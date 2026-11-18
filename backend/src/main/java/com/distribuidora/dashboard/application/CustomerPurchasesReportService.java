package com.distribuidora.dashboard.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@Service
public class CustomerPurchasesReportService {
    public record Summary(UUID customerId, String customer, LocalDate dateMin, LocalDate dateMax,
                          long salesCount, BigDecimal totalAmount) { }
    public record Item(UUID id, String product, BigDecimal soldUnits, BigDecimal returnedUnits,
                       BigDecimal netUnits, BigDecimal amount) { }
    public record Purchase(UUID id, UUID orderId, String saleNumber, String orderNumber, Instant date,
                           String status, BigDecimal amount, List<Item> items) { }
    public record Report(Summary summary, PageResponse<Purchase> purchases) { }
    public record Export(Summary summary, List<Purchase> purchases) { }

    private record Criteria(UUID customerId, String customer, CommercialReportSales.Period period,
                            CommercialReportSales.Selection sales) { }
    private record Page(int number, int size) { }

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    public CustomerPurchasesReportService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(UUID customerId, LocalDate dateMin, LocalDate dateMax, int page, int size) {
        Criteria criteria = criteria(customerId, dateMin, dateMax);
        Page paging = new Page(Math.max(0, page), Math.max(1, Math.min(100, size)));
        Summary summary = summarize(criteria);
        List<Purchase> purchases = load(criteria.sales(), paging);
        return new Report(summary, PageResponse.of(purchases, paging.number(), paging.size(), summary.salesCount()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Export export(UUID customerId, LocalDate dateMin, LocalDate dateMax) {
        Criteria criteria = criteria(customerId, dateMin, dateMax);
        return new Export(summarize(criteria), load(criteria.sales(), null));
    }

    private Criteria criteria(UUID customerId, LocalDate dateMin, LocalDate dateMax) {
        var period = CommercialReportSales.period(dateMin, dateMax);
        currentUser.requireCustomerAccess(customerId);
        String customer = jdbc.queryForObject("select business_name from customer.customers where id = ?", String.class, customerId);
        return new Criteria(customerId, customer, period, CommercialReportSales.select(period, currentUser, customerId));
    }

    private Summary summarize(Criteria criteria) {
        return jdbc.queryForObject(
            "select count(*), coalesce(sum(total), 0) from (" + criteria.sales().sql() + ") purchases",
            (row, index) -> new Summary(criteria.customerId(), criteria.customer(), criteria.period().from(),
                criteria.period().to(), row.getLong(1), row.getBigDecimal(2)), criteria.sales().args().toArray());
    }

    private List<Purchase> load(CommercialReportSales.Selection sales, Page page) {
        String eligible = sales.sql();
        List<Object> args = new ArrayList<>(sales.args());
        if (page != null) {
            eligible += " order by s.created_at desc, s.id desc limit ? offset ?";
            args.add(page.size());
            args.add((long) page.number() * page.size());
        }
        String query = CommercialReportSales.allocatedLines(eligible) + """
            select s.id, s.order_id, s.sale_number, s.order_number, s.created_at, s.status, s.total,
                   line.item_id, line.product_name, line.sold_units, line.returned_units, line.amount
            from eligible_sales s left join allocated_lines line on line.sale_id = s.id
            order by s.created_at desc, s.id desc, lower(line.product_name), line.item_id
            """;
        var purchases = new LinkedHashMap<UUID, Purchase>();
        jdbc.query(query, (RowCallbackHandler) row -> {
            UUID id = row.getObject("id", UUID.class);
            Purchase sale = purchases.get(id);
            if (sale == null) {
                sale = new Purchase(id, row.getObject("order_id", UUID.class), row.getString("sale_number"),
                    row.getString("order_number"), row.getTimestamp("created_at").toInstant(),
                    row.getString("status"), row.getBigDecimal("total"), new ArrayList<>());
                purchases.put(id, sale);
            }
            UUID itemId = row.getObject("item_id", UUID.class);
            if (itemId != null) {
                BigDecimal sold = row.getBigDecimal("sold_units");
                BigDecimal returned = row.getBigDecimal("returned_units");
                sale.items().add(new Item(itemId, row.getString("product_name"), sold, returned,
                    sold.subtract(returned).max(BigDecimal.ZERO), row.getBigDecimal("amount")));
            }
        }, args.toArray());
        return List.copyOf(purchases.values());
    }
}
