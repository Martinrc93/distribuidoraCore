package com.distribuidora.document.application;

import com.distribuidora.document.rendering.AccountStatementModel;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a customer's debt statement: CONFIRMED/DELIVERED sales with a positive balance, oldest first.
 * Each balance is capped like the debts API (ledger balance limited to total - paid). Sellers only see
 * sales of their own orders, or of orders without seller for customers assigned to them.
 */
@Service
public class AccountStatementDocumentService {
    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    public AccountStatementDocumentService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AccountStatementModel load(UUID customerId) {
        Map<String, Object> customer;
        try {
            customer = jdbc.queryForMap("select business_name, tax_id, address from customer.customers where id = ?", customerId);
        } catch (EmptyResultDataAccessException exception) {
            throw new DocumentNotFoundException("Cliente inexistente");
        }
        List<Object> parameters = new ArrayList<>(List.of(customerId));
        String scope = "";
        if (!currentUser.isAdmin()) {
            currentUser.requireCustomerAccess(customerId);
            UUID sellerId = currentUser.requireSellerProfile();
            scope = " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        List<AccountStatementModel.Line> lines = jdbc.query("""
            select (s.created_at at time zone 'America/Argentina/Buenos_Aires')::date as sale_date,
                   s.sale_number, o.order_number, s.total, s.paid,
                   least(greatest(coalesce(l.balance, 0), 0), greatest(s.total - s.paid, 0)) as due
            from sale.sales s
            join orders.orders o on o.id = s.order_id
            join customer.customers c on c.id = s.customer_id
            left join lateral (
                select sum(case when entry_type = 'DEBIT' then amount else -amount end) as balance
                from customer.account_ledger where sale_id = s.id
            ) l on true
            where s.customer_id = ? and s.status in ('CONFIRMED', 'DELIVERED')
            """ + scope + """
              and least(greatest(coalesce(l.balance, 0), 0), greatest(s.total - s.paid, 0)) > 0
            order by s.created_at, s.id
            """, (rs, row) -> new AccountStatementModel.Line(
                rs.getObject("sale_date", LocalDate.class), rs.getString("sale_number"), rs.getString("order_number"),
                rs.getBigDecimal("total"), rs.getBigDecimal("paid"), rs.getBigDecimal("due")),
            parameters.toArray());
        BigDecimal totalDue = lines.stream().map(AccountStatementModel.Line::due).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate today = jdbc.queryForObject(
            "select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class);
        return new AccountStatementModel((String) customer.get("business_name"), (String) customer.get("tax_id"),
            (String) customer.get("address"), today, lines, totalDue);
    }
}
