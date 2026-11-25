package com.distribuidora.dashboard.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.time.BusinessTime;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DashboardReadQueryService {
    private final ReadQuerySupport support;

    @Autowired
    public DashboardReadQueryService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.support = new ReadQuerySupport(jdbc, currentUser);
    }

    public Map<String, Object> dashboard(LocalDate dateMin, LocalDate dateMax) {
        ZoneId zone = BusinessTime.ZONE;
        LocalDate today = LocalDate.now(zone);
        LocalDate from = dateMin == null ? today : dateMin;
        LocalDate to = dateMax == null ? today : dateMax;
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha desde no puede ser posterior a la fecha hasta");
        }
        ReadQuerySupport.validateDateRange(from, to);
        List<Map<String, Object>> bySeller = support.jdbc().queryForList("""
            with period_orders as (
                select o.seller_id, o.status as order_status, s.id as sale_id, s.status as sale_status,
                       s.total, s.paid
                from orders.orders o
                left join sale.sales s on s.order_id = o.id
                where o.created_at >= ? and o.created_at < ?
            ),
            period_payments as (
                select p.sale_id,
                       sum(case when p.method = 'CASH' then p.amount else 0 end) as cash_paid,
                       sum(case when p.method = 'BANK_TRANSFER' then p.amount else 0 end) as transfer_paid
                from payment.payments p
                where p.sale_id in (select sale_id from period_orders where sale_id is not null)
                group by p.sale_id
            ),
            period_ledger as (
                select l.sale_id,
                       sum(case when l.entry_type = 'DEBIT' then l.amount else -l.amount end) as account_debt
                from customer.account_ledger l
                where l.sale_id in (select sale_id from period_orders where sale_id is not null)
                group by l.sale_id
            )
            select sp.id as "sellerId", coalesce(sp.display_name, 'Sin asignar') as seller,
                   count(*) as "performedOrders",
                   sum(case when p.order_status = 'DELIVERED' then 1 else 0 end) as "deliveredOrders",
                   sum(case when p.order_status <> 'CANCELLED' and p.sale_status <> 'CANCELLED'
                       then coalesce(p.total, 0) else 0 end) as "totalBilled",
                   sum(case when p.order_status <> 'CANCELLED' and p.sale_status <> 'CANCELLED'
                       then coalesce(p.paid, 0) else 0 end) as "totalPaid",
                   sum(case when p.order_status <> 'CANCELLED' and p.sale_status <> 'CANCELLED'
                       then coalesce(pay.cash_paid, 0) else 0 end) as "cashPaid",
                   sum(case when p.order_status <> 'CANCELLED' and p.sale_status <> 'CANCELLED'
                       then coalesce(pay.transfer_paid, 0) else 0 end) as "transferPaid",
                   sum(case when p.order_status <> 'CANCELLED' and p.sale_status <> 'CANCELLED'
                       then least(greatest(coalesce(led.account_debt, 0), 0), greatest(p.total - p.paid, 0))
                       else 0 end) as "accountBalance"
            from period_orders p
            left join period_payments pay on pay.sale_id = p.sale_id
            left join period_ledger led on led.sale_id = p.sale_id
            left join seller.seller_profiles sp on sp.id = p.seller_id
            group by sp.id, sp.display_name
            order by seller, sp.id
            """, Timestamp.from(from.atStartOfDay(zone).toInstant()),
                Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
        Map<String, Object> totals = new HashMap<>();
        for (String key : List.of("performedOrders", "deliveredOrders", "totalBilled", "totalPaid", "cashPaid", "transferPaid", "accountBalance")) {
            totals.put(key, bySeller.stream().map(row -> new BigDecimal(row.get(key).toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return Map.of("dateMin", from, "dateMax", to, "totals", totals, "bySeller", bySeller);
    }

    public PageResponse<Map<String, Object>> dashboardSellerOrders(UUID sellerId, boolean unassigned,
            LocalDate dateMin, LocalDate dateMax, int page, int size) {
        if ((sellerId == null) != unassigned) {
            throw new IllegalArgumentException("Seleccione un vendedor o los pedidos sin asignar");
        }
        ZoneId zone = BusinessTime.ZONE;
        LocalDate today = LocalDate.now(zone);
        LocalDate from = dateMin == null ? today : dateMin;
        LocalDate to = dateMax == null ? today : dateMax;
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("La fecha desde no puede ser posterior a la fecha hasta");
        }
        ReadQuerySupport.validateDateRange(from, to);
        String source = """
            from orders.orders o
            join sale.sales s on s.order_id = o.id
            join customer.customers c on c.id = o.customer_id
            where o.status = 'DELIVERED' and s.status <> 'CANCELLED'
              and o.created_at >= ? and o.created_at < ?
            """ + (unassigned ? " and o.seller_id is null" : " and o.seller_id = ?");
        List<Object> parameters = new ArrayList<>(List.of(
            Timestamp.from(from.atStartOfDay(zone).toInstant()), Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant())));
        if (!unassigned) parameters.add(sellerId);
        var result = support.page("""
            select o.id, o.order_number as number, c.business_name as customer,
                   o.created_at as date, o.delivered_at as "deliveredAt", s.id as "saleId", s.total, s.paid,
                   least(greatest((select coalesce(sum(case when l.entry_type = 'DEBIT' then l.amount else -l.amount end), 0)
                       from customer.account_ledger l where l.sale_id = s.id), 0), greatest(s.total - s.paid, 0)) as "accountBalance"
            """ + source + " order by o.created_at desc, o.id", "select count(*) " + source,
            page, size, parameters.toArray());
        if (result.content().isEmpty()) return result;
        Object[] saleIds = result.content().stream().map(row -> row.get("saleId")).toArray();
        String placeholders = String.join(",", Collections.nCopies(saleIds.length, "?"));
        var payments = support.jdbc().queryForList("""
            select sale_id as "saleId", method, sum(amount) as amount
            from payment.payments where sale_id in (
            """ + placeholders + ") group by sale_id, method order by method", saleIds);
        for (var row : result.content()) {
            row.put("payments", payments.stream().filter(payment -> row.get("saleId").equals(payment.get("saleId")))
                .map(payment -> Map.of("method", payment.get("method"), "amount", payment.get("amount"))).toList());
        }
        return result;
    }
}
