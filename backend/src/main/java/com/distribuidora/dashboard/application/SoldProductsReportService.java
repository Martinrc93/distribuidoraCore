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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SoldProductsReportService {
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
        long totalElements = count == null ? 0L : count;
        if (totalElements == 0 || (long) safePage * safeSize >= totalElements) {
            return PageResponse.of(List.of(), safePage, safeSize, totalElements);
        }
        var rows = jdbc.query(lines(filter) + PRODUCT_TOTALS
            + " order by lower(product), product_id limit ? offset ?", (row, i) -> productTotals(row),
            filter.pageArgs(safePage, safeSize));
        return PageResponse.of(rows, safePage, safeSize, totalElements);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History sales(UUID productId, int page, int size, LocalDate dateMin, LocalDate dateMax) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Filter filter = filter(dateMin, dateMax, productId, "");
        String sql = lines(filter) + """
            , product_totals as (
                """ + PRODUCT_TOTALS + """
            ), paged_sales as (
                select sale_id, order_id, sale_number, customer, status, created_at,
                       sum(sold_units) as sale_sold_units, sum(returned_units) as sale_returned_units,
                       sum(greatest(sold_units - returned_units, 0)) as sale_net_units, sum(amount) as sale_amount
                from report_lines group by sale_id, order_id, sale_number, customer, status, created_at
                order by created_at desc, sale_id desc limit ? offset ?
            )
            select pt.product_id, pt.product, pt.sales_count, pt.sold_units, pt.returned_units, pt.net_units, pt.amount,
                   ps.sale_id, ps.order_id, ps.sale_number, ps.customer, ps.status, ps.created_at,
                   ps.sale_sold_units, ps.sale_returned_units, ps.sale_net_units, ps.sale_amount
            from product_totals pt
            left join paged_sales ps on true
            order by ps.created_at desc nulls last, ps.sale_id desc nulls last
            """;
        ProductTotals[] productHolder = new ProductTotals[1];
        List<SaleEntry> rows = new ArrayList<>();
        jdbc.query(sql, row -> {
            if (productHolder[0] == null) {
                productHolder[0] = productTotals(row);
            }
            UUID saleId = row.getObject("sale_id", UUID.class);
            if (saleId != null) {
                rows.add(new SaleEntry(saleId, row.getObject("order_id", UUID.class),
                    row.getString("sale_number"), row.getString("customer"), row.getString("status"),
                    row.getTimestamp("created_at").toInstant(), row.getBigDecimal("sale_sold_units"),
                    row.getBigDecimal("sale_returned_units"), row.getBigDecimal("sale_net_units"),
                    row.getBigDecimal("sale_amount")));
            }
        }, filter.pageArgs(safePage, safeSize));
        if (productHolder[0] == null) {
            return new History(null, PageResponse.of(List.of(), safePage, safeSize, 0));
        }
        return new History(productHolder[0], PageResponse.of(rows, safePage, safeSize, productHolder[0].salesCount));
    }

    private Filter filter(LocalDate dateMin, LocalDate dateMax, UUID productId, String search) {
        var period = CommercialReportSales.period(dateMin, dateMax);
        if (search.length() > 200) throw new IllegalArgumentException("La búsqueda admite hasta 200 caracteres");
        var selection = CommercialReportSales.select(period, currentUser, null);
        String eligible = selection.sql();
        List<Object> args = new ArrayList<>(selection.args());
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
        return CommercialReportSales.allocatedLines(filter.eligibleSales) + """
            , report_lines as (
            select a.*, p.name as product from allocated_lines a join catalog.products p on p.id = a.product_id
            """ + filter.productWhere + ") ";
    }

    private static ProductTotals productTotals(ResultSet row) throws SQLException {
        return new ProductTotals(row.getObject("product_id", UUID.class), row.getString("product"),
            row.getLong("sales_count"), row.getBigDecimal("sold_units"), row.getBigDecimal("returned_units"),
            row.getBigDecimal("net_units"), row.getBigDecimal("amount"));
    }
}
