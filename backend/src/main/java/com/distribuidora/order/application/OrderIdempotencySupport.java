package com.distribuidora.order.application;

import com.distribuidora.order.application.OrderConfirmationService.ConfirmationCommand;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class OrderIdempotencySupport {
    private OrderIdempotencySupport() { }

    static void lock(JdbcTemplate jdbc, String idempotencyKey) {
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, advisoryLockKey(idempotencyKey));
    }

    private static long advisoryLockKey(String idempotencyKey) {
        byte[] digest = sha256(idempotencyKey);
        return ByteBuffer.wrap(digest).getLong();
    }

    static String fingerprint(ConfirmationCommand request, BigDecimal previousBalance) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, request.idempotencyKey());
        append(canonical, request.customerId());
        append(canonical, request.priceListId());
        append(canonical, request.orderDiscountPercent());
        append(canonical, request.lines() == null ? null : request.lines().size());
        if (request.lines() != null) {
            request.lines().forEach(line -> {
                append(canonical, line.productId());
                append(canonical, line.quantity());
                append(canonical, line.lineDiscountPercent());
                append(canonical, line.unitPriceOverride());
            });
        }
        append(canonical, request.payments() == null ? null : request.payments().size());
        if (request.payments() != null) {
            request.payments().forEach(payment -> {
                append(canonical, payment.method());
                append(canonical, payment.amount());
            });
        }
        append(canonical, request.sellerId());
        if (request.orderDate() != null) {
            append(canonical, "orderDate");
            append(canonical, request.orderDate());
        }
        if (previousBalance.signum() > 0) {
            append(canonical, "previousBalanceAmount");
            append(canonical, previousBalance);
        }
        // Only appended when present so fingerprints of requests without these fields keep their value.
        if (request.payments() != null && request.payments().stream().anyMatch(payment -> payment.transferReference() != null)) {
            append(canonical, "transferReferences");
            request.payments().forEach(payment -> append(canonical, payment.transferReference()));
        }
        if (request.previousDebtPayments() != null && !request.previousDebtPayments().isEmpty()) {
            append(canonical, "previousDebtPayments");
            request.previousDebtPayments().forEach(payment -> {
                append(canonical, payment.method());
                append(canonical, payment.amount());
                append(canonical, payment.transferReference());
            });
        }
        return HexFormat.of().formatHex(sha256(canonical.toString()));
    }

    private static void append(StringBuilder canonical, Object value) {
        String text = value == null ? "<NULL>" : value instanceof BigDecimal decimal
            ? decimal.stripTrailingZeros().toPlainString() : String.valueOf(value);
        canonical.append(text.length()).append(':').append(text).append('|');
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }
}
