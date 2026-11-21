package com.distribuidora.document.application;

import com.distribuidora.document.rendering.PriceListDocumentModel;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the printable price list: current prices (Buenos Aires business date) of active products,
 * optionally filtered by category (rubro), brand and name. Never includes product cost.
 */
@Service
public class PriceListDocumentService {
    private record PriceList(UUID id, String name, String code) { }

    private final JdbcTemplate jdbc;

    public PriceListDocumentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PriceListDocumentModel load(UUID listId, UUID categoryId, UUID brandId, String search) {
        PriceList list;
        try {
            list = jdbc.queryForObject("select name, code from catalog.price_lists where id = ?",
                (rs, row) -> new PriceList(listId, rs.getString("name"), rs.getString("code")), listId);
        } catch (EmptyResultDataAccessException exception) {
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
        LocalDate today = businessDate();
        return jdbc.query("select id, name, code from catalog.price_lists where status = 'ACTIVE' order by lower(name), code",
                (rs, row) -> new PriceList(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("code")))
            .stream()
            .map(list -> new PriceListDocumentModel(list.name(), list.code(), today, "", loadLines("", list.id())))
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
