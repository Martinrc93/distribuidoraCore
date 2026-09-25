package com.distribuidora.dashboard.application;

import com.distribuidora.shared.web.PageResponse;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReadQueryService {
    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    public ReadQueryService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public ReadQueryService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
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
        if (sellerScoped()) {
            UUID sellerId = currentUser.requireSellerProfile();
            return page("""
                select c.id, c.business_name as name, c.tax_id as "cuitId", c.seller_id as "sellerId",
                       c.price_list_id as "priceListId", coalesce(sp.display_name, 'Sin asignar') as seller, c.balance, c.status
                from customer.customers c left join seller.seller_profiles sp on sp.id = c.seller_id
                where c.seller_id = ? and (lower(c.business_name) like ? or lower(c.tax_id) like ?)
                order by c.business_name
                """, "select count(*) from customer.customers where seller_id = ? and (lower(business_name) like ? or lower(tax_id) like ?)",
                page, size, sellerId, term, term);
        }
        return page("""
            select c.id, c.business_name as name, c.tax_id as "cuitId", c.seller_id as "sellerId",
                   c.price_list_id as "priceListId", coalesce(sp.display_name, 'Sin asignar') as seller,
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
        if (sellerScoped()) {
            return page("""
                select p.id, p.sku, p.name, coalesce(cat.name, p.category) as category,
                       p.category_id as "categoryId", p.brand_id as "brandId", p.presentation,
                       coalesce(ib.quantity, 0) as stock, p.status
                from catalog.products p
                left join catalog.categories cat on cat.id = p.category_id
                left join inventory.inventory_balances ib on ib.product_id = p.id
                where lower(p.name) like ? or lower(p.sku) like ? order by p.name
                """, "select count(*) from catalog.products where lower(name) like ? or lower(sku) like ?",
                page, size, term, term);
        }
        return page("""
            select p.id, p.sku, p.name, coalesce(cat.name, p.category) as category,
                   p.category_id as "categoryId", p.brand_id as "brandId", p.presentation, p.cost,
                   coalesce(ib.quantity, 0) as stock, p.status
            from catalog.products p
            left join catalog.categories cat on cat.id = p.category_id
            left join inventory.inventory_balances ib on ib.product_id = p.id
            where lower(p.name) like ? or lower(p.sku) like ?
            order by p.name
            """, "select count(*) from catalog.products where lower(name) like ? or lower(sku) like ?",
            page, size, term, term);
    }

    public PageResponse<Map<String, Object>> inventory(int page, int size, String search) {
        String term = like(search);
        return page("""
            select p.id, p.name as product,
                   coalesce(ib.quantity, 0) as stock,
                   sm.movement_type as "lastMovement",
                   sm.created_at as updated
            from catalog.products p
            left join inventory.inventory_balances ib on ib.product_id = p.id
            left join lateral (select movement_type, created_at from inventory.stock_movements
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
        if (sellerScoped()) {
            UUID sellerId = currentUser.requireSellerProfile();
            return page("""
                select o.id, o.order_number as number, c.business_name as customer,
                       coalesce(sp.display_name, 'Sin asignar') as seller, o.total, o.status, o.created_at as date
                from orders.orders o join customer.customers c on c.id = o.customer_id
                left join seller.seller_profiles sp on sp.id = o.seller_id
                where (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))
                  and (lower(c.business_name) like ? or lower(o.order_number) like ?) and o.status like ?
                order by o.created_at desc
                """, """
                select count(*) from orders.orders o join customer.customers c on c.id = o.customer_id
                where (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))
                  and (lower(c.business_name) like ? or lower(o.order_number) like ?) and o.status like ?
                """, page, size, sellerId, sellerId, term, term, state);
        }
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
        if (sellerScoped()) currentUser.requireOrderAccess(orderId);
        Map<String, Object> order = jdbc.queryForMap("""
            select o.id, o.order_number as number, o.customer_id as "customerId",
                   c.business_name as customer, o.status, o.subtotal, o.discount, o.total,
                   o.order_discount_percent as "orderDiscountPercent",
                   o.order_discount_rule_id as "orderDiscountRuleId",
                   o.credit_limit_exceeded as "creditLimitExceeded", o.credit_limit_snapshot as "creditLimitSnapshot",
                   o.projected_balance_snapshot as "projectedBalanceSnapshot",
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
                   o.order_discount_percent as "orderDiscountPercent",
                   o.order_discount_rule_id as "orderDiscountRuleId",
                   o.credit_limit_exceeded as "creditLimitExceeded", o.credit_limit_snapshot as "creditLimitSnapshot",
                   o.projected_balance_snapshot as "projectedBalanceSnapshot",
                   c.balance as "customerBalance", o.created_at as date
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
             where o.order_number = ?
             """, orderNumber);
        if (sellerScoped()) currentUser.requireOrderAccess((UUID) order.get("id"));
        return detail(order, (UUID) order.get("id"));
    }

    private Map<String, Object> detail(Map<String, Object> order, UUID orderId) {
        UUID customerId = (UUID) order.get("customerId");
        Map<String, Object> sale = jdbc.queryForMap("""
            select s.id, s.sale_number as number, s.status, s.total, s.paid,
                   s.order_discount_percent as "orderDiscountPercent",
                   s.order_discount_rule_id as "orderDiscountRuleId",
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
                       oi.line_discount_percent as "lineDiscountPercent",
                       oi.discount_rule_id as "discountRuleId"
                from orders.order_items oi where oi.order_id = ? order by oi.id
                """, orderId),
            "sale", sale,
            "payments", jdbc.queryForList("""
                select p.id, p.amount, p.method, p.transfer_reference as "transferReference", p.created_at as date
                from payment.payments p where p.sale_id = ? order by p.created_at, p.id
                """, saleId),
            "account", account
        );
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search) {
        String term = like(search);
        if (sellerScoped()) {
            UUID sellerId = currentUser.requireSellerProfile();
            return page("""
                select s.id, s.sale_number as number, c.business_name as customer, s.total, s.paid,
                       (s.total - s.paid) as balance, s.status, s.created_at as date
                from sale.sales s join orders.orders o on o.id = s.order_id
                join customer.customers c on c.id = s.customer_id
                where (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))
                  and (lower(c.business_name) like ? or lower(s.sale_number) like ?) order by s.created_at desc
                """, """
                select count(*) from sale.sales s join orders.orders o on o.id = s.order_id
                join customer.customers c on c.id = s.customer_id
                where (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))
                  and (lower(c.business_name) like ? or lower(s.sale_number) like ?)
                """, page, size, sellerId, sellerId, term, term);
        }
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
        if (sellerScoped()) {
            UUID sellerId = currentUser.requireSellerProfile();
            return page("""
                select p.id, c.business_name as customer, s.sale_number as sale, p.amount, p.method,
                       p.transfer_reference as "transferReference", p.created_at as date
                from payment.payments p join customer.customers c on c.id = p.customer_id
                join sale.sales s on s.id = p.sale_id join orders.orders o on o.id = s.order_id
                where (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))
                  and (lower(c.business_name) like ? or lower(s.sale_number) like ?) order by p.created_at desc
                """, """
                select count(*) from payment.payments p join customer.customers c on c.id = p.customer_id
                join sale.sales s on s.id = p.sale_id join orders.orders o on o.id = s.order_id
                where (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))
                  and (lower(c.business_name) like ? or lower(s.sale_number) like ?)
                """, page, size, sellerId, sellerId, term, term);
        }
        return page("""
            select p.id, c.business_name as customer, s.sale_number as sale,
                   p.amount, p.method, p.transfer_reference as "transferReference", p.created_at as date
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
            select u.id, u.email, u.email as name, u.status,
                   coalesce((select string_agg(r.code, ',' order by r.code)
                       from identity.user_roles ur join identity.roles r on r.id = ur.role_id
                       where ur.user_id = u.id), '') as roles,
                   sp.display_name as "sellerDisplayName"
            from identity.users u
            left join seller.seller_profiles sp on sp.user_id = u.id
            where lower(u.email) like ? order by lower(u.email)
            """, "select count(*) from identity.users u where lower(u.email) like ?",
             page, size, term);
    }

    public PageResponse<Map<String, Object>> sellers(int page, int size) {
        return page("""
            select sp.id, sp.display_name as "displayName", u.email
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            order by sp.display_name
            """, "select count(*) from seller.seller_profiles sp join identity.users u on u.id = sp.user_id",
            page, size);
    }

    public PageResponse<Map<String, Object>> sellers(int page, int size, String search, String status) {
        String term = like(search);
        String state = status == null || status.isBlank() ? "%" : status.trim();
        return page("""
            select sp.id, sp.user_id as "userId", sp.display_name as "displayName",
                   u.email, sp.status, sp.created_at as "createdAt",
                   (select count(*) from customer.customers c where c.seller_id = sp.id) as "assignedCustomersCount"
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            where (lower(sp.display_name) like ? or lower(u.email) like ?)
              and sp.status like ?
            order by sp.display_name
            """, """
            select count(*)
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            where (lower(sp.display_name) like ? or lower(u.email) like ?)
              and sp.status like ?
            """, page, size, term, term, state);
    }

    public Map<String, Object> sellerDetail(UUID id) {
        return jdbc.queryForMap("""
            select sp.id, sp.user_id as "userId", sp.display_name as "displayName",
                   u.email, sp.status, sp.created_at as "createdAt",
                   (select count(*) from customer.customers c where c.seller_id = sp.id) as "assignedCustomersCount"
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            where sp.id = ?
            """, id);
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

    private boolean sellerScoped() {
        return currentUser != null && !currentUser.isAdmin();
    }
}
