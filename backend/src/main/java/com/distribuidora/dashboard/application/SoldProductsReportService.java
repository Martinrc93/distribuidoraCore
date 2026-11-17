package com.distribuidora.dashboard.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SoldProductsReportService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    public record ProductTotals(UUID productId, String product, long salesCount, BigDecimal soldUnits,
                                BigDecimal returnedUnits, BigDecimal netUnits, BigDecimal amount) { }
    public record SaleEntry(UUID id, UUID orderId, String number, String customer, String status, Instant date,
                            BigDecimal soldUnits, BigDecimal returnedUnits, BigDecimal netUnits, BigDecimal amount) { }
    public record History(ProductTotals product, PageResponse<SaleEntry> sales) { }

    private record Filter(String eligibleSales, String productWhere, List<Object> args) {
        Object[] pageArgs(int page, int size) {
            List<Object> paging = new ArrayList<>(args);
            paging.add(size);
            paging.add((long) page * size);
            return paging.toArray();
        }
    }

    private static final String PRODUCT_TOTALS = """
        select product_id, product, count(distinct sale_id) as sales_count,
               sum(sold_units) as sold_units, sum(returned_units) as returned_units,
               sum(greatest(sold_units - returned_units, 0)) as net_units, sum(amount) as amount
        from report_lines group by product_id, product
        """;

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    public SoldProductsReportService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ProductTotals> products(int page, int size, LocalDate dateMin, LocalDate dateMax,
                                                String search, UUID productId) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Filter filter = filter(dateMin, dateMax, productId, search);
        Long count = jdbc.queryForObject("with eligible_sales as (" + filter.eligibleSales + ") " + """
            select count(distinct p.id)
            from eligible_sales s join sale.sale_items item on item.sale_id = s.id
            join catalog.products p on p.id = item.product_id
            """ + filter.productWhere, Long.class, filter.args.toArray());
        var rows = jdbc.query(lines(filter) + PRODUCT_TOTALS
            + " order by lower(product), product_id limit ? offset ?", (row, i) -> productTotals(row),
            filter.pageArgs(safePage, safeSize));
        return PageResponse.of(rows, safePage, safeSize, count);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History sales(UUID productId, int page, int size, LocalDate dateMin, LocalDate dateMax) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Filter filter = filter(dateMin, dateMax, productId, "");
        String lines = lines(filter);
        var totals = jdbc.query(lines + PRODUCT_TOTALS, (row, i) -> productTotals(row), filter.args.toArray());
        if (totals.isEmpty()) {
            return new History(null, PageResponse.of(List.of(), safePage, safeSize, 0));
        }
        ProductTotals product = totals.getFirst();
        var rows = jdbc.query(lines + """
            select sale_id, order_id, sale_number, customer, status, created_at,
                   sum(sold_units) as sold_units, sum(returned_units) as returned_units,
                   sum(greatest(sold_units - returned_units, 0)) as net_units, sum(amount) as amount
            from report_lines group by sale_id, order_id, sale_number, customer, status, created_at
            order by created_at desc, sale_id desc limit ? offset ?
            """, (row, i) -> new SaleEntry(row.getObject("sale_id", UUID.class), row.getObject("order_id", UUID.class),
            row.getString("sale_number"), row.getString("customer"), row.getString("status"),
            row.getTimestamp("created_at").toInstant(), row.getBigDecimal("sold_units"),
            row.getBigDecimal("returned_units"), row.getBigDecimal("net_units"), row.getBigDecimal("amount")),
            filter.pageArgs(safePage, safeSize));
        return new History(product, PageResponse.of(rows, safePage, safeSize, product.salesCount));
    }

    private Filter filter(LocalDate dateMin, LocalDate dateMax, UUID productId, String search) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = dateMin == null ? today.withDayOfMonth(1) : dateMin;
        LocalDate to = dateMax == null ? today : dateMax;
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.getYear() < 1900 || to.isAfter(today) || days < 1 || days > 366) {
            throw new IllegalArgumentException("Elegí un período válido de hasta 366 días, sin fechas futuras");
        }
        if (search.length() > 200) throw new IllegalArgumentException("La búsqueda admite hasta 200 caracteres");
        String eligible = """
            select s.id, s.order_id, s.sale_number, s.status, s.created_at, s.total, c.business_name as customer
            from sale.sales s join orders.orders o on o.id = s.order_id
            join customer.customers c on c.id = s.customer_id
            where s.status in ('CONFIRMED', 'DELIVERED') and s.created_at >= ? and s.created_at < ?
            """;
        List<Object> args = new ArrayList<>(List.of(
            Timestamp.from(from.atStartOfDay(BUSINESS_ZONE).toInstant()),
            Timestamp.from(to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant())));
        if (!currentUser.isAdmin()) {
            UUID seller = currentUser.requireSellerProfile();
            eligible += " and s.status = 'DELIVERED' and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            args.add(seller);
            args.add(seller);
        }
        String productWhere = " where true";
        if (productId != null) {
            eligible += " and exists (select 1 from sale.sale_items x where x.sale_id = s.id and x.product_id = ?)";
            args.add(productId);
            productWhere += " and p.id = ?";
            args.add(productId);
        }
        if (!search.isBlank()) {
            productWhere += " and position(lower(?) in lower(p.name)) > 0";
            args.add(search.strip());
        }
        return new Filter(eligible, productWhere, List.copyOf(args));
    }

    private String lines(Filter filter) {
        // Allocate the persisted sale total across all items before filtering products. Cumulative rounding
        // makes the four-decimal amounts sum to the sale total, including historical discounts and remainders.
        return "with eligible_sales as (" + filter.eligibleSales + "), weighted as (" + """
            select s.*, item.id as item_id, item.product_id, item.quantity, item.line_total,
                   sum(item.line_total) over (partition by s.id) as subtotal,
                   sum(item.line_total) over (partition by s.id order by item.id rows unbounded preceding) as cumulative
            from eligible_sales s join sale.sale_items item on item.sale_id = s.id
            ), report_lines as (
            select w.id as sale_id, w.order_id, w.sale_number, w.customer, w.status, w.created_at,
                   p.id as product_id, p.name as product, w.quantity as sold_units,
                   coalesce((select sum(r.quantity) from sale.return_items r where r.sale_item_id = w.item_id), 0) as returned_units,
                   case when w.subtotal > 0
                       then round(w.total * w.cumulative / w.subtotal, 4)
                          - round(w.total * (w.cumulative - w.line_total) / w.subtotal, 4)
                       else 0 end as amount
            from weighted w join catalog.products p on p.id = w.product_id
            """ + filter.productWhere + ") ";
    }

    private static ProductTotals productTotals(ResultSet row) throws SQLException {
        return new ProductTotals(row.getObject("product_id", UUID.class), row.getString("product"),
            row.getLong("sales_count"), row.getBigDecimal("sold_units"), row.getBigDecimal("returned_units"),
            row.getBigDecimal("net_units"), row.getBigDecimal("amount"));
    }
}
