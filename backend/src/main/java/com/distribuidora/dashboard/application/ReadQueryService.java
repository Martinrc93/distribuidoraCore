package com.distribuidora.dashboard.application;

import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReadQueryService {
    private final JdbcTemplate jdbc;

    public ReadQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> dashboard() {
        return Map.of(
            "confirmedOrders", number("select count(*) from orders.orders where status = 'CONFIRMED'"),
            "todaySales", number("select coalesce(sum(total), 0) from sale.sales where created_at::date = current_date"),
            "pendingBalance", number("select coalesce(sum(balance), 0) from customer.customers where balance > 0"),
            "negativeStock", number("select count(*) from inventory.inventory_balances where quantity < 0"),
            "recentOrders", jdbc.queryForList("""
                select o.order_number as id, c.business_name as customer,
                       coalesce(sp.display_name, 'Sin asignar') as seller,
                       o.total, o.status
                from orders.orders o
                join customer.customers c on c.id = o.customer_id
                left join seller.seller_profiles sp on sp.id = o.seller_id
                order by o.created_at desc limit 5
                """)
        );
    }

    public PageResponse<Map<String, Object>> customers(int page, int size, String search) {
        String term = like(search);
        return page("""
            select c.id, c.business_name as name, c.tax_id as "taxId",
                   coalesce(sp.display_name, 'Sin asignar') as seller,
                   c.balance, c.status
            from customer.customers c
            left join seller.seller_profiles sp on sp.id = c.seller_id
            where lower(c.business_name) like ? or lower(c.tax_id) like ?
            order by c.business_name
            """, "select count(*) from customer.customers where lower(business_name) like ? or lower(tax_id) like ?",
            page, size, term, term);
    }

    public PageResponse<Map<String, Object>> products(int page, int size, String search) {
        String term = like(search);
        return page("""
            select p.id, p.name, p.category, p.presentation, p.cost, p.price,
                   coalesce(ib.quantity, 0) as stock, p.status
            from catalog.products p
            left join inventory.inventory_balances ib on ib.product_id = p.id
            where lower(p.name) like ? or lower(p.sku) like ?
            order by p.name
            """, "select count(*) from catalog.products where lower(name) like ? or lower(sku) like ?",
            page, size, term, term);
    }

    public PageResponse<Map<String, Object>> inventory(int page, int size, String search) {
        String term = like(search);
        return page("""
            select p.id, p.name as product, ib.quantity as stock,
                   sm.movement_type as "lastMovement", ib.updated_at as updated
            from inventory.inventory_balances ib
            join catalog.products p on p.id = ib.product_id
            left join lateral (select movement_type from inventory.stock_movements
                where product_id = p.id order by created_at desc limit 1) sm on true
            where lower(p.name) like ?
            order by p.name
            """, "select count(*) from catalog.products where lower(name) like ?",
             page, size, term);
    }

    public PageResponse<Map<String, Object>> movements(UUID productId, int page, int size) {
        return page("""
            select sm.id, sm.movement_type as "movementType", sm.quantity, sm.reason,
                   sm.reference_type as "referenceType", sm.reference_id as "referenceId",
                   sm.created_at as date
            from inventory.stock_movements sm
            where sm.product_id = ?
            order by sm.created_at desc
            """, "select count(*) from inventory.stock_movements where product_id = ?",
            page, size, productId);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status) {
        String term = like(search);
        String state = status == null || status.isBlank() ? "%" : status;
        return page("""
            select o.id, o.order_number as number, c.business_name as customer,
                   coalesce(sp.display_name, 'Sin asignar') as seller,
                   o.total, o.status, o.created_at as date
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            where (lower(c.business_name) like ? or lower(o.order_number) like ?)
              and o.status like ?
            order by o.created_at desc
            """, """
            select count(*) from orders.orders o join customer.customers c on c.id = o.customer_id
            where (lower(c.business_name) like ? or lower(o.order_number) like ?) and o.status like ?
             """, page, size, term, term, state);
    }

    public Map<String, Object> orderDetail(UUID orderId) {
        Map<String, Object> order = jdbc.queryForMap("""
            select o.id, o.order_number as number, o.customer_id as "customerId",
                   c.business_name as customer, o.status, o.subtotal, o.discount, o.total,
                   c.balance as "customerBalance", o.created_at as date
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            where o.id = ?
            """, orderId);
        return detail(order, orderId);
    }

    public Map<String, Object> orderDetailByNumber(String orderNumber) {
        Map<String, Object> order = jdbc.queryForMap("""
            select o.id, o.order_number as number, o.customer_id as "customerId",
                   c.business_name as customer, o.status, o.subtotal, o.discount, o.total,
                   c.balance as "customerBalance", o.created_at as date
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            where o.order_number = ?
            """, orderNumber);
        return detail(order, (UUID) order.get("id"));
    }

    private Map<String, Object> detail(Map<String, Object> order, UUID orderId) {
        UUID customerId = (UUID) order.get("customerId");
        Map<String, Object> sale = jdbc.queryForMap("""
            select s.id, s.sale_number as number, s.status, s.total, s.paid,
                   (s.total - s.paid) as balance, s.created_at as date
            from sale.sales s where s.order_id = ?
            """, orderId);
        UUID saleId = (UUID) sale.get("id");
        Map<String, Object> account = jdbc.queryForMap("""
            select coalesce(sum(amount) filter (where entry_type = 'DEBIT'), 0) as debit,
                   coalesce(sum(amount) filter (where entry_type = 'CREDIT'), 0) as credit,
                   coalesce(sum(amount) filter (where entry_type = 'DEBIT'), 0)
                     - coalesce(sum(amount) filter (where entry_type = 'CREDIT'), 0) as net
            from customer.account_ledger where customer_id = ? and sale_id = ?
            """, customerId, saleId);
        return Map.of(
            "order", order,
            "items", jdbc.queryForList("""
                select oi.product_id as "productId", oi.product_name as "productName",
                       oi.quantity, oi.unit_price as "unitPrice", oi.line_total as "lineTotal",
                       oi.price_list_id as "priceListId", oi.price_list_code as "priceListCode",
                       oi.line_discount_percent as "lineDiscountPercent"
                from orders.order_items oi where oi.order_id = ? order by oi.id
                """, orderId),
            "sale", sale,
            "payments", jdbc.queryForList("""
                select p.id, p.amount, p.method, p.created_at as date
                from payment.payments p where p.sale_id = ? order by p.created_at, p.id
                """, saleId),
            "account", account
        );
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search) {
        String term = like(search);
        return page("""
            select s.id, s.sale_number as number, c.business_name as customer,
                   s.total, s.paid, (s.total - s.paid) as balance,
                   s.status, s.created_at as date
            from sale.sales s join customer.customers c on c.id = s.customer_id
            where lower(c.business_name) like ? or lower(s.sale_number) like ?
            order by s.created_at desc
            """, "select count(*) from sale.sales s join customer.customers c on c.id = s.customer_id where lower(c.business_name) like ? or lower(s.sale_number) like ?",
            page, size, term, term);
    }

    public PageResponse<Map<String, Object>> payments(int page, int size, String search) {
        String term = like(search);
        return page("""
            select p.id, c.business_name as customer, s.sale_number as sale,
                   p.amount, p.method, p.created_at as date
            from payment.payments p
            join customer.customers c on c.id = p.customer_id
            join sale.sales s on s.id = p.sale_id
            where lower(c.business_name) like ? or lower(s.sale_number) like ?
            order by p.created_at desc
            """, "select count(*) from payment.payments p join customer.customers c on c.id = p.customer_id join sale.sales s on s.id = p.sale_id where lower(c.business_name) like ? or lower(s.sale_number) like ?",
            page, size, term, term);
    }

    public PageResponse<Map<String, Object>> users(int page, int size, String search) {
        String term = like(search);
        return page("""
            select id, email, email as name, status
            from identity.users where lower(email) like ? order by email
            """, "select count(*) from identity.users where lower(email) like ?",
            page, size, term);
    }

    private PageResponse<Map<String, Object>> page(String sql, String countSql, int page, int size, Object... parameters) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Map<String, Object>> rows = jdbc.queryForList(sql + " limit ? offset ?", append(parameters, safeSize, safePage * safeSize));
        Number total = jdbc.queryForObject(countSql, Number.class, parameters);
        return PageResponse.of(rows, safePage, safeSize, total == null ? 0 : total.longValue());
    }

    private Object[] append(Object[] values, Object... suffix) {
        Object[] result = java.util.Arrays.copyOf(values, values.length + suffix.length);
        System.arraycopy(suffix, 0, result, values.length, suffix.length);
        return result;
    }

    private String like(String value) {
        return "%" + (value == null ? "" : value.trim().toLowerCase()) + "%";
    }

    private Number number(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value;
    }
}
