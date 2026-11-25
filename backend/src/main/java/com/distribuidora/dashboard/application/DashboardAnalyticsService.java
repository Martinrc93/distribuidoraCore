package com.distribuidora.dashboard.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.distribuidora.shared.time.BusinessTime;

@Service
public class DashboardAnalyticsService {
    private static final ZoneId ZONE = BusinessTime.ZONE;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DashboardAnalyticsService(JdbcTemplate jdbc) {
        this(jdbc, Clock.system(ZONE));
    }

    @Autowired
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

        Map<String, Object> summary = jdbc.queryForMap("""
            with sales_agg as (
                select coalesce(sum(total) filter (where created_at >= ? and created_at < ?), 0) as amount,
                       count(*) filter (where created_at >= ? and created_at < ?) as orders,
                       coalesce(sum(total) filter (where created_at >= ? and created_at < ?), 0) as prev_amount,
                       count(*) filter (where created_at >= ? and created_at < ?) as prev_orders
                from sale.sales
                where status in ('CONFIRMED', 'DELIVERED')
                  and created_at >= ? and created_at < ?
            ),
            collections_agg as (
                select coalesce(sum(amount) filter (where created_at >= ? and created_at < ?), 0) as amount,
                       coalesce(sum(amount) filter (where created_at >= ? and created_at < ? and method = 'CASH'), 0) as cash,
                       coalesce(sum(amount) filter (where created_at >= ? and created_at < ? and method = 'BANK_TRANSFER'), 0) as transfer,
                       coalesce(sum(amount) filter (where created_at >= ? and created_at < ?), 0) as prev_amount,
                       coalesce(sum(amount) filter (where created_at >= ? and created_at < ? and method = 'CASH'), 0) as prev_cash,
                       coalesce(sum(amount) filter (where created_at >= ? and created_at < ? and method = 'BANK_TRANSFER'), 0) as prev_transfer
                from payment.payments
                where method in ('CASH', 'BANK_TRANSFER')
                  and created_at >= ? and created_at < ?
            ),
            debts as (
                select greatest(least(s.total - s.paid, coalesce(l.balance, 0)), 0) as balance,
                       cast(? as date) - (s.created_at at time zone 'America/Argentina/Buenos_Aires')::date as age
                from sale.sales s left join lateral (
                    select sum(case when entry_type = 'DEBIT' then amount else -amount end) as balance
                    from customer.account_ledger where sale_id = s.id and customer_id = s.account_customer_id
                ) l on true where s.status in ('CONFIRMED', 'DELIVERED') and s.paid < s.total
            ),
            aging_agg as (
                select coalesce(sum(balance) filter (where age <= 30), 0) as days_0_30,
                       coalesce(sum(balance) filter (where age between 31 and 60), 0) as days_31_60,
                       coalesce(sum(balance) filter (where age between 61 and 90), 0) as days_61_90,
                       coalesce(sum(balance) filter (where age > 90), 0) as days_over_90
                from debts
            )
            select sales_agg.amount as sales_amount, sales_agg.orders as sales_orders,
                   sales_agg.prev_amount as prev_sales_amount, sales_agg.prev_orders as prev_sales_orders,
                   collections_agg.amount as col_amount, collections_agg.cash as col_cash, collections_agg.transfer as col_transfer,
                   collections_agg.prev_amount as prev_col_amount, collections_agg.prev_cash as prev_col_cash, collections_agg.prev_transfer as prev_col_transfer,
                   aging_agg.days_0_30, aging_agg.days_31_60, aging_agg.days_61_90, aging_agg.days_over_90
            from sales_agg cross join collections_agg cross join aging_agg
            """,
            from, until, from, until, previousFromSql, previousUntilSql, previousFromSql, previousUntilSql, previousFromSql, until,
            from, until, from, until, from, until, previousFromSql, previousUntilSql, previousFromSql, previousUntilSql, previousFromSql, previousUntilSql, previousFromSql, until,
            today);

        List<Map<String, Object>> expenseRows = jdbc.queryForList("""
            select id, amount, type, observation, created_at as "createdAt",
                   sum(amount) over () as total_amount, count(*) over () as total_count
            from expense.expenses where created_at >= ? and created_at < ?
            order by created_at desc, id desc limit 5
            """, from, until);
        Map<String, Object> expenses = new LinkedHashMap<>();
        expenses.put("amount", expenseRows.isEmpty() ? BigDecimal.ZERO : expenseRows.getFirst().get("total_amount"));
        expenses.put("count", expenseRows.isEmpty() ? 0L : expenseRows.getFirst().get("total_count"));
        expenses.put("recent", stripKeys(expenseRows, "total_amount", "total_count"));

        List<Map<String, Object>> debtorRows = jdbc.queryForList("""
            select id, business_name as name, balance,
                   sum(balance) over () as total_debt, count(*) over () as debtor_count
            from customer.customers where balance > 0 order by balance desc, id limit 5
            """);

        List<Map<String, Object>> stockAlertRows = jdbc.queryForList("""
            select p.id, p.name, coalesce(ib.quantity, 0) as stock,
                   count(*) over () as alert_count
            from catalog.products p left join inventory.inventory_balances ib on ib.product_id = p.id
            where p.status = 'ACTIVE' and coalesce(ib.quantity, 0) <= 0
            order by stock, p.name, p.id limit 8
            """);

