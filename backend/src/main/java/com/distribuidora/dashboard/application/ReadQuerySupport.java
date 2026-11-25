package com.distribuidora.dashboard.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Shared pagination, LIKE escaping, seller scoping, and date-window helpers for read queries. */
final class ReadQuerySupport {
    static final String ORDER_DETAIL_SELECT = """
            select o.id, o.order_number as number, o.customer_id as "customerId",
                   c.business_name as customer,
                   coalesce(assigned_seller.display_name, customer_seller.display_name, 'Sin asignar') as seller,
                   o.status, o.subtotal, o.discount, o.total,
                   o.fulfillment_status as "fulfillmentStatus", o.prepared_at as "preparedAt",
                   o.prepared_by_user_id as "preparedByUserId", o.legacy_fulfillment as "legacyFulfillment",
                   o.created_by_admin as "createdByAdmin", o.created_by_user_id as "createdByUserId",
                   (o.created_by_admin = false and o.reviewed_at is null) as "reviewPending",
                   o.reviewed_at as "reviewedAt", o.reviewed_by_user_id as "reviewedByUserId",
                   o.order_discount_percent as "orderDiscountPercent",
                   o.credit_limit_exceeded as "creditLimitExceeded", o.credit_limit_snapshot as "creditLimitSnapshot",
                   o.projected_balance_snapshot as "projectedBalanceSnapshot",
                   account_customer.balance as "customerBalance", o.previous_balance_amount as "previousBalanceAmount",
                   account_sale.account_customer_id as "accountCustomerId", account_customer.business_name as "accountCustomer",
                   account_customer.kind as "accountKind",
                   (o.total + o.previous_balance_amount) as "collectionTotal", o.created_at as date
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            join sale.sales account_sale on account_sale.order_id = o.id
            join customer.customers account_customer on account_customer.id = account_sale.account_customer_id
            left join seller.seller_profiles assigned_seller on assigned_seller.id = o.seller_id
            left join seller.seller_profiles customer_seller on customer_seller.id = c.seller_id
            """;

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    ReadQuerySupport(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    CurrentUserAccess currentUser() {
        return currentUser;
    }

    boolean sellerScoped() {
        return !currentUser.isAdmin();
    }

    PageResponse<Map<String, Object>> page(String sql, String countSql, int page, int size, Object... parameters) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return pageUnpaged(sql + " limit ? offset ?", append(parameters, safeSize, (long) safePage * safeSize),
            countSql, parameters, safePage, safeSize);
    }

    PageResponse<Map<String, Object>> pageUnpaged(String rowsSql, Object[] rowParameters,
                                                  String countSql, Object[] countParameters,
                                                  int safePage, int safeSize) {
        List<Map<String, Object>> rows = jdbc.queryForList(rowsSql, rowParameters);
        Number total = jdbc.queryForObject(countSql, Number.class, countParameters);
        return PageResponse.of(rows, safePage, safeSize, total == null ? 0 : total.longValue());
    }

    static Object[] append(Object[] values, Object... suffix) {
        Object[] result = Arrays.copyOf(values, values.length + suffix.length);
        System.arraycopy(suffix, 0, result, values.length, suffix.length);
        return result;
    }

    static String escapedLike(String value) {
        if (value == null) return null;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return null;
        return "%" + trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    static void validateDateRange(LocalDate from, LocalDate to) {
        if (from.getYear() < 1900 || to.getYear() > 9999) {
            throw new IllegalArgumentException("El año debe estar entre 1900 y 9999");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > 366) {
            throw new IllegalArgumentException("El rango máximo de fechas es 366 días");
        }
    }
}
