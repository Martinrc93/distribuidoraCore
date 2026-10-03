package com.distribuidora.customer.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Outstanding earlier sales available to the current delivery operator. */
public final class DeliveryDebtQuery {
    public record Debt(UUID saleId, BigDecimal amount) { }

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    public DeliveryDebtQuery(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    private String source(List<Object> parameters, UUID saleId) {
        parameters.add(saleId);
        String sql = """
            from sale.sales s
            join sale.sales anchor on anchor.id = ? and anchor.customer_id = s.customer_id
            join orders.orders o on o.id = s.order_id
            join customer.customers c on c.id = s.customer_id
            where s.status in ('CONFIRMED', 'DELIVERED')
              and (s.created_at, s.id) <= (anchor.created_at, anchor.id)
            """;
        if (currentUser != null && !currentUser.isAdmin()) {
            UUID sellerId = currentUser.requireSellerProfile();
            sql += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        return sql;
    }

    // Match account payments' chronological sale-lock order before locking the customer.
    public void lockSales(UUID saleId) {
        List<Object> parameters = new ArrayList<>();
        String sql = source(parameters, saleId);
        jdbc.queryForList("select s.id " + sql + " order by s.created_at, s.id for update of s", parameters.toArray());
    }

    public List<Debt> previousSales(UUID saleId) {
        List<Object> parameters = new ArrayList<>();
        String sql = source(parameters, saleId);
        return jdbc.query("""
            select s.id, least(greatest(s.total - s.paid, 0), greatest(coalesce((
                select sum(case when entry_type = 'DEBIT' then amount else -amount end)
                from customer.account_ledger where sale_id = s.id
            ), 0), 0)) as due
            """ + sql + " and s.id <> anchor.id order by s.created_at, s.id",
            (row, index) -> new Debt(row.getObject("id", UUID.class), row.getBigDecimal("due").setScale(4)),
            parameters.toArray());
    }

    public BigDecimal previousBalance(UUID saleId) {
        return previousSales(saleId).stream().map(Debt::amount)
            .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
    }
}
