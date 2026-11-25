package com.distribuidora.payment.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

/**
 * Outstanding earlier sales of the account that owes the delivered sale, available to the current
 * delivery operator. For assigned scopes, orders without seller use the recipient's assignment.
 */
public final class DeliveryDebtQuery {
    public record Debt(UUID saleId, BigDecimal amount) { }

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    public DeliveryDebtQuery(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    private String source(List<Object> parameters, UUID saleId) {
        parameters.add(saleId);
        String sql = """
            from orders.orders o
            join sale.sales s on s.order_id = o.id
            join sale.sales anchor on anchor.id = ? and anchor.account_customer_id = s.account_customer_id
            join customer.customers c on c.id = s.customer_id
            where s.status in ('CONFIRMED', 'DELIVERED')
              and (s.created_at, s.id) <= (anchor.created_at, anchor.id)
            """;
        if (!currentUser.isAdmin()) {
            UUID sellerId = currentUser.requireSellerProfile();
            sql += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        return sql;
    }

    // Match account payments' canonical lock order (orders -> sales) before locking the account.
    public void lockSales(UUID saleId) {
        List<Object> parameters = new ArrayList<>();
        String sql = source(parameters, saleId);
        jdbc.queryForList(
            "select s.id " + sql + " and (s.id = anchor.id or s.paid < s.total) order by o.id for update of o, s",
            parameters.toArray());
    }

    public List<Debt> previousSales(UUID saleId) {
        List<Object> parameters = new ArrayList<>();
        String sql = source(parameters, saleId);
        return jdbc.query("""
            select s.id, least(greatest(s.total - s.paid, 0), greatest(coalesce((
                select sum(case when entry_type = 'DEBIT' then amount else -amount end)
                from customer.account_ledger where sale_id = s.id
            ), 0), 0)) as due
            """ + sql + " and s.paid < s.total and s.id <> anchor.id order by s.created_at, s.id",
            (row, index) -> new Debt(row.getObject("id", UUID.class), row.getBigDecimal("due").setScale(4)),
            parameters.toArray());
    }

    public BigDecimal previousBalance(UUID saleId) {
        return previousSales(saleId).stream().map(Debt::amount)
            .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
    }
}
