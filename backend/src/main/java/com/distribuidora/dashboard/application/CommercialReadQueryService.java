package com.distribuidora.dashboard.application;

import com.distribuidora.payment.application.DeliveryDebtQuery;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.time.BusinessTime;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CommercialReadQueryService {
    private final ReadQuerySupport support;

    @Autowired
    public CommercialReadQueryService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.support = new ReadQuerySupport(jdbc, currentUser);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status) {
        return orders(page, size, search, status, null, null);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status,
            LocalDate dateMin, LocalDate dateMax) {
        return orders(page, size, search, status, dateMin, dateMax, null, false);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status,
            LocalDate dateMin, LocalDate dateMax, String createdBy, boolean paidUndelivered) {
        return orders(page, size, search, status, dateMin, dateMax, createdBy, paidUndelivered, null, false);
    }

    public PageResponse<Map<String, Object>> orders(int page, int size, String search, String status,
            LocalDate dateMin, LocalDate dateMax, String createdBy, boolean paidUndelivered,
            String fulfillmentStatus, boolean reviewPending) {
        if (fulfillmentStatus != null && !fulfillmentStatus.isBlank()
            && !List.of("PENDING", "PREPARED").contains(fulfillmentStatus)) {
            throw new IllegalArgumentException("fulfillmentStatus debe ser PENDING o PREPARED");
        }
        if (createdBy != null && !createdBy.isBlank() && !"ADMIN".equals(createdBy) && !"SELLER".equals(createdBy)) {
            throw new IllegalArgumentException("createdBy debe ser ADMIN o SELLER");
        }
        if (dateMin != null && dateMax != null && dateMin.isAfter(dateMax)) {
            throw new IllegalArgumentException("La fecha mínima no puede ser posterior a la fecha máxima");
        }
        String term = ReadQuerySupport.escapedLike(search);
        String state = status == null ? "" : status.trim();
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (term != null) {
            where.append(" and (lower(c.business_name) like ? escape '!' or lower(o.order_number) like ? escape '!')");
            parameters.add(term);
            parameters.add(term);
        }
        if (!state.isEmpty()) {
            where.append(" and o.status = ?");
            parameters.add(state);
        }
        if (support.sellerScoped()) {
            UUID sellerId = support.currentUser().requireSellerProfile();
            where.append(" and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))");
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        ZoneId zone = BusinessTime.ZONE;
        if (dateMin != null) {
            where.append(" and o.created_at >= ?");
            parameters.add(Timestamp.from(dateMin.atStartOfDay(zone).toInstant()));
        }
        if (dateMax != null) {
            where.append(" and o.created_at < ?");
            parameters.add(Timestamp.from(dateMax.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        if ("ADMIN".equals(createdBy)) where.append(" and o.created_by_admin = true");
        if ("SELLER".equals(createdBy)) where.append(" and o.created_by_admin = false");
        if (paidUndelivered) where.append(" and o.status = 'CONFIRMED' and s.id is not null and s.paid >= s.total");
        if (fulfillmentStatus != null && !fulfillmentStatus.isBlank()) {
            where.append(" and o.status = 'CONFIRMED' and o.fulfillment_status = ?");
            parameters.add(fulfillmentStatus);
        }
        if (reviewPending) where.append(" and o.created_by_admin = false and o.reviewed_at is null");
        return support.page("""
            select o.id, o.order_number as number, c.business_name as customer,
                   coalesce(sp.display_name, 'Sin asignar') as seller,
                   o.total, o.status, o.created_at as date,
                   o.created_by_admin as "createdByAdmin", coalesce(s.paid, 0) as paid,
                   o.fulfillment_status as "fulfillmentStatus",
                   (o.created_by_admin = false and o.reviewed_at is null) as "reviewPending"
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            left join sale.sales s on s.order_id = o.id
            """ + where + " order by o.created_at desc, o.id desc", """
            select count(*) from orders.orders o join customer.customers c on c.id = o.customer_id
            left join sale.sales s on s.order_id = o.id
             """ + where, page, size, parameters.toArray());
    }

    public Map<String, Object> lastCustomerOrder(UUID customerId) {
        support.currentUser().requireCustomerAccess(customerId);
        String where = " where o.customer_id = ? and o.status in ('CONFIRMED', 'DELIVERED')";
        List<Object> parameters = new ArrayList<>(List.of(customerId));
        if (support.sellerScoped()) {
            UUID sellerId = support.currentUser().requireSellerProfile();
            where += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        List<Map<String, Object>> orders = support.jdbc().queryForList("""
            select o.id, o.order_number as "number", o.order_discount_percent as "orderDiscountPercent"
            from orders.orders o join customer.customers c on c.id = o.customer_id
            """ + where + " order by o.created_at desc, o.id desc limit 1", parameters.toArray());
        if (orders.isEmpty()) return Map.of("available", false);
        Map<String, Object> order = orders.getFirst();
        List<Map<String, Object>> items = support.jdbc().queryForList("""
            select oi.product_id as "productId", coalesce(p.name, oi.product_name) as "productName",
                   p.presentation, p.status,
                   oi.quantity, oi.line_discount_percent as "lineDiscountPercent"
            from orders.order_items oi
            left join catalog.products p on p.id = oi.product_id
            where oi.order_id = ? order by oi.id
            """, order.get("ID"));
        return Map.of("available", !items.isEmpty(), "orderId", order.get("ID"),
            "orderNumber", order.get("number"), "orderDiscountPercent", order.get("orderDiscountPercent"), "items", items);
    }

    public Map<String, Object> orderDetail(UUID orderId) {
        if (support.sellerScoped()) support.currentUser().requireOrderAccess(orderId);
        Map<String, Object> order = support.jdbc().queryForMap(ReadQuerySupport.ORDER_DETAIL_SELECT + "where o.id = ?", orderId);
        return detail(order, orderId);
    }

    public Map<String, Object> saleDetail(UUID saleId) {
        UUID orderId = support.jdbc().queryForObject("select order_id from sale.sales where id = ?", UUID.class, saleId);
        Map<String, Object> detail = new HashMap<>(orderDetail(orderId));
        detail.put("saleItems", support.jdbc().queryForList("""
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
        Map<String, Object> order = support.jdbc().queryForMap(ReadQuerySupport.ORDER_DETAIL_SELECT + """
            where o.order_number = ? or o.legacy_order_number = ?
            order by case when o.order_number = ? then 0 else 1 end limit 1
            """, orderNumber, orderNumber, orderNumber);
        if (support.sellerScoped()) support.currentUser().requireOrderAccess((UUID) order.get("id"));
        return detail(order, (UUID) order.get("id"));
    }

    private Map<String, Object> detail(Map<String, Object> order, UUID orderId) {
        Map<String, Object> sale = support.jdbc().queryForMap("""
            select s.id, s.sale_number as number, s.status, s.total, s.paid,
                   s.order_discount_percent as "orderDiscountPercent",
                   (s.total - s.paid) as balance, s.created_at as date
            from sale.sales s where s.order_id = ?
            """, orderId);
        UUID saleId = (UUID) sale.get("id");
        sale = new HashMap<>(sale);
        sale.put("previousDebtAvailable", new DeliveryDebtQuery(support.jdbc(), support.currentUser()).previousBalance(saleId));
        Map<String, Object> account = support.jdbc().queryForMap("""
            select coalesce(sum(amount) filter (where entry_type = 'DEBIT'), 0) as debit,
                   coalesce(sum(amount) filter (where entry_type = 'CREDIT'), 0) as credit,
                   coalesce(sum(amount) filter (where entry_type = 'DEBIT'), 0)
                     - coalesce(sum(amount) filter (where entry_type = 'CREDIT'), 0) as net
            from customer.account_ledger where sale_id = ?
            """, saleId);
        return Map.of(
            "order", order,
            "items", support.jdbc().queryForList("""
                select oi.product_id as "productId", oi.product_name as "productName",
                       oi.quantity, oi.unit_price as "unitPrice", oi.line_total as "lineTotal",
                       oi.price_list_id as "priceListId", oi.price_list_code as "priceListCode",
                       oi.line_discount_percent as "lineDiscountPercent"
                from orders.order_items oi where oi.order_id = ? order by oi.id
                """, orderId),
            "sale", sale,
            "payments", support.jdbc().queryForList("""
                select p.id, p.amount, p.method, p.transfer_reference as "transferReference", p.created_at as date
                from payment.payments p where p.sale_id = ? order by p.created_at, p.id
                """, saleId),
            "deliveryAttempts", support.jdbc().queryForList("""
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
        return sales(page, size, search, customerId, null, sellerId, pendingBalance, dateMin, dateMax);
    }

    public PageResponse<Map<String, Object>> sales(int page, int size, String search, UUID customerId,
            UUID accountCustomerId, UUID sellerId, boolean pendingBalance, LocalDate dateMin, LocalDate dateMax) {
        if (dateMin != null && dateMax != null && dateMin.isAfter(dateMax)) {
            throw new IllegalArgumentException("La fecha mínima no puede ser posterior a la fecha máxima");
        }
        String term = ReadQuerySupport.escapedLike(search);
        StringBuilder from = new StringBuilder("""
            from sale.sales s
            join orders.orders o on o.id = s.order_id
            join customer.customers c on c.id = s.customer_id
            join customer.customers account_customer on account_customer.id = s.account_customer_id
            left join seller.seller_profiles sp on sp.id = coalesce(o.seller_id, c.seller_id)
            where 1 = 1
            """);
        List<Object> parameters = new ArrayList<>();
        if (term != null) {
            from.append(" and (lower(s.sale_number) like ? escape '!' or lower(s.legacy_sale_number) like ? escape '!')");
            parameters.add(term);
            parameters.add(term);
        }
        if (customerId != null) {
            from.append(" and s.customer_id = ?");
            parameters.add(customerId);
        }
        if (accountCustomerId != null) {
            from.append(" and s.account_customer_id = ?");
            parameters.add(accountCustomerId);
        }
        if (sellerId != null) {
            from.append(" and coalesce(o.seller_id, c.seller_id) = ?");
            parameters.add(sellerId);
        }
        if (pendingBalance) {
            from.append(" and s.total > s.paid and s.status <> 'CANCELLED'");
        }
        if (support.sellerScoped()) {
            UUID scopedSellerId = support.currentUser().requireSellerProfile();
            from.append(" and s.status = 'DELIVERED' and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))");
            parameters.add(scopedSellerId);
            parameters.add(scopedSellerId);
        }
        ZoneId zone = BusinessTime.ZONE;
        if (dateMin != null) {
            from.append(" and s.created_at >= ?");
            parameters.add(Timestamp.from(dateMin.atStartOfDay(zone).toInstant()));
        }
        if (dateMax != null) {
            from.append(" and s.created_at < ?");
            parameters.add(Timestamp.from(dateMax.plusDays(1).atStartOfDay(zone).toInstant()));
        }
        return support.page("""
            select s.id, s.sale_number as number, c.business_name as customer, s.customer_id as "customerId",
                   s.account_customer_id as "accountCustomerId", account_customer.business_name as account,
                   coalesce(sp.display_name, 'Sin asignar') as seller,
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
        if (support.sellerScoped()) {
            UUID sellerId = support.currentUser().requireSellerProfile();
            from += " and s.status = 'DELIVERED' and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        return Map.of(
            "customers", support.jdbc().queryForList("select distinct c.id, c.business_name as name " + from
                + " order by name, c.id limit 500", parameters.toArray()),
            "sellers", support.jdbc().queryForList("select distinct sp.id, sp.display_name as name " + from
                + " and sp.id is not null order by name, sp.id limit 500", parameters.toArray())
        );
    }

    public PageResponse<Map<String, Object>> payments(int page, int size, String search) {
        String term = ReadQuerySupport.escapedLike(search);
        if (support.sellerScoped()) {
            UUID sellerId = support.currentUser().requireSellerProfile();
            List<Object> params = new ArrayList<>(List.of(sellerId, sellerId));
            String searchClause = "";
            if (term != null) {
                searchClause = " and (lower(c.business_name) like ? escape '!' or lower(s.sale_number) like ? escape '!')";
                params.add(term);
                params.add(term);
            }
            return support.page("""
                select p.id, c.business_name as customer, s.sale_number as sale, p.amount, p.method,
                       p.transfer_reference as "transferReference", p.created_at as date
                from payment.payments p join customer.customers c on c.id = p.customer_id
                join sale.sales s on s.id = p.sale_id join orders.orders o on o.id = s.order_id
                where (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))""" + searchClause + """
                 order by p.created_at desc
                """, """
                select count(*) from payment.payments p join customer.customers c on c.id = p.customer_id
                join sale.sales s on s.id = p.sale_id join orders.orders o on o.id = s.order_id
                where (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))""" + searchClause,
                page, size, params.toArray());
        }
        List<Object> params = new ArrayList<>();
        String where = "";
        if (term != null) {
            where = " where lower(c.business_name) like ? escape '!' or lower(s.sale_number) like ? escape '!'";
            params.add(term);
            params.add(term);
        }
        return support.page("""
            select p.id, c.business_name as customer, s.sale_number as sale,
                   p.amount, p.method, p.transfer_reference as "transferReference", p.created_at as date
            from payment.payments p
            join customer.customers c on c.id = p.customer_id
            join sale.sales s on s.id = p.sale_id""" + where + """
             order by p.created_at desc
            """, "select count(*) from payment.payments p join customer.customers c on c.id = p.customer_id join sale.sales s on s.id = p.sale_id" + where,
            page, size, params.toArray());
    }
}
