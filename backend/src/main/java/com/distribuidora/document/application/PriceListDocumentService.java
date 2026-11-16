package com.distribuidora.document.application;

import com.distribuidora.document.rendering.PriceListDocumentModel;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the printable price list: current prices (Buenos Aires business date) of active products,
 * optionally filtered by category (rubro), brand and name. Never includes product cost.
 */
@Service
public class PriceListDocumentService {
    private final JdbcTemplate jdbc;

    public PriceListDocumentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PriceListDocumentModel load(UUID listId, UUID categoryId, UUID brandId, String search) {
        Map<String, Object> list;
        try {
            list = jdbc.queryForMap("select name, code, status from catalog.price_lists where id = ?", listId);
        } catch (EmptyResultDataAccessException exception) {
            throw new DocumentNotFoundException("Lista de precios inexistente");
        }
        String normalizedSearch = search == null ? "" : search.trim();
        if (normalizedSearch.length() > 200) throw new IllegalArgumentException("search admite hasta 200 caracteres");
        List<Object> parameters = new ArrayList<>(List.of(listId));
        StringBuilder where = new StringBuilder(" where p.status = 'ACTIVE'");
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
        LocalDate today = jdbc.queryForObject(
            "select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class);
        List<PriceListDocumentModel.Line> lines = jdbc.query("""
            select coalesce(c.name, p.category, '') as category, p.name, h.price
            from catalog.products p
            left join catalog.categories c on c.id = p.category_id
            join lateral (
                select history.price from catalog.product_price_history history
                where history.price_list_id = ? and history.product_id = p.id
                  and history.effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
                order by history.effective_on desc, history.created_at desc, history.id desc
                limit 1
            ) h on true
            """ + where + " order by lower(coalesce(c.name, p.category, '')), lower(p.name), p.id",
            (rs, row) -> new PriceListDocumentModel.Line(rs.getString("category"), rs.getString("name"), rs.getBigDecimal("price")),
            parameters.toArray());
        return new PriceListDocumentModel((String) list.get("name"), (String) list.get("code"), today, String.join(", ", filters), lines);
    }

    private String name(String table, UUID id) {
        List<String> names = jdbc.queryForList("select name from " + table + " where id = ?", String.class, id);
        if (names.isEmpty()) throw new DocumentNotFoundException("Filtro inexistente");
        return names.getFirst();
    }
}
