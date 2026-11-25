package com.distribuidora.payment.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.access = Objects.requireNonNull(access, "currentUser");
    }

    /** Correlated to the customer alias c; callers bind these parameters before their WHERE parameters. */
    public Projection projection() {
        if (access.isAdmin()) return new Projection("c.balance", List.of());
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
                  and s.paid < s.total
                  and (o.seller_id = ? or (o.seller_id is null and r.seller_id = ?))
            ), 0))
            """, List.of(seller, seller));
    }

    public BigDecimal balance(UUID customerId) {
        access.requireCustomerAccess(customerId);
        Projection projection = projection();
        List<Object> parameters = new ArrayList<>(projection.parameters()); parameters.add(customerId);
        return Objects.requireNonNullElse(jdbc.queryForObject("select " + projection.sql() + " from customer.customers c where c.id = ?",
            BigDecimal.class, parameters.toArray()), BigDecimal.ZERO).setScale(4);
    }

    /**
     * Account balance excluding any unpaid debt belonging to {@code excludedSaleId}, used when editing a
     * confirmed order so the order's own unpaid balance cannot be double-counted as {@code previousBalanceAmount}.
     */
    public BigDecimal balanceExcludingSale(UUID customerId, UUID excludedSaleId) {
        access.requireCustomerAccess(customerId);
        if (excludedSaleId == null) return balance(customerId);
        BigDecimal excludedDebt = jdbc.queryForObject("""
            select least(greatest(s.total - s.paid, 0), greatest(coalesce((
                select sum(case when l.entry_type = 'DEBIT' then l.amount else -l.amount end)
                from customer.account_ledger l where l.sale_id = s.id
            ), 0), 0))
            from sale.sales s where s.id = ? and s.account_customer_id = ?
            """, BigDecimal.class, excludedSaleId, customerId);
        BigDecimal excluded = excludedDebt == null ? BigDecimal.ZERO : excludedDebt.max(BigDecimal.ZERO);
        if (access.isAdmin()) {
            BigDecimal accountBalance = jdbc.queryForObject(
                "select balance from customer.customers where id = ?", BigDecimal.class, customerId);
            BigDecimal raw = accountBalance == null ? BigDecimal.ZERO : accountBalance;
            return raw.subtract(excluded).max(BigDecimal.ZERO).setScale(4);
        }
        UUID seller = access.requireSellerProfile();
        BigDecimal scoped = jdbc.queryForObject("""
            select least(greatest(c.balance - ?, 0), coalesce((
                select sum(least(greatest(coalesce((
                    select sum(case when l.entry_type = 'DEBIT' then l.amount else -l.amount end)
                    from customer.account_ledger l where l.sale_id = s.id
                ), 0), 0), greatest(s.total - s.paid, 0)))
                from sale.sales s join orders.orders o on o.id = s.order_id
                join customer.customers r on r.id = s.customer_id
                where s.account_customer_id = c.id and s.status in ('CONFIRMED', 'DELIVERED')
                  and s.paid < s.total and s.id <> ?
                  and (o.seller_id = ? or (o.seller_id is null and r.seller_id = ?))
            ), 0))
            from customer.customers c where c.id = ?
            """, BigDecimal.class, excluded, excludedSaleId, seller, seller, customerId);
        return (scoped == null ? BigDecimal.ZERO : scoped).max(BigDecimal.ZERO).setScale(4);
    }
}
