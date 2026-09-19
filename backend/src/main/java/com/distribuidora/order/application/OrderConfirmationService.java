package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.api.OrderConfirmationDtos;
import com.distribuidora.pricing.application.PricingQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;

@Service
public class OrderConfirmationService {
    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private final JdbcTemplate jdbc;
    private final PricingQueryService pricing;
    private final OrderCalculationService calculation;
    private final InventoryMovementService inventory;
    private final AuditService audit;

    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    OrderCalculationService calculation,
                                    InventoryMovementService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.calculation = calculation;
        this.inventory = inventory;
        this.audit = audit;
    }

    @Transactional
    public OrderConfirmationDtos.ConfirmationResponse confirm(OrderConfirmationDtos.ConfirmationRequest request) {
        validateRequest(request);
        validateOverrides(request);
        String fingerprint = fingerprint(request);
        lockIdempotencyKey(request.idempotencyKey());
        OrderConfirmationDtos.ConfirmationResponse existing = findExisting(request, fingerprint);
        if (existing != null) {
            return existing;
        }

        UUID customerId = request.customerId();
        Map<String, Object> customer = jdbc.queryForMap(
            "select id, status from customer.customers where id = ?", customerId);
        if (!"ACTIVE".equals(customer.get("status"))) {
            throw new IllegalStateException("El cliente no está activo");
        }

        List<ResolvedLine> resolved = resolveLines(request);
        OrderCalculationService.OrderCalculation calculated = calculation.calculate(
            resolved.stream().map(line -> new OrderCalculationService.CalculatedLine(
                line.productId(), line.quantity(), line.unitPrice(), line.discount())).toList(),
            request.orderDiscountPercent(), request.payments());

        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
            resolved.stream().map(ResolvedLine::productId).distinct().sorted(Comparator.comparing(UUID::toString))
                .forEach(productId -> {
                    BigDecimal quantity = resolved.stream().filter(line -> line.productId().equals(productId))
                        .map(ResolvedLine::quantity).reduce(ZERO, BigDecimal::add);
                    inventory.apply(productId, quantity.negate(), "SALE", orderId, "Order confirmation");
                });

            Timestamp now = Timestamp.from(Instant.now());
            String orderNumber = number("ORD");
            String saleNumber = number("SAL");
            jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, discount, total, created_at, idempotency_key, idempotency_fingerprint) values (?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?)",
                orderId, orderNumber, customerId, calculated.subtotal(),
                calculated.lineDiscount().add(calculated.orderDiscount()), calculated.total(), now,
                request.idempotencyKey(), fingerprint);
            insertItems("orders.order_items", orderId, resolved, calculated.lines());

            BigDecimal monetaryPaid = monetaryPaid(request.payments());
            jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)",
                saleId, saleNumber, orderId, customerId, calculated.total(), monetaryPaid, now);
            insertItems("sale.sale_items", saleId, resolved, calculated.lines());

            insertPayments(saleId, customerId, request.payments());
            List<BigDecimal> accountDebits = accountDebits(request.payments(), calculated.total(), monetaryPaid);
            for (BigDecimal accountDebit : accountDebits) {
                jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)",
                    UUID.randomUUID(), customerId, saleId, accountDebit, now);
            }
            BigDecimal totalAccountDebit = accountDebits.stream().reduce(ZERO, BigDecimal::add).setScale(4);
            if (totalAccountDebit.signum() > 0) {
                jdbc.update("update customer.customers set balance = balance + ? where id = ?", totalAccountDebit, customerId);
            }

            BigDecimal responsePaid = monetaryPaid(request.payments());
            OrderConfirmationDtos.ConfirmationResponse response = new OrderConfirmationDtos.ConfirmationResponse(
                orderId, saleId, orderNumber, saleNumber, calculated.total(), responsePaid,
                calculated.total().subtract(responsePaid).setScale(4));
            audit.recordWithinTransaction(actorId(), "ORDER_CONFIRM", "ORDER", orderId.toString(), "SUCCESS",
                Map.of("saleId", saleId.toString(), "total", calculated.total(), "paid", responsePaid,
                    "balance", response.balance()));
        return response;
    }

    private List<ResolvedLine> resolveLines(OrderConfirmationDtos.ConfirmationRequest request) {
        List<ResolvedLine> lines = new ArrayList<>();
        for (OrderConfirmationDtos.LineRequest line : request.lines()) {
            Map<String, Object> price = pricing.resolve(request.customerId(), line.productId(), request.priceListId());
            BigDecimal unitPrice = line.unitPriceOverride() == null
                ? decimal(price.get("unitPrice")) : line.unitPriceOverride();
            String productName = jdbc.queryForObject(
                "select name from catalog.products where id = ?", String.class, line.productId());
            lines.add(new ResolvedLine(line.productId(), productName, line.quantity(), unitPrice,
                line.lineDiscountPercent(), (UUID) price.get("priceListId"), String.valueOf(price.get("priceListCode"))));
        }
        return lines;
    }

    private void insertItems(String table, UUID parentId, List<ResolvedLine> resolved,
                             List<OrderCalculationService.CalculatedLineResult> calculated) {
        for (int i = 0; i < resolved.size(); i++) {
            ResolvedLine line = resolved.get(i);
            var result = calculated.get(i);
            jdbc.update("insert into " + table + "(id, " + (table.startsWith("orders") ? "order_id" : "sale_id")
                    + ", product_id, product_name, quantity, unit_price, line_total, price_list_id, price_list_code, line_discount_percent) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), parentId, line.productId(), line.productName(), result.quantity(), result.unitPrice(),
                result.lineTotal(), line.priceListId(), line.priceListCode(), result.lineDiscountPercent());
        }
    }

    private void insertPayments(UUID saleId, UUID customerId, List<OrderConfirmationDtos.PaymentRequest> payments) {
        if (payments == null) return;
        Timestamp now = Timestamp.from(Instant.now());
        for (var payment : payments) {
            if (!"CUSTOMER_ACCOUNT".equals(payment.method())) {
                jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, created_at) values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), saleId, customerId, payment.amount(), payment.method(), now);
            }
        }
    }

    private OrderConfirmationDtos.ConfirmationResponse findExisting(OrderConfirmationDtos.ConfirmationRequest request,
                                                                    String fingerprint) {
        List<ExistingOrder> rows = jdbc.query(
            "select o.id as order_id, s.id as sale_id, o.customer_id, o.order_number, s.sale_number, o.total, s.paid, o.idempotency_fingerprint from orders.orders o join sale.sales s on s.order_id = o.id where o.idempotency_key = ?",
            (rs, rowNum) -> new ExistingOrder(rs.getObject("order_id", UUID.class), rs.getObject("sale_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getString("order_number"), rs.getString("sale_number"),
                rs.getBigDecimal("total"), rs.getBigDecimal("paid"), rs.getString("idempotency_fingerprint")), request.idempotencyKey());
        if (rows.isEmpty()) return null;
        ExistingOrder existing = rows.get(0);
        if (!Objects.equals(existing.fingerprint(), fingerprint)) {
            throw new IdempotencyConflictException();
        }
        return new OrderConfirmationDtos.ConfirmationResponse(existing.orderId(), existing.saleId(), existing.orderNumber(),
            existing.saleNumber(), existing.total(), existing.paid(), existing.total().subtract(existing.paid()).setScale(4));
    }

    private void validateOverrides(OrderConfirmationDtos.ConfirmationRequest request) {
        boolean override = request.orderDiscountPercent().signum() != 0
            || request.lines().stream().anyMatch(line -> line.unitPriceOverride() != null
                || line.lineDiscountPercent().signum() != 0);
        if (override && !hasAuthority("ADMIN_ALL")) {
            throw new AccessDeniedException("ADMIN_ALL es requerido para modificar precios o descuentos");
        }
    }

    private void validateRequest(OrderConfirmationDtos.ConfirmationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request es obligatorio");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            || request.idempotencyKey().length() > 100 || request.customerId() == null
            || request.lines() == null || request.lines().isEmpty() || request.orderDiscountPercent() == null) {
            throw new IllegalArgumentException("Los campos obligatorios son inválidos");
        }
        validatePercent(request.orderDiscountPercent(), "orderDiscountPercent");
        for (OrderConfirmationDtos.LineRequest line : request.lines()) {
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
            for (OrderConfirmationDtos.PaymentRequest payment : request.payments()) {
                if (payment == null || payment.method() == null || !Set.of("CASH", "BANK_TRANSFER", "CUSTOMER_ACCOUNT").contains(payment.method())) {
                    throw new IllegalArgumentException("method de pago inválido");
                }
                if (payment.amount() == null || payment.amount().signum() <= 0) {
                    throw new IllegalArgumentException("El importe del pago debe ser positivo");
                }
                validateScale(payment.amount(), "payment.amount");
            }
        }
    }

    private void validatePercent(BigDecimal value, String field) {
        validateScale(value, field);
        if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(field + " debe estar entre 0 y 100");
        }
    }

    private void validateScale(BigDecimal value, String field) {
        if (value.scale() > 4) {
            throw new IllegalArgumentException(field + " no puede tener más de 4 decimales");
        }
    }

    private BigDecimal monetaryPaid(List<OrderConfirmationDtos.PaymentRequest> payments) {
        if (payments == null) return ZERO;
        return payments.stream().filter(payment -> !"CUSTOMER_ACCOUNT".equals(payment.method()))
            .map(OrderConfirmationDtos.PaymentRequest::amount).reduce(ZERO, BigDecimal::add).setScale(4);
    }

    private List<BigDecimal> accountDebits(List<OrderConfirmationDtos.PaymentRequest> payments,
                                            BigDecimal total, BigDecimal monetaryPaid) {
        List<BigDecimal> debits = new ArrayList<>();
        BigDecimal explicitAccountTotal = ZERO;
        if (payments != null) {
            for (OrderConfirmationDtos.PaymentRequest payment : payments) {
                if ("CUSTOMER_ACCOUNT".equals(payment.method())) {
                    BigDecimal amount = payment.amount().setScale(4);
                    debits.add(amount);
                    explicitAccountTotal = explicitAccountTotal.add(amount);
                }
            }
        }
        BigDecimal remainder = total.subtract(monetaryPaid).subtract(explicitAccountTotal).setScale(4);
        if (remainder.signum() > 0) {
            debits.add(remainder);
        }
        return debits;
    }

    private String number(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private void lockIdempotencyKey(String idempotencyKey) {
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, advisoryLockKey(idempotencyKey));
    }

    private long advisoryLockKey(String idempotencyKey) {
        byte[] digest = sha256(idempotencyKey);
        return ByteBuffer.wrap(digest).getLong();
    }

    private String fingerprint(OrderConfirmationDtos.ConfirmationRequest request) {
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
        return hex(sha256(canonical.toString()));
    }

    private void append(StringBuilder canonical, Object value) {
        String text = value == null ? "<NULL>" : value instanceof BigDecimal decimal
            ? decimal.stripTrailingZeros().toPlainString() : String.valueOf(value);
        canonical.append(text.length()).append(':').append(text).append('|');
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ResolvedLine(UUID productId, String productName, BigDecimal quantity, BigDecimal unitPrice,
                                BigDecimal discount, UUID priceListId, String priceListCode) { }

    private record ExistingOrder(UUID orderId, UUID saleId, UUID customerId, String orderNumber, String saleNumber,
                                 BigDecimal total, BigDecimal paid, String fingerprint) { }
}
