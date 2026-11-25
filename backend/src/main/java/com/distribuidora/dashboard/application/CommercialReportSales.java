package com.distribuidora.dashboard.application;

import com.distribuidora.shared.security.CurrentUserAccess;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.distribuidora.shared.time.BusinessTime;

/** Shared period, sale scope and line amounts for the commercial reports. */
final class CommercialReportSales {
    private static final ZoneId BUSINESS_ZONE = BusinessTime.ZONE;

    record Period(LocalDate from, LocalDate to) { }
    record Selection(String sql, List<Object> args) { }

    private CommercialReportSales() { }

    static Period period(LocalDate dateMin, LocalDate dateMax) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = dateMin == null ? today.withDayOfMonth(1) : dateMin;
        LocalDate to = dateMax == null ? today : dateMax;
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.getYear() < 1900 || to.isAfter(today) || days < 1 || days > 366) {
            throw new IllegalArgumentException("Elegí un período válido de hasta 366 días, sin fechas futuras");
        }
        return new Period(from, to);
    }

    static Selection select(Period period, CurrentUserAccess user, UUID customerId) {
        String sql = """
            select s.id, s.order_id, s.sale_number, o.order_number, s.status, s.created_at, s.total,
                   c.business_name as customer
            from sale.sales s join orders.orders o on o.id = s.order_id
            join customer.customers c on c.id = s.customer_id
            where s.status in ('CONFIRMED', 'DELIVERED') and s.created_at >= ? and s.created_at < ?
            """;
        List<Object> args = new ArrayList<>(List.of(
            Timestamp.from(period.from.atStartOfDay(BUSINESS_ZONE).toInstant()),
            Timestamp.from(period.to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant())));
        if (!user.isAdmin()) {
            UUID seller = user.requireSellerProfile();
            sql += " and s.status = 'DELIVERED' and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            args.add(seller);
            args.add(seller);
        }
        if (customerId != null) {
            sql += " and s.customer_id = ?";
            args.add(customerId);
        }
        return new Selection(sql, List.copyOf(args));
    }

    static String allocatedLines(String eligibleSales) {
        // Weight by persisted line subtotals and allocate before filtering products. Cumulative rounding
        // makes the four-decimal amounts sum to the sale total, including historical discounts.
        return "with eligible_sales as (" + eligibleSales + "), weighted as (" + """
            select s.*, item.id as item_id, item.product_id, item.product_name, item.quantity, item.line_total,
                   sum(item.line_total) over (partition by s.id) as subtotal,
                   sum(item.line_total) over (partition by s.id order by item.id rows unbounded preceding) as cumulative
            from eligible_sales s join sale.sale_items item on item.sale_id = s.id
            ), allocated_lines as (
            select w.id as sale_id, w.order_id, w.sale_number, w.customer, w.status, w.created_at,
                   w.item_id, w.product_id, w.product_name, w.quantity as sold_units,
                   coalesce((select sum(r.quantity) from sale.return_items r where r.sale_item_id = w.item_id), 0) as returned_units,
                   case when w.subtotal > 0
                       then round(w.total * w.cumulative / w.subtotal, 4)
                          - round(w.total * (w.cumulative - w.line_total) / w.subtotal, 4)
                       else 0 end as amount
            from weighted w
            )
            """;
    }
}
