package com.distribuidora.dashboard.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DashboardAnalyticsService {
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public DashboardAnalyticsService(JdbcTemplate jdbc) {
        this(jdbc, Clock.system(ZONE));
    }

    public DashboardAnalyticsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> report(LocalDate dateMin, LocalDate dateMax) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(ZONE).toLocalDate();
        LocalDate start = dateMin == null ? today.withDayOfMonth(1) : dateMin;
        LocalDate end = dateMax == null ? today : dateMax;
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1 || days > 366 || end.isAfter(today) || start.getYear() < 1900) {
            throw new IllegalArgumentException("Elegí un período válido de hasta 366 días, sin fechas futuras.");
        }
        Instant fromInstant = start.atStartOfDay(ZONE).toInstant();
        Instant untilInstant = end.equals(today) ? now : end.plusDays(1).atStartOfDay(ZONE).toInstant();
        LocalDate previousStart = start.minusDays(days);
        Instant previousFrom = previousStart.atStartOfDay(ZONE).toInstant();
        Instant previousUntil = previousFrom.plus(Duration.between(fromInstant, untilInstant));
        Timestamp from = Timestamp.from(fromInstant);
        Timestamp until = Timestamp.from(untilInstant);
        Timestamp previousFromSql = Timestamp.from(previousFrom);
        Timestamp previousUntilSql = Timestamp.from(previousUntil);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dateMin", start);
        result.put("dateMax", end);
        result.put("previousDateMin", previousStart);
        result.put("previousDateMax", start.minusDays(1));
        result.put("generatedAt", now);
        result.put("sales", sales(from, until));
        result.put("previousSales", sales(previousFromSql, previousUntilSql));
        result.put("collections", collections(from, until));
        result.put("previousCollections", collections(previousFromSql, previousUntilSql));
        result.put("current", jdbc.queryForMap("""
            select
                (select coalesce(sum(balance), 0) from customer.customers where balance > 0) as debt,
                (select count(*) from customer.customers where balance > 0) as "debtorCount",
                count(*) as "pendingOrders", coalesce(sum(o.total), 0) as "pendingAmount",
                min(o.created_at) as "oldestPendingAt",
                count(*) filter (where (select da.result from orders.delivery_attempts da
                    where da.order_id = o.id order by da.attempt_number desc limit 1) = 'FAILED') as "failedDeliveries",
                (select count(*) from catalog.products p left join inventory.inventory_balances ib on ib.product_id = p.id
                    where p.status = 'ACTIVE' and coalesce(ib.quantity, 0) <= 0) as "stockAlertCount"
            from orders.orders o where o.status = 'CONFIRMED'
            """));
        result.put("trend", jdbc.queryForList("""
            with daily as (
                select (s.created_at at time zone 'America/Argentina/Buenos_Aires')::date as day,
                       sum(s.total) as amount, count(*) as orders
                from sale.sales s where s.status in ('CONFIRMED', 'DELIVERED')
                    and s.created_at >= ? and s.created_at < ? group by day
            )
            select to_char(d.day, 'YYYY-MM-DD') as date, coalesce(daily.amount, 0) as amount,
                   coalesce(daily.orders, 0) as orders
            from generate_series(cast(? as date), cast(? as date), interval '1 day') d(day)
            left join daily on daily.day = d.day::date order by d.day
            """, from, until, start, end));
        result.put("topDebtors", jdbc.queryForList("""
            select id, business_name as name, balance
            from customer.customers where balance > 0 order by balance desc, id limit 5
            """));
        result.put("debtAging", jdbc.queryForMap("""
            with debts as (
                select greatest(least(s.total - s.paid, coalesce(l.balance, 0)), 0) as balance,
                    cast(? as date) - (s.created_at at time zone 'America/Argentina/Buenos_Aires')::date as age
                from sale.sales s left join lateral (
                    select sum(case when entry_type = 'DEBIT' then amount else -amount end) as balance
                    from customer.account_ledger where sale_id = s.id and customer_id = s.customer_id
                ) l on true where s.status in ('CONFIRMED', 'DELIVERED')
            )
            select coalesce(sum(balance) filter (where age <= 30), 0) as "days0to30",
                coalesce(sum(balance) filter (where age between 31 and 60), 0) as "days31to60",
                coalesce(sum(balance) filter (where age between 61 and 90), 0) as "days61to90",
                coalesce(sum(balance) filter (where age > 90), 0) as "daysOver90"
            from debts
            """, today));
        result.put("stockAlerts", jdbc.queryForList("""
            select p.id, p.name, coalesce(ib.quantity, 0) as stock
            from catalog.products p left join inventory.inventory_balances ib on ib.product_id = p.id
            where p.status = 'ACTIVE' and coalesce(ib.quantity, 0) <= 0
            order by stock, p.name, p.id limit 8
            """));
        result.put("stockCoverage", jdbc.queryForList("""
            with sold as (
                select si.product_id, sum(greatest(si.quantity - coalesce(r.quantity, 0), 0)) as quantity
                from sale.sale_items si join sale.sales s on s.id = si.sale_id
                left join lateral (select sum(ri.quantity) as quantity from sale.return_items ri
                    where ri.sale_item_id = si.id) r on true
                where s.status in ('CONFIRMED', 'DELIVERED') and s.created_at >= ? and s.created_at < ?
                group by si.product_id
            )
            select p.id, p.name, coalesce(ib.quantity, 0) as stock,
                coalesce(sold.quantity, 0) / 30 as "dailyUnits",
                case when sold.quantity > 0 then round(greatest(coalesce(ib.quantity, 0), 0) * 30 / sold.quantity, 1)
                    else null end as days
            from catalog.products p left join inventory.inventory_balances ib on ib.product_id = p.id
            left join sold on sold.product_id = p.id where p.status = 'ACTIVE'
            order by days asc nulls last, p.name, p.id limit 8
            """, Timestamp.from(today.minusDays(30).atStartOfDay(ZONE).toInstant()),
            Timestamp.from(today.atStartOfDay(ZONE).toInstant())));
        result.put("topProducts", jdbc.queryForList("""
            select si.product_id as id, max(si.product_name) as name, sum(si.quantity) as units,
                sum(si.line_total * (1 - s.order_discount_percent / 100)) as amount
            from sale.sale_items si join sale.sales s on s.id = si.sale_id
            where s.status in ('CONFIRMED', 'DELIVERED') and s.created_at >= ? and s.created_at < ?
            group by si.product_id order by units desc, si.product_id limit 5
            """, from, until));
        result.put("sellers", jdbc.queryForList("""
            select sp.id, coalesce(sp.display_name, 'Sin asignar') as name,
                count(*) as orders, sum(s.total) as amount
            from sale.sales s join orders.orders o on o.id = s.order_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            where s.status in ('CONFIRMED', 'DELIVERED') and s.created_at >= ? and s.created_at < ?
            group by sp.id, sp.display_name order by amount desc, sp.id nulls last limit 5
            """, from, until));
        result.put("pendingOrders", jdbc.queryForList("""
            select o.id, o.order_number as number, c.business_name as customer,
                coalesce(sp.display_name, 'Sin asignar') as seller, o.total, o.status, o.created_at as date
            from orders.orders o join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            where o.status = 'CONFIRMED' order by o.created_at, o.id limit 5
            """));
        result.put("recentOrders", jdbc.queryForList("""
            select o.id, o.order_number as number, c.business_name as customer,
                coalesce(sp.display_name, 'Sin asignar') as seller, o.total, o.status, o.created_at as date
            from orders.orders o join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            where o.created_at >= ? and o.created_at < ? order by o.created_at desc, o.id desc limit 5
            """, from, until));
        return result;
    }

    private Map<String, Object> sales(Timestamp from, Timestamp until) {
        return jdbc.queryForMap("""
            select coalesce(sum(total), 0) as amount, count(*) as orders
            from sale.sales where status in ('CONFIRMED', 'DELIVERED') and created_at >= ? and created_at < ?
            """, from, until);
    }

    private Map<String, Object> collections(Timestamp from, Timestamp until) {
        return jdbc.queryForMap("""
            select coalesce(sum(amount), 0) as amount,
                coalesce(sum(amount) filter (where method = 'CASH'), 0) as cash,
                coalesce(sum(amount) filter (where method = 'BANK_TRANSFER'), 0) as transfer
            from payment.payments where method in ('CASH', 'BANK_TRANSFER') and created_at >= ? and created_at < ?
            """, from, until);
    }
}