        List<Map<String, Object>> pendingRows = jdbc.queryForList("""
            with confirmed_orders as (
                select o.id, o.order_number as number, c.business_name as customer,
                       coalesce(sp.display_name, 'Sin asignar') as seller, o.total, o.status, o.created_at as date,
                       (select da.result from orders.delivery_attempts da
                        where da.order_id = o.id order by da.attempt_number desc limit 1) as last_attempt
                from orders.orders o join customer.customers c on c.id = o.customer_id
                left join seller.seller_profiles sp on sp.id = o.seller_id
                where o.status = 'CONFIRMED'
            )
            select id, number, customer, seller, total, status, date,
                   count(*) over () as pending_orders_count,
                   coalesce(sum(total) over (), 0) as pending_amount,
                   min(date) over () as oldest_pending_at,
                   count(*) filter (where last_attempt = 'FAILED') over () as failed_deliveries
            from confirmed_orders
            order by date, id limit 5
            """);

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("debt", debtorRows.isEmpty() ? BigDecimal.ZERO : debtorRows.getFirst().get("total_debt"));
        current.put("debtorCount", debtorRows.isEmpty() ? 0L : debtorRows.getFirst().get("debtor_count"));
        current.put("pendingOrders", pendingRows.isEmpty() ? 0L : pendingRows.getFirst().get("pending_orders_count"));
        current.put("pendingAmount", pendingRows.isEmpty() ? BigDecimal.ZERO : pendingRows.getFirst().get("pending_amount"));
        current.put("oldestPendingAt", pendingRows.isEmpty() ? null : pendingRows.getFirst().get("oldest_pending_at"));
        current.put("failedDeliveries", pendingRows.isEmpty() ? 0L : pendingRows.getFirst().get("failed_deliveries"));
        current.put("stockAlertCount", stockAlertRows.isEmpty() ? 0L : stockAlertRows.getFirst().get("alert_count"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dateMin", start);
        result.put("dateMax", end);
        result.put("previousDateMin", previousStart);
        result.put("previousDateMax", start.minusDays(1));
        result.put("generatedAt", now);
        result.put("sales", Map.of(
            "amount", valueOrZero(summary.get("sales_amount")),
            "orders", valueOrZero(summary.get("sales_orders"))));
        result.put("previousSales", Map.of(
            "amount", valueOrZero(summary.get("prev_sales_amount")),
            "orders", valueOrZero(summary.get("prev_sales_orders"))));
        result.put("collections", Map.of(
            "amount", valueOrZero(summary.get("col_amount")),
            "cash", valueOrZero(summary.get("col_cash")),
            "transfer", valueOrZero(summary.get("col_transfer"))));
        result.put("previousCollections", Map.of(
            "amount", valueOrZero(summary.get("prev_col_amount")),
            "cash", valueOrZero(summary.get("prev_col_cash")),
            "transfer", valueOrZero(summary.get("prev_col_transfer"))));
        result.put("expenses", expenses);
        result.put("current", current);
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
        result.put("topDebtors", stripKeys(debtorRows, "total_debt", "debtor_count"));
        result.put("debtAging", Map.of(
            "days0to30", valueOrZero(summary.get("days_0_30")),
            "days31to60", valueOrZero(summary.get("days_31_60")),
            "days61to90", valueOrZero(summary.get("days_61_90")),
            "daysOver90", valueOrZero(summary.get("days_over_90"))));
        result.put("stockAlerts", stripKeys(stockAlertRows, "alert_count"));
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
            select si.product_id as id, coalesce(p.name, max(si.product_name)) as name,
                sum(greatest(si.quantity - coalesce(r.quantity, 0), 0)) as units,
                sum(greatest(si.quantity - coalesce(r.quantity, 0), 0) * si.unit_price
                    * (1 - si.line_discount_percent / 100) * (1 - s.order_discount_percent / 100)) as amount
            from sale.sale_items si
            join sale.sales s on s.id = si.sale_id
            left join catalog.products p on p.id = si.product_id
            left join lateral (select sum(ri.quantity) as quantity from sale.return_items ri where ri.sale_item_id = si.id) r on true
            where s.status in ('CONFIRMED', 'DELIVERED') and s.created_at >= ? and s.created_at < ?
            group by si.product_id, p.name
            having sum(greatest(si.quantity - coalesce(r.quantity, 0), 0)) > 0
            order by units desc, si.product_id limit 5
            """, from, until));
        result.put("sellers", jdbc.queryForList("""
            select sp.id, coalesce(sp.display_name, 'Sin asignar') as name,
                count(*) as orders, sum(s.total) as amount
            from sale.sales s join orders.orders o on o.id = s.order_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            where s.status in ('CONFIRMED', 'DELIVERED') and s.created_at >= ? and s.created_at < ?
            group by sp.id, sp.display_name order by amount desc, sp.id nulls last limit 5
            """, from, until));
        result.put("pendingOrders", stripKeys(pendingRows,
            "pending_orders_count", "pending_amount", "oldest_pending_at", "failed_deliveries"));
        result.put("recentOrders", jdbc.queryForList("""
            select o.id, o.order_number as number, c.business_name as customer,
                coalesce(sp.display_name, 'Sin asignar') as seller, o.total, o.status, o.created_at as date
            from orders.orders o join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            where o.created_at >= ? and o.created_at < ? order by o.created_at desc, o.id desc limit 5
            """, from, until));
        return result;
    }

    private static List<Map<String, Object>> stripKeys(List<Map<String, Object>> rows, String... excludedKeys) {
        List<Map<String, Object>> cleaned = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            for (String key : excludedKeys) copy.remove(key);
            cleaned.add(copy);
        }
        return cleaned;
    }

    private static Object valueOrZero(Object value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
