package com.distribuidora.pricing.application;

import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CommercialDiscountRuleQueryService {
    private final JdbcTemplate jdbc;

    public CommercialDiscountRuleQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DiscountSnapshot(UUID ruleId, BigDecimal percent) { }

    public PageResponse<Map<String, Object>> rules(int page, int size) {
        String select = """
            select r.id, r.code, r.description, r.kind, r.percent, r.customer_id as "customerId",
                   c.business_name as "customerName", r.price_list_id as "priceListId",
                   pl.code as "priceListCode", r.product_id as "productId", p.sku, p.name as "productName",
                   r.priority, r.status, r.valid_from as "validFrom", r.valid_until as "validUntil",
                   r.created_at as "createdAt", r.updated_at as "updatedAt"
            from catalog.commercial_discount_rules r
            left join customer.customers c on c.id = r.customer_id
            left join catalog.price_lists pl on pl.id = r.price_list_id
            left join catalog.products p on p.id = r.product_id
            order by r.kind, r.priority desc, r.code
            """;
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbc.queryForList(select + " limit ? offset ?", safeSize, safePage * safeSize);
        Number total = jdbc.queryForObject("select count(*) from catalog.commercial_discount_rules", Number.class);
        return PageResponse.of(rows, safePage, safeSize, total == null ? 0 : total.longValue());
    }

    public Optional<DiscountSnapshot> lineDiscount(UUID customerId, UUID productId, UUID priceListId) {
        return jdbc.query("""
            select id, percent
            from catalog.commercial_discount_rules
            where kind = 'LINE' and status = 'ACTIVE' and product_id = ?
              and (customer_id is null or customer_id = ?)
              and (price_list_id is null or price_list_id = ?)
              and valid_from <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
              and (valid_until is null or valid_until >= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date)
            order by priority desc,
                     (case when customer_id is not null then 1 else 0 end
                      + case when price_list_id is not null then 1 else 0 end) desc,
                     created_at desc, id desc
            limit 1
            """, (rs, row) -> new DiscountSnapshot(rs.getObject("id", UUID.class), rs.getBigDecimal("percent")),
            productId, customerId, priceListId).stream().findFirst();
    }

    public Optional<DiscountSnapshot> orderDiscount(UUID customerId, UUID requestedPriceListId) {
        UUID effectivePriceListId = jdbc.queryForObject("""
            select coalesce(cast(? as uuid), c.price_list_id,
                (select id from catalog.price_lists where code = 'GENERAL' and status = 'ACTIVE'))
            from customer.customers c where c.id = ?
            """, UUID.class, requestedPriceListId, customerId);
        return jdbc.query("""
            select id, percent
            from catalog.commercial_discount_rules
            where kind = 'ORDER' and status = 'ACTIVE'
              and (customer_id is null or customer_id = ?)
              and (price_list_id is null or price_list_id = ?)
              and valid_from <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
              and (valid_until is null or valid_until >= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date)
            order by priority desc,
                     (case when customer_id is not null then 1 else 0 end
                      + case when price_list_id is not null then 1 else 0 end) desc,
                     created_at desc, id desc
            limit 1
            """, (rs, row) -> new DiscountSnapshot(rs.getObject("id", UUID.class), rs.getBigDecimal("percent")),
            customerId, effectivePriceListId).stream().findFirst();
    }
}
