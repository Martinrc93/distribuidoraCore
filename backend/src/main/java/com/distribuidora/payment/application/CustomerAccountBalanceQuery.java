package com.distribuidora.payment.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One balance definition for customer lists, details and account collection. The balance belongs to the
 * account that owes each sale; for assigned scopes, orders without seller use the recipient's assignment.
 */
public final class CustomerAccountBalanceQuery {
    public record Projection(String sql, List<Object> parameters) { }
    private final JdbcTemplate jdbc;
    private final CurrentUserAccess access;

    public CustomerAccountBalanceQuery(JdbcTemplate jdbc, CurrentUserAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** Correlated to the customer alias c; callers bind these parameters before their WHERE parameters. */
    public Projection projection() {
        if (access == null || access.isAdmin()) return new Projection("c.balance", List.of());
        UUID seller = access.requireSellerProfile();
        return new Projection("""
            least(greatest(c.balance, 0), coalesce((
                select sum(least(greatest(coalesce((
                    select sum(case when l.entry_type = 'DEBIT' then l.amount else -l.amount end)
                    from customer.account_ledger l where l.sale_id = s.id
                ), 0), 0), greatest(s.total - s.paid, 0)))
                from sale.sales s join orders.orders o on o.id = s.order_id
                join customer.customers r on r.id = s.customer_id
                where s.account_customer_id = c.id and s.status in ('CONFIRMED', 'DELIVERED')
                  and (o.seller_id = ? or (o.seller_id is null and r.seller_id = ?))
            ), 0))
            """, List.of(seller, seller));
    }

    public BigDecimal balance(UUID customerId) {
        if (access != null) access.requireCustomerAccess(customerId);
        Projection projection = projection();
        List<Object> parameters = new ArrayList<>(projection.parameters()); parameters.add(customerId);
        return jdbc.queryForObject("select " + projection.sql() + " from customer.customers c where c.id = ?",
            BigDecimal.class, parameters.toArray()).setScale(4);
    }
}
