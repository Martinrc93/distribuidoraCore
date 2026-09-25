package com.distribuidora.pricing.application;

import com.distribuidora.shared.web.PageResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PricingQueryService {
    private static final String RESOLVED_PRICE_CTE = """
        with resolved_prices as (
            select pl.id as "priceListId", pl.code as "priceListCode",
                   p.id as "productId", p.sku, p.name,
                   h.price, h.effective_on as "effectiveOn",
                   h.created_at as "createdAt", h.updated_at as "updatedAt"
            from catalog.price_lists pl
            cross join catalog.products p
            left join lateral (
                select history.price, history.effective_on, history.created_at, history.updated_at
                from catalog.product_price_history history
                where history.price_list_id = pl.id and history.product_id = p.id
                  and history.effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
                order by history.effective_on desc, history.created_at desc, history.id desc
                limit 1
            ) h on true
            where pl.id = ?
        )
        """;

    private final JdbcTemplate jdbc;

    public PricingQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<Map<String, Object>> lists(int page, int size) {
        return page("""
            select id, code, name, status, is_default as "isDefault",
                   created_at as "createdAt", updated_at as "updatedAt"
            from catalog.price_lists
            order by code
            """, "select count(*) from catalog.price_lists", page, size);
    }

    public PageResponse<Map<String, Object>> prices(UUID listId, int page, int size) {
        return page(RESOLVED_PRICE_CTE + """
            select "priceListId", "priceListCode", "productId", sku, name, price,
                   "effectiveOn", "createdAt", "updatedAt"
            from resolved_prices
            where price is not null
            order by name, sku
            """, RESOLVED_PRICE_CTE + "select count(*) from resolved_prices where price is not null",
            page, size, listId);
    }

    public PageResponse<Map<String, Object>> history(UUID listId, UUID productId, int page, int size) {
        return page("""
            select id, price_list_id as "priceListId", product_id as "productId", price,
                   effective_on as "effectiveOn", created_at as "recordedAt", updated_at as "updatedAt",
                   effective_on > (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date as scheduled
            from catalog.product_price_history
            where price_list_id = ? and product_id = ?
            order by effective_on, created_at, id
            """, "select count(*) from catalog.product_price_history where price_list_id = ? and product_id = ?",
            page, size, listId, productId);
    }

    public Map<String, Object> resolve(UUID customerId, UUID productId, UUID explicitListId) {
        return resolveAsOf(customerId, productId, explicitListId, null);
    }

    public Map<String, Object> resolveAsOf(UUID customerId, UUID productId, UUID explicitListId, LocalDate asOf) {
        Map<String, Object> customer = jdbc.queryForMap(
            "select price_list_id from customer.customers where id = ?", customerId);
        UUID assignedListId = (UUID) customer.get("price_list_id");
        UUID selectedListId = explicitListId != null ? explicitListId : assignedListId;

        Map<String, Object> selectedList = selectedListId == null
            ? jdbc.queryForMap("select id, code, status from catalog.price_lists where code = 'GENERAL' and status = 'ACTIVE'", new Object[0])
            : jdbc.queryForMap("select id, code, status from catalog.price_lists where id = ?", selectedListId);
        if (!"ACTIVE".equals(selectedList.get("status"))) {
            throw new IllegalStateException("La lista de precios no está activa");
        }

        UUID resolvedListId = (UUID) selectedList.get("id");
        try {
            return findPrice(resolvedListId, productId, asOf);
        } catch (EmptyResultDataAccessException missingPrice) {
            List<Map<String, Object>> previousLists = jdbc.queryForList("""
                select id, code, status
                from catalog.price_lists
                where status = 'ACTIVE' and id < ?
                order by id desc
                """, resolvedListId);

            for (Map<String, Object> previousList : previousLists) {
                try {
                    return findPrice((UUID) previousList.get("id"), productId, asOf);
                } catch (EmptyResultDataAccessException ignored) {
                    // Continue with the next previous active list.
                }
            }
            throw missingPrice;
        }
    }

    private Map<String, Object> findPrice(UUID listId, UUID productId, LocalDate asOf) {
        String datePredicate = asOf == null
            ? "h.effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date"
            : "h.effective_on <= ?";
        String sql = """
            select pl.id as "priceListId", pl.code as "priceListCode",
                   p.id as "productId", history.price as "unitPrice",
                   history.effective_on as "effectiveOn"
            from catalog.price_lists pl
            cross join catalog.products p
            join lateral (
                select h.id, h.price, h.effective_on, h.created_at
                from catalog.product_price_history h
                where h.price_list_id = pl.id and h.product_id = p.id and %s
                order by h.effective_on desc, h.created_at desc, h.id desc
                limit 1
            ) history on true
            where pl.id = ? and p.id = ?
            """.formatted(datePredicate);
        return asOf == null
            ? jdbc.queryForMap(sql, listId, productId)
            : jdbc.queryForMap(sql, asOf, listId, productId);
    }

    private PageResponse<Map<String, Object>> page(
        String sql,
        String countSql,
        int page,
        int size,
        Object... parameters
    ) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbc.queryForList(
            sql + " limit ? offset ?", append(parameters, safeSize, safePage * safeSize));
        Number total = jdbc.queryForObject(countSql, Number.class, parameters);
        return PageResponse.of(rows, safePage, safeSize, total == null ? 0 : total.longValue());
    }

    private Object[] append(Object[] values, Object... suffix) {
        Object[] result = java.util.Arrays.copyOf(values, values.length + suffix.length);
        System.arraycopy(suffix, 0, result, values.length, suffix.length);
        return result;
    }
}
