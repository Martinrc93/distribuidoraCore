package com.distribuidora.pricing.application;

import com.distribuidora.shared.web.PageResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PricingQueryService {
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
        return page("""
            select pp.price_list_id as "priceListId", pp.product_id as "productId",
                   p.sku, p.name, pp.price,
                   pp.created_at as "createdAt", pp.updated_at as "updatedAt"
            from catalog.product_prices pp
            join catalog.products p on p.id = pp.product_id
            where pp.price_list_id = ?
            order by p.name, p.sku
            """, "select count(*) from catalog.product_prices where price_list_id = ?", page, size, listId);
    }

    public Map<String, Object> resolve(UUID customerId, UUID productId, UUID explicitListId) {
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
            return findPrice(resolvedListId, productId);
        } catch (EmptyResultDataAccessException missingPrice) {
            List<Map<String, Object>> previousLists = jdbc.queryForList("""
                select id, code, status
                from catalog.price_lists
                where status = 'ACTIVE' and id < ?
                order by id desc
                """, resolvedListId);

            for (Map<String, Object> previousList : previousLists) {
                try {
                    return findPrice((UUID) previousList.get("id"), productId);
                } catch (EmptyResultDataAccessException ignored) {
                    // Continue with the next previous active list.
                }
            }
            throw missingPrice;
        }
    }

    private Map<String, Object> findPrice(UUID listId, UUID productId) {
        Map<String, Object> price = jdbc.queryForMap("""
            select pp.price_list_id as "priceListId", pl.code as "priceListCode",
                   pp.product_id as "productId", pp.price as "unitPrice"
            from catalog.product_prices pp
            join catalog.price_lists pl on pl.id = pp.price_list_id
            join catalog.products p on p.id = pp.product_id
            where pp.price_list_id = ? and pp.product_id = ?
            """, listId, productId);
        if (price.isEmpty()) {
            throw new EmptyResultDataAccessException(1);
        }
        return price;
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
