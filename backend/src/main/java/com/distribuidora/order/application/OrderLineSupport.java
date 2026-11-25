package com.distribuidora.order.application;

import com.distribuidora.order.application.OrderConfirmationService.ConfirmationCommand;
import com.distribuidora.order.application.OrderConfirmationService.LineCommand;
import com.distribuidora.order.application.OrderConfirmationService.PaymentCommand;
import com.distribuidora.pricing.application.PricingQueryService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shared line pricing, validation, persistence, and value conversion helpers for order commands. */
final class OrderLineSupport {
    static final BigDecimal ZERO = new BigDecimal("0.0000");

    record ResolvedLine(UUID productId, String productName, BigDecimal quantity, BigDecimal unitPrice,
                        BigDecimal discount, UUID priceListId, String priceListCode, String productStatus) { }

    private OrderLineSupport() { }

    static List<ResolvedLine> resolveLines(JdbcTemplate jdbc, PricingQueryService pricing, ConfirmationCommand request) {
        List<UUID> distinctProductIds = request.lines().stream().map(LineCommand::productId).distinct().toList();
        Map<UUID, Map<String, Object>> pricesByProduct = new HashMap<>();
        for (int start = 0; start < distinctProductIds.size(); start += 100) {
            List<UUID> chunk = distinctProductIds.subList(start, Math.min(start + 100, distinctProductIds.size()));
            for (Map<String, Object> priceRow : pricing.resolveBatch(request.customerId(), chunk, request.priceListId())) {
                pricesByProduct.put(uuidOrNull(priceRow.get("productId")), priceRow);
            }
        }
        Map<UUID, Map<String, Object>> productsById = new HashMap<>();
        String placeholders = String.join(",", Collections.nCopies(distinctProductIds.size(), "?"));
        for (Map<String, Object> productRow : jdbc.queryForList(
            "select id, name, status from catalog.products where id in (" + placeholders + ")",
            distinctProductIds.toArray())) {
            productsById.put(uuidOrNull(productRow.get("id")), productRow);
        }
        List<ResolvedLine> lines = new ArrayList<>(request.lines().size());
        for (LineCommand line : request.lines()) {
            Map<String, Object> price = pricesByProduct.get(line.productId());
            Map<String, Object> product = productsById.get(line.productId());
            if (price == null || product == null) {
                throw new EmptyResultDataAccessException(1);
            }
            BigDecimal unitPrice = line.unitPriceOverride() == null
                ? decimal(price.get("unitPrice")) : line.unitPriceOverride();
            lines.add(new ResolvedLine(line.productId(), String.valueOf(product.get("name")), line.quantity(), unitPrice,
                line.lineDiscountPercent(), (UUID) price.get("priceListId"), String.valueOf(price.get("priceListCode")),
                String.valueOf(product.get("status"))));
        }
        return lines;
    }

    static void insertItems(JdbcTemplate jdbc, String table, UUID parentId, List<ResolvedLine> resolved,
                            List<OrderCalculationService.CalculatedLineResult> calculated) {
        String sql = "insert into " + table + "(id, " + (table.startsWith("orders") ? "order_id" : "sale_id")
            + ", product_id, product_name, quantity, unit_price, line_total, price_list_id, price_list_code, line_discount_percent) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        List<Object[]> batchArgs = new ArrayList<>(resolved.size());
        for (int i = 0; i < resolved.size(); i++) {
            ResolvedLine line = resolved.get(i);
            var result = calculated.get(i);
            batchArgs.add(new Object[]{UUID.randomUUID(), parentId, line.productId(), line.productName(),
                result.quantity(), result.unitPrice(), result.lineTotal(),
                line.priceListId(), line.priceListCode(), result.lineDiscountPercent()});
        }
        jdbc.batchUpdate(sql, batchArgs);
    }

