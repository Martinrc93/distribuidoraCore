package com.distribuidora.pricing.application;

import com.distribuidora.shared.web.PageResponse;
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

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PricingQueryService {
    private static final String RESOLVED_PRICE_CTE = """
        with resolved_prices as (
            select pl.id as "priceListId", pl.code as "priceListCode",
                   p.id as "productId", p.name, p.category_id as "categoryId", p.brand_id as "brandId", p.cost,
                   h.price, h.effective_on as "effectiveOn",
                   h.created_at as "createdAt", h.updated_at as "updatedAt",
                   f.review_status as "followStatus"
            from catalog.price_lists pl
            cross join catalog.products p
            left join catalog.price_list_follows f on f.price_list_id = pl.id and f.product_id = p.id
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
        return lists(page, size, true);
    }

    public PageResponse<Map<String, Object>> lists(int page, int size, boolean includeAdminDetails) {
        String reviewCount = includeAdminDetails ? """
            , (select count(*) from catalog.price_list_follows f
               where f.price_list_id = catalog.price_lists.id and f.review_status = 'PENDING') as "pendingReviewCount"
            """ : "";
        String filter = includeAdminDetails ? "" : " where status = 'ACTIVE'";
        return page("""
            select id, code, name, status, is_default as "isDefault",
                   created_at as "createdAt", updated_at as "updatedAt"%s
            from catalog.price_lists%s
            order by code
            """.formatted(reviewCount, filter), "select count(*) from catalog.price_lists" + filter, page, size);
    }

    public PageResponse<Map<String, Object>> prices(UUID listId, int page, int size) {
        return prices(listId, page, size, (PriceFilter) null);
    }

    /**
     * Current prices of a list. {@code followStatus} is PENDING (copied, not reviewed), CONFIRMED (reviewed,
     * still following the default list) or null (own price). Filter values: PENDING, CONFIRMED, FOLLOWING, OWN.
     */
    public PageResponse<Map<String, Object>> prices(UUID listId, int page, int size, String followFilter) {
        return prices(listId, page, size, new PriceFilter(followFilter, null, null, null));
    }

    /** Optional filters for a list's prices: follow status, category (rubro), brand and product name. */
    /** {@code includeCost} adds the product cost to each row; only for administrators. */
    public record PriceFilter(String followStatus, UUID categoryId, UUID brandId, String search, boolean includeCost, boolean activeListOnly) {
        public PriceFilter(String followStatus, UUID categoryId, UUID brandId, String search) {
            this(followStatus, categoryId, brandId, search, false, false);
        }

        public PriceFilter(String followStatus, UUID categoryId, UUID brandId, String search, boolean includeCost) {
            this(followStatus, categoryId, brandId, search, includeCost, false);
        }
    }

    public PageResponse<Map<String, Object>> prices(UUID listId, int page, int size, PriceFilter priceFilter) {
        PriceFilter criteria = priceFilter == null ? new PriceFilter(null, null, null, null) : priceFilter;
        if (criteria.activeListOnly()) {
            boolean active = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from catalog.price_lists where id = ? and status = 'ACTIVE')",
                Boolean.class, listId));
            if (!active) {
                throw new EmptyResultDataAccessException(1);
            }
        }
        StringBuilder filter = new StringBuilder(switch (criteria.followStatus() == null ? "" : criteria.followStatus()) {
            case "" -> "";
            case "PENDING" -> " and \"followStatus\" = 'PENDING'";
            case "CONFIRMED" -> " and \"followStatus\" = 'CONFIRMED'";
            case "FOLLOWING" -> " and \"followStatus\" is not null";
            case "OWN" -> " and \"followStatus\" is null";
            default -> throw new IllegalArgumentException("followStatus debe ser PENDING, CONFIRMED, FOLLOWING u OWN");
        });
        List<Object> parameters = new ArrayList<>(List.of(listId));
        if (criteria.categoryId() != null) {
            filter.append(" and \"categoryId\" = ?");
            parameters.add(criteria.categoryId());
        }
        if (criteria.brandId() != null) {
            filter.append(" and \"brandId\" = ?");
            parameters.add(criteria.brandId());
        }
        String search = criteria.search() == null ? "" : criteria.search().trim();
        if (!search.isEmpty()) {
            if (search.length() > 200) throw new IllegalArgumentException("search admite hasta 200 caracteres");
            filter.append(" and position(lower(?) in lower(name)) > 0");
            parameters.add(search);
        }
        return page(RESOLVED_PRICE_CTE + """
            select "priceListId", "priceListCode", "productId", name, price,
                   "effectiveOn", "createdAt", "updatedAt", "followStatus", "categoryId", "brandId"%s
            from resolved_prices
            where price is not null%s
            order by name, "productId"
            """.formatted(criteria.includeCost() ? ", cost" : "", filter), RESOLVED_PRICE_CTE + "select count(*) from resolved_prices where price is not null" + filter,
            page, size, parameters.toArray());
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
        Map<String, Object> selectedList = selectedList(customerId, explicitListId);
        UUID resolvedListId = (UUID) selectedList.get("id");
        try {
            return findPrice(resolvedListId, productId, asOf);
        } catch (EmptyResultDataAccessException missingPrice) {
            if (Boolean.TRUE.equals(selectedList.get("is_default"))) {
                throw missingPrice;
            }
            List<UUID> defaults = jdbc.queryForList(
                "select id from catalog.price_lists where is_default = true and status = 'ACTIVE'", UUID.class);
            if (defaults == null || defaults.isEmpty() || resolvedListId.equals(defaults.getFirst())) {
                throw missingPrice;
            }
            return findPrice(defaults.getFirst(), productId, asOf);
        }
    }

    public List<Map<String, Object>> resolveBatch(UUID customerId, List<UUID> productIds, UUID explicitListId) {
        if (productIds == null || productIds.isEmpty() || productIds.size() > 100 || productIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("La consulta debe incluir entre 1 y 100 productos válidos");
        }
        UUID listId = (UUID) selectedList(customerId, explicitListId).get("id");
        List<UUID> distinctProducts = productIds.stream().distinct().toList();
        String placeholders = String.join(",", java.util.Collections.nCopies(distinctProducts.size(), "?"));
        Object[] parameters = new Object[distinctProducts.size() + 2];
        parameters[0] = listId;
        parameters[1] = listId;
        for (int i = 0; i < distinctProducts.size(); i++) parameters[i + 2] = distinctProducts.get(i);
        return jdbc.queryForList("""
            select p.id as "productId", resolved."priceListId", resolved."priceListCode", resolved."unitPrice"
            from catalog.products p
            join lateral (
                select candidate.id as "priceListId", candidate.code as "priceListCode", history.price as "unitPrice"
                from catalog.price_lists candidate
                join lateral (
                    select h.price
                    from catalog.product_price_history h
                    where h.price_list_id = candidate.id and h.product_id = p.id
                      and h.effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
                    order by h.effective_on desc, h.created_at desc, h.id desc
                    limit 1
                ) history on true
                where candidate.status = 'ACTIVE' and (candidate.id = ? or candidate.is_default = true)
                order by (candidate.id = ?) desc, candidate.is_default desc
                limit 1
            ) resolved on true
            where p.id in (%s)
            """.formatted(placeholders), parameters);
    }

    private Map<String, Object> selectedList(UUID customerId, UUID explicitListId) {
        Map<String, Object> customer = jdbc.queryForMap(
            "select price_list_id from customer.customers where id = ?", customerId);
        UUID assignedListId = (UUID) customer.get("price_list_id");
        UUID selectedListId = explicitListId != null ? explicitListId : assignedListId;

        Map<String, Object> selectedList = selectedListId == null
            ? jdbc.queryForMap("select id, code, status, is_default from catalog.price_lists where is_default = true and status = 'ACTIVE'", new Object[0])
            : jdbc.queryForMap("select id, code, status, is_default from catalog.price_lists where id = ?", selectedListId);
        if (!"ACTIVE".equals(selectedList.get("status"))) {
            throw new IllegalStateException("La lista de precios no está activa");
        }

        return selectedList;
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
            sql + " limit ? offset ?", append(parameters, safeSize, (long) safePage * safeSize));
        Number total = jdbc.queryForObject(countSql, Number.class, parameters);
        return PageResponse.of(rows, safePage, safeSize, total == null ? 0 : total.longValue());
    }

    private Object[] append(Object[] values, Object... suffix) {
        Object[] result = java.util.Arrays.copyOf(values, values.length + suffix.length);
        System.arraycopy(suffix, 0, result, values.length, suffix.length);
        return result;
    }
}
