package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.notification.application.OutboxService;


import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.pricing.application.CommercialDiscountRuleQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.Set;

@Service
public class OrderConfirmationService {
    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    public interface LineCommand {
        UUID productId();
        BigDecimal quantity();
        BigDecimal lineDiscountPercent();
        BigDecimal unitPriceOverride();
    }

    public interface PaymentCommand {
        String method();
        BigDecimal amount();
    }

    public interface ConfirmationCommand {
        String idempotencyKey();
        UUID customerId();
        UUID priceListId();
        List<? extends LineCommand> lines();
        BigDecimal orderDiscountPercent();
        List<? extends PaymentCommand> payments();
        default UUID sellerId() { return null; }
    }

    public interface EditCommand {
        UUID priceListId();
        List<? extends LineCommand> lines();
        BigDecimal orderDiscountPercent();
    }

    public record ConfirmationData(String idempotencyKey, UUID customerId, UUID priceListId,
                                   List<? extends LineCommand> lines, BigDecimal orderDiscountPercent,
                                   List<? extends PaymentCommand> payments) implements ConfirmationCommand { }

    public record CreditLimitWarningResult(BigDecimal creditLimit, BigDecimal projectedBalance, BigDecimal exceededBy) { }

    public record ConfirmationResult(UUID orderId, UUID saleId, String orderNumber, String saleNumber,
                                     BigDecimal total, BigDecimal paid, BigDecimal balance,
                                     CreditLimitWarningResult creditLimitWarning) { }

    public record EditResult(UUID orderId, UUID saleId, BigDecimal total, BigDecimal paid, BigDecimal balance) { }

    private final JdbcTemplate jdbc;
    private final PricingQueryService pricing;
    private final CommercialDiscountRuleQueryService discountRules;
    private final OrderCalculationService calculation;
    private final InventoryMovementService inventory;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;
    private final OutboxService outbox;