    static void validateRequest(ConfirmationCommand request) {
        if (request == null) {
            throw new IllegalArgumentException("request es obligatorio");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            || request.idempotencyKey().length() > 100 || request.customerId() == null
            || request.lines() == null || request.lines().isEmpty() || request.orderDiscountPercent() == null) {
            throw new IllegalArgumentException("Los campos obligatorios son inválidos");
        }
        if (request.lines().size() > 500) {
            throw new IllegalArgumentException("Se admiten hasta 500 líneas por pedido");
        }
        validatePercent(request.orderDiscountPercent(), "orderDiscountPercent");
        if (request.orderDate() != null && (request.orderDate().getYear() < 1 || request.orderDate().getYear() > 9999)) {
            throw new IllegalArgumentException("orderDate debe ser una fecha válida entre los años 0001 y 9999");
        }
        if (request.previousBalanceAmount() != null) {
            if (request.previousBalanceAmount().signum() < 0) {
                throw new IllegalArgumentException("El importe del saldo anterior no puede ser negativo");
            }
            validateScale(request.previousBalanceAmount(), "previousBalanceAmount");
        }
        for (LineCommand line : request.lines()) {
            if (line == null || line.productId() == null || line.quantity() == null
                || line.lineDiscountPercent() == null) {
                throw new IllegalArgumentException("La línea contiene valores obligatorios inválidos");
            }
            validateScale(line.quantity(), "quantity");
            if (line.quantity().signum() <= 0 || line.quantity().remainder(new BigDecimal("0.5")).signum() != 0) {
                throw new IllegalArgumentException("quantity debe ser positiva y múltiplo de 0.5");
            }
            validatePercent(line.lineDiscountPercent(), "lineDiscountPercent");
            if (line.unitPriceOverride() != null) {
                validateScale(line.unitPriceOverride(), "unitPriceOverride");
                if (line.unitPriceOverride().signum() < 0) {
                    throw new IllegalArgumentException("unitPriceOverride no puede ser negativo");
                }
            }
        }
        if (request.payments() != null) {
            if (request.payments().size() > 50) {
                throw new IllegalArgumentException("Se admiten hasta 50 pagos por pedido");
            }
            for (PaymentCommand payment : request.payments()) {
                if (payment == null || payment.method() == null
                    || !Set.of("CASH", "BANK_TRANSFER", "CUSTOMER_ACCOUNT").contains(payment.method())) {
                    throw new IllegalArgumentException("method de pago inválido");
                }
                if (payment.amount() == null || payment.amount().signum() <= 0) {
                    throw new IllegalArgumentException("El importe del pago debe ser positivo");
                }
                validateScale(payment.amount(), "payment.amount");
                validateReference(payment);
            }
        }
        if (request.previousDebtPayments() != null) {
            if (request.previousDebtPayments().size() > 20) {
                throw new IllegalArgumentException("Se admiten hasta 20 pagos de deuda anterior");
            }
            for (PaymentCommand payment : request.previousDebtPayments()) {
                if (payment == null || payment.method() == null
                    || !Set.of("CASH", "BANK_TRANSFER").contains(payment.method())) {
                    throw new IllegalArgumentException("La deuda anterior solo admite pagos en efectivo o transferencia");
                }
                if (payment.amount() == null || payment.amount().signum() <= 0) {
                    throw new IllegalArgumentException("El importe del pago de deuda anterior debe ser positivo");
                }
                validateScale(payment.amount(), "previousDebtPayments.amount");
                validateReference(payment);
            }
        }
    }

    static void validateReference(PaymentCommand payment) {
        if (payment.transferReference() != null && !payment.transferReference().isBlank()
            && !"BANK_TRANSFER".equals(payment.method())) {
            throw new IllegalArgumentException("transferReference requiere method BANK_TRANSFER");
        }
        normalizeReference(payment.transferReference());
    }

    static void validatePercent(BigDecimal value, String field) {
        validateScale(value, field);
        if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(field + " debe estar entre 0 y 100");
        }
    }

    static void validateScale(BigDecimal value, String field) {
        if (value.scale() > 4) {
            throw new IllegalArgumentException(field + " no puede tener más de 4 decimales");
        }
        if (value.abs().compareTo(new BigDecimal("999999999999999.9999")) > 0) {
            throw new IllegalArgumentException(field + " supera el máximo permitido");
        }
    }

    static String normalizeReference(String reference) {
        if (reference == null || reference.isBlank()) return null;
        if (reference.trim().length() > 100) {
            throw new IllegalArgumentException("transferReference no puede superar 100 caracteres");
        }
        return reference.trim();
    }

    static BigDecimal previousBalanceAmount(ConfirmationCommand request) {
        return request.previousBalanceAmount() == null ? ZERO : request.previousBalanceAmount().setScale(4);
    }

    static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    static BigDecimal decimalOrZero(Object value) {
        return value == null ? ZERO : decimal(value);
    }

    static UUID uuidOrNull(Object value) {
        return value == null ? null : value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }
}
