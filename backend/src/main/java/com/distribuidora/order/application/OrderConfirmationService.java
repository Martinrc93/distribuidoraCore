package com.distribuidora.order.application;

import com.distribuidora.shared.security.PermissionAccess;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.payment.application.AccountLedger;
import com.distribuidora.payment.application.AccountPaymentService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.notification.application.OutboxService;


import com.distribuidora.pricing.application.PricingQueryService;
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
import java.time.LocalDate;
import java.time.ZoneId;
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
        default String transferReference() { return null; }
    }

    public interface ConfirmationCommand {
        String idempotencyKey();
        UUID customerId();
        UUID priceListId();
        List<? extends LineCommand> lines();
        BigDecimal orderDiscountPercent();
        List<? extends PaymentCommand> payments();
        default UUID sellerId() { return null; }
        default BigDecimal previousBalanceAmount() { return ZERO; }
        default LocalDate orderDate() { return null; }
        /** Payments of older debts made while confirming: applied FIFO to previous sales, never to this order. */
        default List<? extends PaymentCommand> previousDebtPayments() { return List.of(); }
    }

    public interface EditCommand {
        UUID priceListId();
        List<? extends LineCommand> lines();
        BigDecimal orderDiscountPercent();
        default BigDecimal previousBalanceAmount() { return null; }
    }

    public record ConfirmationData(String idempotencyKey, UUID customerId, UUID priceListId,
                                   List<? extends LineCommand> lines, BigDecimal orderDiscountPercent,
                                   List<? extends PaymentCommand> payments) implements ConfirmationCommand { }

    public record CreditLimitWarningResult(BigDecimal creditLimit, BigDecimal projectedBalance, BigDecimal exceededBy) { }

    public record ConfirmationResult(UUID orderId, UUID saleId, String orderNumber, String saleNumber,
                                     BigDecimal total, BigDecimal paid, BigDecimal balance,
                                     CreditLimitWarningResult creditLimitWarning, BigDecimal previousBalanceAmount) {
        public ConfirmationResult(UUID orderId, UUID saleId, String orderNumber, String saleNumber,
                                  BigDecimal total, BigDecimal paid, BigDecimal balance, CreditLimitWarningResult warning) {
            this(orderId, saleId, orderNumber, saleNumber, total, paid, balance, warning, ZERO);
        }
    }

    public record EditResult(UUID orderId, UUID saleId, BigDecimal total, BigDecimal paid, BigDecimal balance) { }

    private final JdbcTemplate jdbc;
    private final PricingQueryService pricing;
    private final OrderCalculationService calculation;
    private final InventoryMovementService inventory;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;
    private final OutboxService outbox;
    private AccountPaymentService accountPayments;

    @Autowired(required = false)
    public void setAccountPayments(AccountPaymentService accountPayments) {
        this.accountPayments = accountPayments;
    }

    @Autowired
    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    OrderCalculationService calculation, InventoryMovementService inventory,
                                    AuditService audit, OutboxService outbox, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.calculation = calculation;
        this.inventory = inventory;
        this.audit = audit;
        this.currentUser = currentUser;
        this.outbox = outbox;
    }

    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    OrderCalculationService calculation,
                                    InventoryMovementService inventory, AuditService audit,
                                    CurrentUserAccess currentUser) {
        this(jdbc, pricing, calculation, inventory, audit, null, currentUser);
    }

    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    OrderCalculationService calculation,
                                    InventoryMovementService inventory, AuditService audit) {
        this(jdbc, pricing, calculation, inventory, audit, null, null);
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
            "select id, status, seller_id, balance, price_list_id from customer.customers where id = ?", customerId);
        if (!"ACTIVE".equals(customer.get("status"))) {
            throw new IllegalStateException("El cliente no está activo");
        }
        validatePriceList(request, customer);
        // The account that owes the sale. The recipient owes its own orders until resellers are introduced.
        UUID accountId = customerId;
        Map<String, Object> account = customer;
        BigDecimal previousDebtPaid = payPreviousDebts(accountId, request.previousDebtPayments());
        // Paying older debts lowers the balance this order starts from (credit limit and previous balance checks).
        BigDecimal balanceBeforeOrder = previousDebtPaid.signum() > 0
            ? decimalOrZero(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, accountId))
            : decimalOrZero(account.get("balance"));
        BigDecimal previousBalanceAmount = previousBalanceAmount(request);
        if (previousBalanceAmount.signum() > 0) {
            BigDecimal currentBalance = jdbc.queryForObject(
                "select balance from customer.customers where id = ? for update", BigDecimal.class, accountId);
            // The remito may include debt paid in this same confirmation: compare with the balance before paying it.
            if (currentBalance == null || previousBalanceAmount.compareTo(currentBalance.add(previousDebtPaid)) > 0) {
                throw new IllegalArgumentException("El importe del saldo anterior supera el saldo actual del cliente");
            }
            balanceBeforeOrder = currentBalance;
        }
        UUID sellerId = resolveSellerId(customer, request.sellerId());
        if (sellerId != null) {
            boolean sellerExists = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from seller.seller_profiles where id = ?)", Boolean.class, sellerId));
            if (!sellerExists) throw new org.springframework.dao.EmptyResultDataAccessException(1);
        }

        List<ResolvedLine> resolved = resolveLines(request);
        resolved.stream().map(ResolvedLine::productId).distinct().forEach(this::requireActiveProduct);
        OrderCalculationService.OrderCalculation calculated = calculation.calculate(
            resolved.stream().map(line -> new OrderCalculationService.CalculatedLine(
                line.productId(), line.quantity(), line.unitPrice(), line.discount())).toList(),
            request.orderDiscountPercent(), request.payments());

        BigDecimal monetaryPaid = monetaryPaid(request.payments());
        List<BigDecimal> accountDebits = accountDebits(request.payments(), calculated.total(), monetaryPaid);
        BigDecimal totalAccountDebit = accountDebits.stream().reduce(ZERO, BigDecimal::add).setScale(4);
        BigDecimal projectedBalance = balanceBeforeOrder.add(totalAccountDebit).setScale(4);
        BigDecimal creditLimit = jdbc.queryForObject(
            "select credit_limit from app.business_settings where id = 1", BigDecimal.class);
        boolean creditLimitExceeded = creditLimit != null && projectedBalance.compareTo(creditLimit) > 0;
        CreditLimitWarningResult creditWarning = creditLimitExceeded
            ? new CreditLimitWarningResult(creditLimit, projectedBalance,
                projectedBalance.subtract(creditLimit).setScale(4)) : null;

        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        String orderNumber = jdbc.queryForObject("select orders.next_customer_order_number(?)", String.class, customerId);
        Timestamp now = Timestamp.from(Instant.now());
        var businessNow = now.toInstant().atZone(ZoneId.of("America/Argentina/Buenos_Aires"));
        Timestamp registeredAt = request.orderDate() == null ? now : Timestamp.from(
            request.orderDate().atTime(businessNow.toLocalTime()).atZone(businessNow.getZone()).toInstant());
        String saleNumber = number("SAL");
        jdbc.update("insert into orders.orders(id, order_number, customer_id, seller_id, status, subtotal, discount, total, created_at, idempotency_key, idempotency_fingerprint, credit_limit_exceeded, credit_limit_snapshot, projected_balance_snapshot, order_discount_percent, previous_balance_amount, created_by_user_id, created_by_admin) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            orderId, orderNumber, customerId, sellerId, calculated.subtotal(),
            calculated.lineDiscount().add(calculated.orderDiscount()), calculated.total(), registeredAt,
            request.idempotencyKey(), fingerprint, creditLimitExceeded, creditLimit, projectedBalance,
            calculated.orderDiscountPercent(), previousBalanceAmount, actorId(), hasAuthority("ADMIN_ALL"));
        insertItems("orders.order_items", orderId, resolved, calculated.lines());

        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, order_discount_percent, account_customer_id) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?)",
            saleId, saleNumber, orderId, customerId, calculated.total(), monetaryPaid, registeredAt,
            calculated.orderDiscountPercent(), accountId);
        insertItems("sale.sale_items", saleId, resolved, calculated.lines());

        AccountLedger ledger = new AccountLedger(jdbc);
        insertPayments(ledger, saleId, accountId, request.payments());
        for (BigDecimal accountDebit : accountDebits) {
            ledger.debit(accountId, saleId, accountDebit, now);
        }
        if (totalAccountDebit.signum() > 0) {
            ledger.increaseBalance(accountId, totalAccountDebit);
        }

        BigDecimal responsePaid = monetaryPaid(request.payments());
        ConfirmationResult response = new ConfirmationResult(
            orderId, saleId, orderNumber, saleNumber, calculated.total(), responsePaid,
            calculated.total().subtract(responsePaid).setScale(4), creditWarning, previousBalanceAmount);
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("saleId", saleId.toString());
        auditDetails.put("total", calculated.total());
        auditDetails.put("paid", responsePaid);
        auditDetails.put("balance", response.balance());
        auditDetails.put("previousBalanceAmount", previousBalanceAmount);
        if (previousDebtPaid.signum() > 0) auditDetails.put("previousDebtPaid", previousDebtPaid);
        auditDetails.put("orderDate", registeredAt.toInstant().atZone(businessNow.getZone()).toLocalDate().toString());
        auditDetails.put("creditLimit", creditLimit);
        auditDetails.put("projectedBalance", projectedBalance);
        auditDetails.put("creditLimitExceeded", creditLimitExceeded);
        audit.recordWithinTransaction(actorId(), "ORDER_CONFIRM", "ORDER", orderId.toString(), "SUCCESS", auditDetails);
        if (creditWarning != null) {
            audit.recordWithinTransaction(actorId(), "CREDIT_LIMIT_WARNING", "CUSTOMER", accountId.toString(), "SUCCESS",
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
        PermissionAccess.require("ORDER_EDIT");
        if (orderId == null || request == null || request.lines() == null || request.lines().isEmpty()
            || request.orderDiscountPercent() == null) {
            throw new IllegalArgumentException("Los campos obligatorios son inválidos");
        }

        Map<String, Object> lifecycle = jdbc.queryForMap("select o.id as order_id, o.status as order_status, o.total as order_total, "
            + "s.id as sale_id, s.status as sale_status, s.customer_id, s.account_customer_id, s.total as sale_total, s.paid, o.previous_balance_amount, o.fulfillment_status, o.order_discount_percent "
            + "from orders.orders o join sale.sales s on s.order_id = o.id where o.id = ? for update of o, s", orderId);
        if (currentUser != null) currentUser.requireOrderAccess(orderId);
        if (!"CONFIRMED".equals(lifecycle.get("order_status")) || !"CONFIRMED".equals(lifecycle.get("sale_status"))) {
            throw new IllegalStateException("Solo se pueden editar pedidos y ventas confirmados");
        }

        // The recipient keeps the order's prices; the account that owes the sale absorbs the debt change.
        UUID customerId = uuidOrNull(lifecycle.get("customer_id"));
        UUID accountId = uuidOrNull(lifecycle.get("account_customer_id"));
        AccountLedger ledger = new AccountLedger(jdbc);
        Map<String, Object> account = ledger.lockAccount(accountId);
        BigDecimal previousBalance = request.previousBalanceAmount() == null
            ? decimal(lifecycle.get("previous_balance_amount")) : request.previousBalanceAmount();
        validateScale(previousBalance, "previousBalanceAmount");
        if (previousBalance.signum() < 0) {
            throw new IllegalArgumentException("El importe del saldo anterior no puede ser negativo");
        }
        if (previousBalance.compareTo(decimal(lifecycle.get("previous_balance_amount"))) != 0
            && previousBalance.compareTo(decimal(account.get("balance")).max(ZERO)) > 0) {
            throw new IllegalStateException("El importe del saldo anterior supera el saldo actual del cliente");
        }
        ConfirmationCommand pricingRequest = new ConfirmationData(
            "confirmed-order-edit", customerId, request.priceListId(), request.lines(), request.orderDiscountPercent(), List.of());
        validateRequest(pricingRequest);
        List<ResolvedLine> resolved = resolveLines(pricingRequest);
        validateEditOverrides(orderId, pricingRequest, resolved, lifecycle, customerId);
        OrderCalculationService.OrderCalculation calculated = calculation.calculate(
            resolved.stream().map(line -> new OrderCalculationService.CalculatedLine(
                line.productId(), line.quantity(), line.unitPrice(), line.discount())).toList(),
            request.orderDiscountPercent());

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
            if (stockDelta.signum() < 0) requireActiveProduct(productId);
            if (!"PREPARED".equals(lifecycle.get("fulfillment_status"))) continue;
            if (stockDelta.signum() < 0) {
                inventory.apply(productId, stockDelta, "SALE", orderId, "Confirmed order edit");
                inventoryMovementsApplied++;
            } else if (stockDelta.signum() > 0) {
                inventory.apply(productId, stockDelta, "SALE_CANCELLATION", orderId, "Confirmed order edit");
                inventoryMovementsApplied++;
            }
        }

        BigDecimal currentAccountDebt = ledger.saleBalance(saleId);
        BigDecimal previousTotal = decimal(lifecycle.get("sale_total")).setScale(4);
        BigDecimal targetAccountDebt = currentAccountDebt.add(calculated.total().subtract(previousTotal)).setScale(4);
        BigDecimal ledgerAdjustment = targetAccountDebt.subtract(currentAccountDebt).setScale(4);
        Timestamp now = Timestamp.from(Instant.now());
        if (ledgerAdjustment.signum() > 0) {
            ledger.debit(accountId, saleId, ledgerAdjustment, now);
            ledger.increaseBalance(accountId, ledgerAdjustment);
        } else if (ledgerAdjustment.signum() < 0) {
            ledger.credit(accountId, saleId, ledgerAdjustment.negate(), now);
            ledger.decreaseBalance(accountId, ledgerAdjustment.negate());
        }

        jdbc.update("delete from orders.order_items where order_id = ?", orderId);
        jdbc.update("delete from sale.sale_items where sale_id = ?", saleId);
        insertItems("orders.order_items", orderId, resolved, calculated.lines());
        insertItems("sale.sale_items", saleId, resolved, calculated.lines());
        BigDecimal discount = calculated.lineDiscount().add(calculated.orderDiscount()).setScale(4);
        jdbc.update("update orders.orders set subtotal = ?, discount = ?, total = ?, order_discount_percent = ?, previous_balance_amount = ? where id = ?",
            calculated.subtotal(), discount, calculated.total(), calculated.orderDiscountPercent(),
            previousBalance, orderId);
        jdbc.update("update sale.sales set total = ?, order_discount_percent = ? where id = ?",
            calculated.total(), calculated.orderDiscountPercent(), saleId);

        audit.recordWithinTransaction(actorId(), "ORDER_EDIT", "ORDER", orderId.toString(), "SUCCESS",
            Map.of("saleId", saleId.toString(), "previousTotal", decimal(lifecycle.get("sale_total")),
                "total", calculated.total(), "paid", paid, "accountDebtAdjustment", ledgerAdjustment,
                "inventoryMovementsApplied", inventoryMovementsApplied, "previousBalanceAmount", previousBalance));
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

    private void requireActiveProduct(UUID productId) {
        String status = jdbc.queryForObject("select status from catalog.products where id = ?", String.class, productId);
        if (!"ACTIVE".equals(status)) throw new IllegalStateException("El producto no está activo");
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

    private void insertPayments(AccountLedger ledger, UUID saleId, UUID accountId, List<? extends PaymentCommand> payments) {
        if (payments == null) return;
        Timestamp now = Timestamp.from(Instant.now());
        for (var payment : payments) {
            if (!"CUSTOMER_ACCOUNT".equals(payment.method())) {
                ledger.recordPayment(accountId, saleId, payment.amount(), payment.method(),
                    "BANK_TRANSFER".equals(payment.method()) ? normalizeReference(payment.transferReference()) : null, now);
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
            existing.saleNumber(), existing.total(), existing.paid(), existing.total().subtract(existing.paid()).setScale(4), warning, previousBalanceAmount(request));
    }

    private void validatePriceList(ConfirmationCommand request, Map<String, Object> customer) {
        // The selected list must still be active; the pricing resolution rejects inactive lists.
        if (hasAuthority("ADMIN_ALL") || hasAuthority("ORDER_PRICE_LIST_CHANGE") || request.priceListId() == null) return;
        UUID assignedListId = (UUID) customer.get("price_list_id");
        if (assignedListId == null) {
            assignedListId = jdbc.queryForObject(
                "select id from catalog.price_lists where code = 'GENERAL' and status = 'ACTIVE'", UUID.class);
        }
        if (!request.priceListId().equals(assignedListId)) {
            throw new AccessDeniedException("ORDER_PRICE_LIST_CHANGE es requerido para cambiar la lista de precios del cliente");
        }
    }

    private void validateOverrides(ConfirmationCommand request) {
        if (request.lines().stream().anyMatch(line -> line.unitPriceOverride() != null))
            PermissionAccess.require("ORDER_PRICE_OVERRIDE");
        if (request.orderDiscountPercent().signum() != 0 || request.lines().stream().anyMatch(line -> line.lineDiscountPercent().signum() != 0))
            PermissionAccess.require("ORDER_DISCOUNT");
    }

    private void validateEditOverrides(UUID orderId, ConfirmationCommand request, List<ResolvedLine> resolved,
                                       Map<String, Object> lifecycle, UUID customerId) {
        boolean prices = PermissionAccess.allows("ORDER_PRICE_OVERRIDE");
        boolean discounts = PermissionAccess.allows("ORDER_DISCOUNT");
        boolean lists = PermissionAccess.allows("ORDER_PRICE_LIST_CHANGE");
        if (prices && discounts && lists) return;
        List<Map<String, Object>> snapshots = jdbc.queryForList(
            "select oi.product_id, coalesce(oi.price_list_id, pl.id) as price_list_id, oi.unit_price, oi.line_discount_percent "
                + "from orders.order_items oi left join catalog.price_lists pl on pl.code = oi.price_list_code where oi.order_id = ?", orderId);
        if (!lists) {
            // The recipient's assigned list, as in the original order.
            UUID selectedList = request.priceListId() != null ? request.priceListId() : jdbc.queryForObject(
                "select price_list_id from customer.customers where id = ?", UUID.class, customerId);
            if (selectedList == null) selectedList = jdbc.queryForObject("select id from catalog.price_lists where code = 'GENERAL' and status = 'ACTIVE'", UUID.class);
            UUID targetList = selectedList;
            if (snapshots.stream().anyMatch(row -> !targetList.equals(uuidOrNull(row.get("price_list_id")))))
                throw new AccessDeniedException("Se requiere ORDER_PRICE_LIST_CHANGE para cambiar la lista del pedido");
        }
        if (!discounts && request.orderDiscountPercent().compareTo(decimal(lifecycle.get("order_discount_percent"))) != 0)
            throw new AccessDeniedException("Se requiere ORDER_DISCOUNT para modificar descuentos");
        for (int index = 0; index < request.lines().size(); index++) {
            LineCommand line = request.lines().get(index);
            UUID resolvedList = resolved.get(index).priceListId();
            List<Map<String, Object>> previous = snapshots.stream()
                .filter(row -> line.productId().equals(uuidOrNull(row.get("product_id")))).toList();
            // Quantity edits retain historical prices and discounts, without granting the ability to change them.
            boolean preserved = previous.stream().anyMatch(row ->
                (prices || line.unitPriceOverride() == null || (resolvedList.equals(uuidOrNull(row.get("price_list_id")))
                    && line.unitPriceOverride().compareTo(decimal(row.get("unit_price"))) == 0))
                && (discounts || line.lineDiscountPercent().compareTo(decimal(row.get("line_discount_percent"))) == 0));
            boolean standardNewLine = previous.isEmpty() && (prices || line.unitPriceOverride() == null)
                && (discounts || line.lineDiscountPercent().signum() == 0);
            if (!preserved && !standardNewLine) throw new AccessDeniedException("Se requieren permisos para modificar precios o descuentos");
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
        if (request.orderDate() != null && (request.orderDate().getYear() < 1 || request.orderDate().getYear() > 9999)) {
            throw new IllegalArgumentException("orderDate debe ser una fecha válida entre los años 0001 y 9999");
        }
        if (request.previousBalanceAmount() != null) {
            if (request.previousBalanceAmount().signum() < 0) throw new IllegalArgumentException("El importe del saldo anterior no puede ser negativo");
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
            for (PaymentCommand payment : request.payments()) {
                if (payment == null || payment.method() == null || !Set.of("CASH", "BANK_TRANSFER", "CUSTOMER_ACCOUNT").contains(payment.method())) {
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
            if (request.previousDebtPayments().size() > 20) throw new IllegalArgumentException("Se admiten hasta 20 pagos de deuda anterior");
            for (PaymentCommand payment : request.previousDebtPayments()) {
                if (payment == null || payment.method() == null || !Set.of("CASH", "BANK_TRANSFER").contains(payment.method())) {
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

    private void validateReference(PaymentCommand payment) {
        if (payment.transferReference() != null && !payment.transferReference().isBlank() && !"BANK_TRANSFER".equals(payment.method())) {
            throw new IllegalArgumentException("transferReference requiere method BANK_TRANSFER");
        }
        normalizeReference(payment.transferReference());
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
        if (request.orderDate() != null) {
            append(canonical, "orderDate");
            append(canonical, request.orderDate());
        }
        if (previousBalanceAmount(request).signum() > 0) {
            append(canonical, "previousBalanceAmount");
            append(canonical, previousBalanceAmount(request));
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
        return hex(sha256(canonical.toString()));
    }

    /**
     * Applies the payments of older debts through the account payment flow (same locks, seller scope, ledger
     * credits and audit), before the new sale exists, so they can never be allocated to this order.
     */
    private BigDecimal payPreviousDebts(UUID customerId, List<? extends PaymentCommand> payments) {
        if (payments == null || payments.isEmpty()) return ZERO;
        if (accountPayments == null) throw new IllegalStateException("El pago de deuda anterior no está disponible");
        BigDecimal paid = ZERO;
        for (PaymentCommand payment : payments) {
            accountPayments.apply(customerId, new PreviousDebtPayment(payment.amount().setScale(4), payment.method(),
                "BANK_TRANSFER".equals(payment.method()) ? normalizeReference(payment.transferReference()) : null));
            paid = paid.add(payment.amount());
        }
        return paid.setScale(4);
    }

    private record PreviousDebtPayment(BigDecimal amount, String method, String transferReference)
        implements AccountPaymentService.PaymentCommand {
        @Override public UUID saleId() { return null; }
    }

    private static String normalizeReference(String reference) {
        if (reference == null || reference.isBlank()) return null;
        if (reference.trim().length() > 100) throw new IllegalArgumentException("transferReference no puede superar 100 caracteres");
        return reference.trim();
    }

    private BigDecimal previousBalanceAmount(ConfirmationCommand request) {
        return request.previousBalanceAmount() == null ? ZERO : request.previousBalanceAmount().setScale(4);
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
                                 BigDecimal total, BigDecimal paid, String fingerprint, boolean creditLimitExceeded,
                                 BigDecimal creditLimit, BigDecimal projectedBalance) { }
}