    @Autowired
    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    CommercialDiscountRuleQueryService discountRules,
                                    OrderCalculationService calculation, InventoryMovementService inventory,
                                    AuditService audit, OutboxService outbox, CurrentUserAccess currentUser) {
        this(jdbc, pricing, discountRules, calculation, inventory, audit, currentUser, outbox);
    }

    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    OrderCalculationService calculation,
                                    InventoryMovementService inventory, AuditService audit,
                                    CurrentUserAccess currentUser) {
        this(jdbc, pricing, null, calculation, inventory, audit, currentUser, null);
    }

    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    OrderCalculationService calculation,
                                    InventoryMovementService inventory, AuditService audit) {
        this(jdbc, pricing, null, calculation, inventory, audit, (CurrentUserAccess) null, (OutboxService) null);
    }

    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    CommercialDiscountRuleQueryService discountRules,
                                    OrderCalculationService calculation,
                                    InventoryMovementService inventory, AuditService audit) {
        this(jdbc, pricing, discountRules, calculation, inventory, audit,
            (CurrentUserAccess) null, (OutboxService) null);
    }

    private OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                     CommercialDiscountRuleQueryService discountRules,
                                     OrderCalculationService calculation,
                                     InventoryMovementService inventory, AuditService audit,
                                     CurrentUserAccess currentUser, OutboxService outbox) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.discountRules = discountRules;
        this.calculation = calculation;
        this.inventory = inventory;
        this.audit = audit;
        this.currentUser = currentUser;
        this.outbox = outbox;
    }

    @Transactional
    public ConfirmationResult confirm(ConfirmationCommand request) {
        validateRequest(request);
        validateOverrides(request);
        String fingerprint = fingerprint(request);
        lockIdempotencyKey(request.idempotencyKey());
        ConfirmationResult existing = findExisting(request, fingerprint);
        if (existing != null) {
            return existing;
        }

        UUID customerId = request.customerId();
        if (currentUser != null) currentUser.requireCustomerAccess(customerId);
        Map<String, Object> customer = jdbc.queryForMap(
            "select id, status, seller_id, balance from customer.customers where id = ?", customerId);
        if (!"ACTIVE".equals(customer.get("status"))) {
            throw new IllegalStateException("El cliente no está activo");
        }
        UUID sellerId = resolveSellerId(customer, request.sellerId());
        if (sellerId != null) {
            boolean sellerExists = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from seller.seller_profiles where id = ?)", Boolean.class, sellerId));
            if (!sellerExists) throw new org.springframework.dao.EmptyResultDataAccessException(1);
        }

        List<ResolvedLine> resolved = resolveLines(request);
        AppliedDiscount orderDiscount = resolveOrderDiscount(request);
        OrderCalculationService.OrderCalculation calculated = calculation.calculate(
            resolved.stream().map(line -> new OrderCalculationService.CalculatedLine(
                line.productId(), line.quantity(), line.unitPrice(), line.discount())).toList(),
            orderDiscount.percent(), request.payments());

        BigDecimal monetaryPaid = monetaryPaid(request.payments());
        List<BigDecimal> accountDebits = accountDebits(request.payments(), calculated.total(), monetaryPaid);
        BigDecimal totalAccountDebit = accountDebits.stream().reduce(ZERO, BigDecimal::add).setScale(4);
        BigDecimal projectedBalance = decimalOrZero(customer.get("balance")).add(totalAccountDebit).setScale(4);
        BigDecimal creditLimit = jdbc.queryForObject(
            "select credit_limit from app.business_settings where id = 1", BigDecimal.class);
        boolean creditLimitExceeded = creditLimit != null && projectedBalance.compareTo(creditLimit) > 0;
        CreditLimitWarningResult creditWarning = creditLimitExceeded
            ? new CreditLimitWarningResult(creditLimit, projectedBalance,
                projectedBalance.subtract(creditLimit).setScale(4)) : null;

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
        jdbc.update("insert into orders.orders(id, order_number, customer_id, seller_id, status, subtotal, discount, total, created_at, idempotency_key, idempotency_fingerprint, credit_limit_exceeded, credit_limit_snapshot, projected_balance_snapshot, order_discount_percent, order_discount_rule_id) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            orderId, orderNumber, customerId, sellerId, calculated.subtotal(),
            calculated.lineDiscount().add(calculated.orderDiscount()), calculated.total(), now,
            request.idempotencyKey(), fingerprint, creditLimitExceeded, creditLimit, projectedBalance,
            calculated.orderDiscountPercent(), orderDiscount.ruleId());
        insertItems("orders.order_items", orderId, resolved, calculated.lines());

        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, order_discount_percent, order_discount_rule_id) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?)",
            saleId, saleNumber, orderId, customerId, calculated.total(), monetaryPaid, now,
            calculated.orderDiscountPercent(), orderDiscount.ruleId());
        insertItems("sale.sale_items", saleId, resolved, calculated.lines());

        insertPayments(saleId, customerId, request.payments());
        for (BigDecimal accountDebit : accountDebits) {
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)",
                UUID.randomUUID(), customerId, saleId, accountDebit, now);
        }
        if (totalAccountDebit.signum() > 0) {
            jdbc.update("update customer.customers set balance = balance + ? where id = ?", totalAccountDebit, customerId);
        }

        BigDecimal responsePaid = monetaryPaid(request.payments());
        ConfirmationResult response = new ConfirmationResult(
            orderId, saleId, orderNumber, saleNumber, calculated.total(), responsePaid,
            calculated.total().subtract(responsePaid).setScale(4), creditWarning);
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("saleId", saleId.toString());
        auditDetails.put("total", calculated.total());
        auditDetails.put("paid", responsePaid);
        auditDetails.put("balance", response.balance());
        auditDetails.put("creditLimit", creditLimit);
        auditDetails.put("projectedBalance", projectedBalance);
        auditDetails.put("creditLimitExceeded", creditLimitExceeded);
        auditDetails.put("orderDiscountRuleId", orderDiscount.ruleId() == null ? null : orderDiscount.ruleId().toString());
        auditDetails.put("lineDiscountRuleIds", resolved.stream().map(ResolvedLine::discountRuleId)
            .filter(Objects::nonNull).map(UUID::toString).distinct().toList());
        audit.recordWithinTransaction(actorId(), "ORDER_CONFIRM", "ORDER", orderId.toString(), "SUCCESS", auditDetails);
        if (creditWarning != null) {
            audit.recordWithinTransaction(actorId(), "CREDIT_LIMIT_WARNING", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("orderId", orderId.toString(), "saleId", saleId.toString(), "creditLimit", creditLimit,
                    "projectedBalance", projectedBalance, "exceededBy", creditWarning.exceededBy()));
        }
        if (outbox != null) {
            outbox.enqueue("ORDER_CONFIRMED", "ORDER", orderId,
                Map.of("orderId", orderId.toString(), "saleId", saleId.toString(),
                    "orderNumber", orderNumber, "saleNumber", saleNumber,
                    "customerId", customerId.toString(), "total", calculated.total()),
                "ORDER_CONFIRMED:" + orderId);
        }
        return response;
    }

    @Transactional
    public EditResult editConfirmed(UUID orderId, EditCommand request) {
        if (!hasAuthority("ADMIN_ALL")) {
            throw new AccessDeniedException("ADMIN_ALL es requerido para editar pedidos confirmados");
        }
        if (orderId == null || request == null || request.lines() == null || request.lines().isEmpty()
            || request.orderDiscountPercent() == null) {
            throw new IllegalArgumentException("Los campos obligatorios son inválidos");
        }

        Map<String, Object> lifecycle = jdbc.queryForMap("select o.id as order_id, o.status as order_status, o.total as order_total, "
            + "s.id as sale_id, s.status as sale_status, s.customer_id, s.total as sale_total, s.paid "
            + "from orders.orders o join sale.sales s on s.order_id = o.id where o.id = ? for update of o, s", orderId);
        if (!"CONFIRMED".equals(lifecycle.get("order_status")) || !"CONFIRMED".equals(lifecycle.get("sale_status"))) {
            throw new IllegalStateException("Solo se pueden editar pedidos y ventas confirmados");
        }

        UUID customerId = uuidOrNull(lifecycle.get("customer_id"));
        Map<String, Object> customer = jdbc.queryForMap(
            "select id, balance from customer.customers where id = ? for update", customerId);
        ConfirmationCommand pricingRequest = new ConfirmationData(
            "confirmed-order-edit", customerId, request.priceListId(), request.lines(), request.orderDiscountPercent(), List.of());
        validateRequest(pricingRequest);
        validateOverrides(pricingRequest);
        List<ResolvedLine> resolved = resolveLines(pricingRequest);
        AppliedDiscount orderDiscount = resolveOrderDiscount(pricingRequest);
        OrderCalculationService.OrderCalculation calculated = calculation.calculate(
            resolved.stream().map(line -> new OrderCalculationService.CalculatedLine(
                line.productId(), line.quantity(), line.unitPrice(), line.discount())).toList(),
            orderDiscount.percent());

        UUID saleId = uuidOrNull(lifecycle.get("sale_id"));
        BigDecimal paid = decimal(lifecycle.get("paid")).setScale(4);
        if (calculated.total().compareTo(paid) < 0) {
            throw new IllegalStateException("El nuevo total no puede ser menor que el importe ya pagado");
        }

        Map<UUID, BigDecimal> oldQuantities = quantities("orders.order_items", "order_id", orderId);
        Map<UUID, BigDecimal> newQuantities = new java.util.HashMap<>();
        resolved.forEach(line -> newQuantities.merge(line.productId(), line.quantity(), BigDecimal::add));
        java.util.Set<UUID> productIds = new java.util.HashSet<>(oldQuantities.keySet());
        productIds.addAll(newQuantities.keySet());
        int inventoryMovementsApplied = 0;
        for (UUID productId : productIds.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            BigDecimal stockDelta = oldQuantities.getOrDefault(productId, ZERO)
                .subtract(newQuantities.getOrDefault(productId, ZERO));
            if (stockDelta.signum() < 0) {
                inventory.apply(productId, stockDelta, "SALE", orderId, "Confirmed order edit");
                inventoryMovementsApplied++;
            } else if (stockDelta.signum() > 0) {
                inventory.apply(productId, stockDelta, "SALE_CANCELLATION", orderId, "Confirmed order edit");
                inventoryMovementsApplied++;
            }
        }

        BigDecimal currentAccountDebt = jdbc.queryForObject(
            "select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0) "
                + "from customer.account_ledger where sale_id = ?", BigDecimal.class, saleId);
        BigDecimal previousTotal = decimal(lifecycle.get("sale_total")).setScale(4);
        BigDecimal targetAccountDebt = currentAccountDebt.add(calculated.total().subtract(previousTotal)).setScale(4);
        BigDecimal ledgerAdjustment = targetAccountDebt.subtract(currentAccountDebt).setScale(4);
        Timestamp now = Timestamp.from(Instant.now());
        if (ledgerAdjustment.signum() != 0) {
            String entryType = ledgerAdjustment.signum() > 0 ? "DEBIT" : "CREDIT";
            BigDecimal amount = ledgerAdjustment.abs();
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), customerId, saleId, entryType, amount, now);
            jdbc.update("update customer.customers set balance = balance "
                    + (ledgerAdjustment.signum() > 0 ? "+" : "-") + " ? where id = ?",
                amount, customerId);
        }

        jdbc.update("delete from orders.order_items where order_id = ?", orderId);
        jdbc.update("delete from sale.sale_items where sale_id = ?", saleId);
        insertItems("orders.order_items", orderId, resolved, calculated.lines());
        insertItems("sale.sale_items", saleId, resolved, calculated.lines());
        BigDecimal discount = calculated.lineDiscount().add(calculated.orderDiscount()).setScale(4);
        jdbc.update("update orders.orders set subtotal = ?, discount = ?, total = ?, order_discount_percent = ?, order_discount_rule_id = ? where id = ?",
            calculated.subtotal(), discount, calculated.total(), calculated.orderDiscountPercent(),
            orderDiscount.ruleId(), orderId);
        jdbc.update("update sale.sales set total = ?, order_discount_percent = ?, order_discount_rule_id = ? where id = ?",
            calculated.total(), calculated.orderDiscountPercent(), orderDiscount.ruleId(), saleId);

        audit.recordWithinTransaction(actorId(), "ORDER_EDIT", "ORDER", orderId.toString(), "SUCCESS",
            Map.of("saleId", saleId.toString(), "previousTotal", decimal(lifecycle.get("sale_total")),
                "total", calculated.total(), "paid", paid, "accountDebtAdjustment", ledgerAdjustment,
                "inventoryMovementsApplied", inventoryMovementsApplied));
        return new EditResult(orderId, saleId, calculated.total(), paid, targetAccountDebt);
    }

    private Map<UUID, BigDecimal> quantities(String table, String parentColumn, UUID parentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select product_id, sum(quantity) as quantity from " + table + " where " + parentColumn + " = ? group by product_id", parentId);
        Map<UUID, BigDecimal> quantities = new java.util.HashMap<>();
        rows.forEach(row -> quantities.put(uuidOrNull(row.get("product_id")), decimal(row.get("quantity"))));
        return quantities;
    }

    private UUID resolveSellerId(Map<String, Object> customer, UUID requestedSellerId) {
        if (currentUser != null && !currentUser.isAdmin()) {
            UUID authenticatedSellerId = currentUser.requireSellerProfile();
            if (requestedSellerId != null && !requestedSellerId.equals(authenticatedSellerId)) {
                throw new AccessDeniedException("No se puede cambiar el vendedor asignado al pedido");
            }
            return authenticatedSellerId;
        }
        return requestedSellerId != null ? requestedSellerId : uuidOrNull(customer.get("seller_id"));
    }

    private UUID uuidOrNull(Object value) {
        return value == null ? null : value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private List<ResolvedLine> resolveLines(ConfirmationCommand request) {
        List<ResolvedLine> lines = new ArrayList<>();
        for (LineCommand line : request.lines()) {
            Map<String, Object> price = pricing.resolve(request.customerId(), line.productId(), request.priceListId());
            BigDecimal unitPrice = line.unitPriceOverride() == null
                ? decimal(price.get("unitPrice")) : line.unitPriceOverride();
            BigDecimal discountPercent = line.lineDiscountPercent();
            UUID discountRuleId = null;
            if (discountPercent.signum() == 0 && discountRules != null) {
                var rule = discountRules.lineDiscount(request.customerId(), line.productId(),
                    (UUID) price.get("priceListId"));
                if (rule.isPresent()) {
                    discountPercent = rule.get().percent();
                    discountRuleId = rule.get().ruleId();
                }
            }
            String productName = jdbc.queryForObject(
                "select name from catalog.products where id = ?", String.class, line.productId());
            lines.add(new ResolvedLine(line.productId(), productName, line.quantity(), unitPrice,
                discountPercent, discountRuleId, (UUID) price.get("priceListId"), String.valueOf(price.get("priceListCode"))));
        }
        return lines;
    }

    private AppliedDiscount resolveOrderDiscount(ConfirmationCommand request) {
        if (request.orderDiscountPercent().signum() > 0 || discountRules == null) {
            return new AppliedDiscount(request.orderDiscountPercent(), null);
        }
        return discountRules.orderDiscount(request.customerId(), request.priceListId())
            .map(rule -> new AppliedDiscount(rule.percent(), rule.ruleId()))
            .orElseGet(() -> new AppliedDiscount(request.orderDiscountPercent(), null));
    }

    private void insertItems(String table, UUID parentId, List<ResolvedLine> resolved,
                             List<OrderCalculationService.CalculatedLineResult> calculated) {
        for (int i = 0; i < resolved.size(); i++) {
            ResolvedLine line = resolved.get(i);
            var result = calculated.get(i);
            jdbc.update("insert into " + table + "(id, " + (table.startsWith("orders") ? "order_id" : "sale_id")
                    + ", product_id, product_name, quantity, unit_price, line_total, price_list_id, price_list_code, line_discount_percent, discount_rule_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), parentId, line.productId(), line.productName(), result.quantity(), result.unitPrice(),
                result.lineTotal(), line.priceListId(), line.priceListCode(), result.lineDiscountPercent(), line.discountRuleId());
        }
    }

    private void insertPayments(UUID saleId, UUID customerId, List<? extends PaymentCommand> payments) {
        if (payments == null) return;
        Timestamp now = Timestamp.from(Instant.now());
        for (var payment : payments) {
            if (!"CUSTOMER_ACCOUNT".equals(payment.method())) {
                jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, created_at) values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), saleId, customerId, payment.amount(), payment.method(), now);
            }
        }
    }

    private ConfirmationResult findExisting(ConfirmationCommand request,
                                                                    String fingerprint) {
        List<ExistingOrder> rows = jdbc.query(
            "select o.id as order_id, s.id as sale_id, o.customer_id, o.order_number, s.sale_number, o.total, s.paid, "
                + "o.idempotency_fingerprint, o.credit_limit_exceeded, o.credit_limit_snapshot, o.projected_balance_snapshot "
                + "from orders.orders o join sale.sales s on s.order_id = o.id where o.idempotency_key = ?",
            (rs, rowNum) -> new ExistingOrder(rs.getObject("order_id", UUID.class), rs.getObject("sale_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getString("order_number"), rs.getString("sale_number"),
                rs.getBigDecimal("total"), rs.getBigDecimal("paid"), rs.getString("idempotency_fingerprint"),
                rs.getBoolean("credit_limit_exceeded"), rs.getBigDecimal("credit_limit_snapshot"),
                rs.getBigDecimal("projected_balance_snapshot")), request.idempotencyKey());
        if (rows.isEmpty()) return null;
        ExistingOrder existing = rows.get(0);
        if (!Objects.equals(existing.fingerprint(), fingerprint)) {
            throw new IdempotencyConflictException();
        }
        CreditLimitWarningResult warning = existing.creditLimitExceeded()
            ? new CreditLimitWarningResult(existing.creditLimit(), existing.projectedBalance(),
                existing.projectedBalance().subtract(existing.creditLimit()).setScale(4)) : null;
        return new ConfirmationResult(existing.orderId(), existing.saleId(), existing.orderNumber(),
            existing.saleNumber(), existing.total(), existing.paid(), existing.total().subtract(existing.paid()).setScale(4), warning);
    }

    private void validateOverrides(ConfirmationCommand request) {
        boolean override = request.orderDiscountPercent().signum() != 0
            || request.lines().stream().anyMatch(line -> line.unitPriceOverride() != null
                || line.lineDiscountPercent().signum() != 0);
        if (override && !hasAuthority("ADMIN_ALL")) {
            throw new AccessDeniedException("ADMIN_ALL es requerido para modificar precios o descuentos");
        }
    }

    private void validateRequest(ConfirmationCommand request) {
        if (request == null) {
            throw new IllegalArgumentException("request es obligatorio");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()
            || request.idempotencyKey().length() > 100 || request.customerId() == null
            || request.lines() == null || request.lines().isEmpty() || request.orderDiscountPercent() == null) {
            throw new IllegalArgumentException("Los campos obligatorios son inválidos");
        }
        validatePercent(request.orderDiscountPercent(), "orderDiscountPercent");
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
            for (PaymentCommand payment : request.payments()) {
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

    private BigDecimal monetaryPaid(List<? extends PaymentCommand> payments) {
        if (payments == null) return ZERO;
        return payments.stream().filter(payment -> !"CUSTOMER_ACCOUNT".equals(payment.method()))
            .map(PaymentCommand::amount).reduce(ZERO, BigDecimal::add).setScale(4);
    }

    private List<BigDecimal> accountDebits(List<? extends PaymentCommand> payments,
                                            BigDecimal total, BigDecimal monetaryPaid) {
        List<BigDecimal> debits = new ArrayList<>();
        BigDecimal explicitAccountTotal = ZERO;
        if (payments != null) {
            for (PaymentCommand payment : payments) {
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

    private BigDecimal decimalOrZero(Object value) {
        return value == null ? ZERO : decimal(value);
    }

    private void lockIdempotencyKey(String idempotencyKey) {
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, advisoryLockKey(idempotencyKey));
    }

    private long advisoryLockKey(String idempotencyKey) {
        byte[] digest = sha256(idempotencyKey);
        return ByteBuffer.wrap(digest).getLong();
    }

    private String fingerprint(ConfirmationCommand request) {
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
                                BigDecimal discount, UUID discountRuleId, UUID priceListId, String priceListCode) { }

    private record AppliedDiscount(BigDecimal percent, UUID ruleId) { }

    private record ExistingOrder(UUID orderId, UUID saleId, UUID customerId, String orderNumber, String saleNumber,
                                 BigDecimal total, BigDecimal paid, String fingerprint, boolean creditLimitExceeded,
                                 BigDecimal creditLimit, BigDecimal projectedBalance) { }
}
