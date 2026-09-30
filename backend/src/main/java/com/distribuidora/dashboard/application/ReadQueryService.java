package com.distribuidora.dashboard.application;

import com.distribuidora.shared.web.PageResponse;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;

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
        return customers(page, size, search, null, false, "");
    }

    public PageResponse<Map<String, Object>> customers(int page, int size, String search,
            UUID sellerId, boolean hasBalance, String status) {
        String state = status == null ? "" : status.trim();
        if (!state.isEmpty() && !state.equals("ACTIVE") && !state.equals("INACTIVE")) {
            throw new IllegalArgumentException("El estado del cliente debe ser ACTIVE o INACTIVE");
        }
        String term = like(search);
        String where = " where (lower(c.business_name) like ? or lower(c.tax_id) like ?)";
        List<Object> parameters = new ArrayList<>(List.of(term, term));
        if (sellerScoped()) {
            where += " and c.seller_id = ?";
            parameters.add(currentUser.requireSellerProfile());
        }
        if (sellerId != null) {
            where += " and c.seller_id = ?";
            parameters.add(sellerId);
        }
        if (hasBalance) where += " and c.balance <> 0";
        if (!state.isEmpty()) {
            where += " and c.status = ?";
            parameters.add(state);
        }
        return page("""
            select c.id, c.business_name as name, c.tax_id as "cuitId", c.email, c.phone, c.address, c.zone, c.seller_id as "sellerId",
                   c.price_list_id as "priceListId", coalesce(sp.display_name, 'Sin asignar') as seller,
                   c.balance, c.status
            from customer.customers c
            left join seller.seller_profiles sp on sp.id = c.seller_id
            """ + where + " order by c.business_name, c.id", "select count(*) from customer.customers c" + where,
            page, size, parameters.toArray());
    }

    public Map<String, Object> customerFilterOptions() {
        String where = "";
        List<Object> parameters = new ArrayList<>();
        if (sellerScoped()) {
            where = " where c.seller_id = ?";
            parameters.add(currentUser.requireSellerProfile());
        }
        return Map.of("sellers", jdbc.queryForList("""
            select distinct sp.id, sp.display_name as name
            from customer.customers c join seller.seller_profiles sp on sp.id = c.seller_id
            """ + where + " order by sp.display_name, sp.id", parameters.toArray()));
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
        return orders(page, size, search, status, null, null);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status,
            LocalDate dateMin, LocalDate dateMax) {
        if (dateMin != null && dateMax != null && dateMin.isAfter(dateMax)) {
            throw new IllegalArgumentException("La fecha mínima no puede ser posterior a la fecha máxima");
        }
        String term = like(search);
        String state = status == null || status.isBlank() ? "%" : status;
        String where = " where (lower(c.business_name) like ? or lower(o.order_number) like ?) and o.status like ?";
        List<Object> parameters = new ArrayList<>(List.of(term, term, state));
        if (sellerScoped()) {
            UUID sellerId = currentUser.requireSellerProfile();
            where += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        ZoneId zone = ZoneId.of("America/Argentina/Buenos_Aires");
        if (dateMin != null) {
            where += " and o.created_at >= ?";
            parameters.add(Timestamp.from(dateMin.atStartOfDay(zone).toInstant()));
        }
        if (dateMax != null) {
            where += " and o.created_at < ?";
            parameters.add(Timestamp.from(dateMax.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        return page("""
            select o.id, o.order_number as number, c.business_name as customer,
                   coalesce(sp.display_name, 'Sin asignar') as seller,
                   o.total, o.status, o.created_at as date
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            """ + where + " order by o.created_at desc, o.id desc", """
            select count(*) from orders.orders o join customer.customers c on c.id = o.customer_id
             """ + where, page, size, parameters.toArray());
    }

    public Map<String, Object> lastCustomerOrder(UUID customerId) {
        if (currentUser != null) currentUser.requireCustomerAccess(customerId);
        String where = " where o.customer_id = ? and o.status in ('CONFIRMED', 'DELIVERED')";
        List<Object> parameters = new ArrayList<>(List.of(customerId));
        if (sellerScoped()) {
            UUID sellerId = currentUser.requireSellerProfile();
            where += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        List<Map<String, Object>> orders = jdbc.queryForList("""
            select o.id, o.order_number as "number", o.order_discount_percent as "orderDiscountPercent"
            from orders.orders o join customer.customers c on c.id = o.customer_id
            """ + where + " order by o.created_at desc, o.id desc limit 1", parameters.toArray());
        if (orders.isEmpty()) return Map.of("available", false);
        Map<String, Object> order = orders.getFirst();
        List<Map<String, Object>> items = jdbc.queryForList("""
            select oi.product_id as "productId", coalesce(p.name, oi.product_name) as "productName",
                   p.sku, p.presentation, p.status, coalesce(ib.quantity, 0) as stock,
                   oi.quantity, oi.line_discount_percent as "lineDiscountPercent"
            from orders.order_items oi
            left join catalog.products p on p.id = oi.product_id
            left join inventory.inventory_balances ib on ib.product_id = oi.product_id
            where oi.order_id = ? order by oi.id
            """, order.get("ID"));
        return Map.of("available", !items.isEmpty(), "orderId", order.get("ID"),
            "orderNumber", order.get("number"), "orderDiscountPercent", order.get("orderDiscountPercent"), "items", items);
    }

    public PageResponse<Map<String, Object>> customerDebts(UUID customerId, int page, int size) {
        if (currentUser != null) currentUser.requireCustomerAccess(customerId);
        jdbc.queryForObject("select id from customer.customers where id = ?", UUID.class, customerId);
        String rows = """
            select s.id as "saleId", s.sale_number as "saleNumber", s.status,
                   s.total, s.paid, o.id as "orderId", o.order_number as "orderNumber",
                   s.created_at as "createdAt",
                   least(greatest(coalesce(l.debit, 0) - coalesce(l.credit, 0), 0), greatest(s.total - s.paid, 0)) as balance
            from sale.sales s
            join orders.orders o on o.id = s.order_id
            left join lateral (
                select sum(amount) filter (where entry_type = 'DEBIT') as debit,
                       sum(amount) filter (where entry_type = 'CREDIT') as credit
                from customer.account_ledger where sale_id = s.id
            ) l on true
            where s.customer_id = ? and s.status in ('CONFIRMED', 'DELIVERED')
              and least(greatest(coalesce(l.debit, 0) - coalesce(l.credit, 0), 0), greatest(s.total - s.paid, 0)) > 0
            order by s.created_at, s.id
            """;
        String count = """
            select count(*) from sale.sales s
            left join lateral (
                select sum(amount) filter (where entry_type = 'DEBIT') as debit,
                       sum(amount) filter (where entry_type = 'CREDIT') as credit
                from customer.account_ledger where sale_id = s.id
            ) l on true
            where s.customer_id = ? and s.status in ('CONFIRMED', 'DELIVERED')
              and least(greatest(coalesce(l.debit, 0) - coalesce(l.credit, 0), 0), greatest(s.total - s.paid, 0)) > 0
            """;
        return page(rows, count, page, size, customerId);
    }

    public Map<String, Object> orderDetail(UUID orderId) {
        if (sellerScoped()) currentUser.requireOrderAccess(orderId);
        Map<String, Object> order = jdbc.queryForMap("""
            select o.id, o.order_number as number, o.customer_id as "customerId",
                   c.business_name as customer,
                   coalesce(assigned_seller.display_name, customer_seller.display_name, 'Sin asignar') as seller,
                   o.status, o.subtotal, o.discount, o.total,
                   o.order_discount_percent as "orderDiscountPercent",
                   o.order_discount_rule_id as "orderDiscountRuleId",
                   o.credit_limit_exceeded as "creditLimitExceeded", o.credit_limit_snapshot as "creditLimitSnapshot",
                   o.projected_balance_snapshot as "projectedBalanceSnapshot",
                   c.balance as "customerBalance", o.previous_balance_amount as "previousBalanceAmount",
                   (o.total + o.previous_balance_amount) as "collectionTotal", o.created_at as date
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles assigned_seller on assigned_seller.id = o.seller_id
            left join seller.seller_profiles customer_seller on customer_seller.id = c.seller_id
            where o.id = ?
            """, orderId);
        return detail(order, orderId);
    }

    public Map<String, Object> saleDetail(UUID saleId) {
        UUID orderId = jdbc.queryForObject("select order_id from sale.sales where id = ?", UUID.class, saleId);
        if (sellerScoped()) currentUser.requireOrderAccess(orderId);
        Map<String, Object> detail = new HashMap<>(orderDetail(orderId));
        detail.put("saleItems", jdbc.queryForList("""
            select si.id as "saleItemId", si.product_id as "productId", si.product_name as "productName",
                   si.quantity, coalesce(sum(ri.quantity), 0) as "returnedQuantity",
                   greatest(si.quantity - coalesce(sum(ri.quantity), 0), 0) as "returnableQuantity"
            from sale.sale_items si
            left join sale.return_items ri on ri.sale_item_id = si.id and ri.sale_id = si.sale_id
            where si.sale_id = ?
            group by si.id, si.product_id, si.product_name, si.quantity
            order by si.id
            """, saleId));
        return detail;
    }

    public Map<String, Object> orderDetailByNumber(String orderNumber) {
        Map<String, Object> order = jdbc.queryForMap("""
            select o.id, o.order_number as number, o.customer_id as "customerId",
                   c.business_name as customer,
                   coalesce(assigned_seller.display_name, customer_seller.display_name, 'Sin asignar') as seller,
                   o.status, o.subtotal, o.discount, o.total,
                   o.order_discount_percent as "orderDiscountPercent",
                   o.order_discount_rule_id as "orderDiscountRuleId",
                   o.credit_limit_exceeded as "creditLimitExceeded", o.credit_limit_snapshot as "creditLimitSnapshot",
                   o.projected_balance_snapshot as "projectedBalanceSnapshot",
                   c.balance as "customerBalance", o.previous_balance_amount as "previousBalanceAmount",
                   (o.total + o.previous_balance_amount) as "collectionTotal", o.created_at as date
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles assigned_seller on assigned_seller.id = o.seller_id
            left join seller.seller_profiles customer_seller on customer_seller.id = c.seller_id
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
            "deliveryAttempts", jdbc.queryForList("""
                select da.id, da.attempt_number as "attemptNumber", da.result, da.observation,
                       da.attempted_by as "attemptedBy", da.attempted_at as "attemptedAt"
                from orders.delivery_attempts da where da.order_id = ? order by da.attempt_number
                """, orderId),
            "account", account
        );
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search) {
        return sales(page, size, search, null, null, false);
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search,
            UUID customerId, UUID sellerId, boolean pendingBalance) {
        return sales(page, size, search, customerId, sellerId, pendingBalance, null, null);
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search,
            UUID customerId, UUID sellerId, boolean pendingBalance, LocalDate dateMin, LocalDate dateMax) {
        if (dateMin != null && dateMax != null && dateMin.isAfter(dateMax)) {
            throw new IllegalArgumentException("La fecha mínima no puede ser posterior a la fecha máxima");
        }
        String from = """
            from sale.sales s
            join orders.orders o on o.id = s.order_id
            join customer.customers c on c.id = s.customer_id
            where lower(s.sale_number) like ?
            """;
        List<Object> parameters = new ArrayList<>(List.of(like(search)));
        if (customerId != null) {
            from += " and s.customer_id = ?";
            parameters.add(customerId);
        }
        if (sellerId != null) {
            from += " and coalesce(o.seller_id, c.seller_id) = ?";
            parameters.add(sellerId);
        }
        if (pendingBalance) {
            from += " and s.total > s.paid and s.status <> 'CANCELLED'";
        }
        if (sellerScoped()) {
            UUID scopedSellerId = currentUser.requireSellerProfile();
            from += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(scopedSellerId);
            parameters.add(scopedSellerId);
        }
        ZoneId zone = ZoneId.of("America/Argentina/Buenos_Aires");
        if (dateMin != null) {
            from += " and s.created_at >= ?";
            parameters.add(Timestamp.from(dateMin.atStartOfDay(zone).toInstant()));
        }
        if (dateMax != null) {
            from += " and s.created_at < ?";
            parameters.add(Timestamp.from(dateMax.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        return page("""
            select s.id, s.sale_number as number, c.business_name as customer,
                   s.total, s.paid, (s.total - s.paid) as balance,
                   s.status, s.created_at as date
            """ + from + " order by s.created_at desc, s.id desc", "select count(*) " + from,
            page, size, parameters.toArray());
    }

    public Map<String, Object> saleFilterOptions() {
        String from = """
            from sale.sales s
            join orders.orders o on o.id = s.order_id
            join customer.customers c on c.id = s.customer_id
            left join seller.seller_profiles sp on sp.id = coalesce(o.seller_id, c.seller_id)
            where 1 = 1
            """;
        List<Object> parameters = new ArrayList<>();
        if (sellerScoped()) {
            UUID sellerId = currentUser.requireSellerProfile();
            from += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        return Map.of(
            "customers", jdbc.queryForList("select distinct c.id, c.business_name as name " + from
                + " order by name, c.id", parameters.toArray()),
            "sellers", jdbc.queryForList("select distinct sp.id, sp.display_name as name " + from
                + " and sp.id is not null order by name, sp.id", parameters.toArray())
        );
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

    public PageResponse<Map<String, Object>> auditEvents(int page, int size, String search) {
        String term = like(search);
        return page("""
                select ae.id, ae.actor_user_id as "actorUserId", u.email as actor,
                       ae.operation, ae.resource_type as "resourceType", ae.resource_id as "resourceId",
                       ae.result, ae.correlation_id as "correlationId", ae.details::text as details,
                       ae.created_at as "createdAt"
                from audit.audit_events ae left join identity.users u on u.id = ae.actor_user_id
                where lower(ae.operation) like ? or lower(ae.resource_type) like ?
                   or lower(coalesce(ae.resource_id, '')) like ? or lower(ae.result) like ?
                order by ae.created_at desc, ae.id desc
                """, """
                select count(*) from audit.audit_events ae
                where lower(ae.operation) like ? or lower(ae.resource_type) like ?
                   or lower(coalesce(ae.resource_id, '')) like ? or lower(ae.result) like ?
                """, page, size, term, term, term, term);
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
