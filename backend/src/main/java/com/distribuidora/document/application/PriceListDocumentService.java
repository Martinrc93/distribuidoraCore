package com.distribuidora.document.application;

import com.distribuidora.document.rendering.PriceListDocumentModel;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Objects;

/**
 * Builds the printable price list: current prices (Buenos Aires business date) of active products,
 * optionally filtered by category (rubro), brand and name. Never includes product cost.
 */
@Service
public class PriceListDocumentService {
    private record PriceList(UUID id, String name, String code, String status) { }

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    @Autowired
    public PriceListDocumentService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PriceListDocumentModel load(UUID listId, UUID categoryId, UUID brandId, String search) {
        PriceList list;
        try {
            list = jdbc.queryForObject("select name, code, status from catalog.price_lists where id = ?",
                (rs, row) -> new PriceList(listId, rs.getString("name"), rs.getString("code"), rs.getString("status")), listId);
        } catch (EmptyResultDataAccessException exception) {
            throw new DocumentNotFoundException("Lista de precios inexistente");
        }
        if (!currentUser.isAdmin() && !"ACTIVE".equals(list.status())) {
            throw new DocumentNotFoundException("Lista de precios inexistente");
        }
        String normalizedSearch = search == null ? "" : search.trim();
        if (normalizedSearch.length() > 200) throw new IllegalArgumentException("search admite hasta 200 caracteres");
        List<Object> parameters = new ArrayList<>(List.of(listId));
        StringBuilder where = new StringBuilder();
        List<String> filters = new ArrayList<>();
        if (categoryId != null) {
            where.append(" and p.category_id = ?");
            parameters.add(categoryId);
            filters.add("categoría " + name("catalog.categories", categoryId));
        }
        if (brandId != null) {
            where.append(" and p.brand_id = ?");
            parameters.add(brandId);
            filters.add("marca " + name("catalog.brands", brandId));
        }
        if (!normalizedSearch.isEmpty()) {
            where.append(" and position(lower(?) in lower(p.name)) > 0");
            parameters.add(normalizedSearch);
            filters.add("«" + normalizedSearch + "»");
        }
        return new PriceListDocumentModel(list.name(), list.code(), businessDate(), String.join(", ", filters),
            loadLines(where.toString(), parameters.toArray()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<PriceListDocumentModel> loadActiveLists() {
        record ExportList(String name, String code, LocalDate date, List<PriceListDocumentModel.Line> lines) { }
        var lists = new LinkedHashMap<UUID, ExportList>();
        jdbc.query("""
            select l.id, l.name as list_name, l.code,
                   (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date as business_date,
                   products.product, products.category, products.price
            from catalog.price_lists l
            left join lateral (
                select p.name as product, coalesce(nullif(trim(coalesce(c.name, p.category)), ''), 'Sin categoría') as category,
                       h.price
                from catalog.products p
                left join catalog.categories c on c.id = p.category_id
                join lateral (
                    select history.price from catalog.product_price_history history
                    where history.price_list_id = l.id and history.product_id = p.id
                      and history.effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
                    order by history.effective_on desc, history.created_at desc, history.id desc
                    limit 1
                ) h on true
                where p.status = 'ACTIVE'
            ) products on true
            where l.status = 'ACTIVE'
            order by lower(l.name), l.code, products.category collate "C", products.product collate "C"
            """, result -> {
            while (result.next()) {
                UUID id = result.getObject("id", UUID.class);
                ExportList list = lists.get(id);
                if (list == null) {
                    list = new ExportList(result.getString("list_name"), result.getString("code"),
                        result.getObject("business_date", LocalDate.class), new ArrayList<>());
                    lists.put(id, list);
                }
                if (result.getString("product") != null) {
                    list.lines().add(new PriceListDocumentModel.Line(
                        result.getString("category"), result.getString("product"), result.getBigDecimal("price")));
                }
            }
            return null;
        });
        return lists.values().stream()
            .map(list -> new PriceListDocumentModel(list.name(), list.code(), list.date(), "", sortLines(list.lines())))
            .toList();
    }

    private LocalDate businessDate() {
        return jdbc.queryForObject(
            "select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class);
    }

    private List<PriceListDocumentModel.Line> loadLines(String filters, Object... parameters) {
        List<PriceListDocumentModel.Line> lines = jdbc.query("""
            select coalesce(nullif(trim(coalesce(c.name, p.category)), ''), 'Sin categoría') as category, p.name, h.price
            from catalog.products p
            left join catalog.categories c on c.id = p.category_id
            join lateral (
                select history.price from catalog.product_price_history history
                where history.price_list_id = ? and history.product_id = p.id
                  and history.effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
                order by history.effective_on desc, history.created_at desc, history.id desc
                limit 1
            ) h on true
            where p.status = 'ACTIVE'
            """ + filters + " order by p.id",
            (rs, row) -> new PriceListDocumentModel.Line(rs.getString("category"), rs.getString("name"), rs.getBigDecimal("price")),
            parameters);
        return sortLines(lines);
    }

    private List<PriceListDocumentModel.Line> sortLines(List<PriceListDocumentModel.Line> lines) {
        Collator spanish = Collator.getInstance(Locale.forLanguageTag("es-AR"));
        spanish.setStrength(Collator.SECONDARY);
        lines.sort(Comparator.comparing(PriceListDocumentModel.Line::category, spanish)
            .thenComparing(PriceListDocumentModel.Line::category)
            .thenComparing(PriceListDocumentModel.Line::product, spanish));
        return lines;
    }

    private String name(String table, UUID id) {
        List<String> names = jdbc.queryForList("select name from " + table + " where id = ?", String.class, id);
        if (names.isEmpty()) throw new DocumentNotFoundException("Filtro inexistente");
        return names.getFirst();
    }
}
