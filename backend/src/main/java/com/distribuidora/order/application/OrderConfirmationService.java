package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.notification.application.OutboxService;
import com.distribuidora.order.application.OrderLineSupport.ResolvedLine;
import com.distribuidora.payment.application.AccountLedger;
import com.distribuidora.payment.application.AccountPaymentService;
import com.distribuidora.payment.application.CustomerAccountBalanceQuery;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.security.PermissionAccess;
import com.distribuidora.shared.time.BusinessTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class OrderConfirmationService {
    private static final BigDecimal ZERO = OrderLineSupport.ZERO;

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
    private final AuditService audit;
    private final CurrentUserAccess currentUser;
    private final OutboxService outbox;
    private final OrderEditService editService;
    private AccountPaymentService accountPayments;

    @Autowired(required = false)
    public void setAccountPayments(AccountPaymentService accountPayments) {
        this.accountPayments = accountPayments;
    }

    @Autowired
    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    OrderCalculationService calculation, InventoryMovementService inventory,
                                    AuditService audit, OutboxService outbox, CurrentUserAccess currentUser,
                                    OrderEditService editService) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.calculation = Objects.requireNonNull(calculation, "calculation");
        Objects.requireNonNull(inventory, "inventory");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        this.outbox = outbox;
        this.editService = Objects.requireNonNull(editService, "editService");
    }

    public OrderConfirmationService(JdbcTemplate jdbc, PricingQueryService pricing,
                                    OrderCalculationService calculation,
                                    InventoryMovementService inventory, AuditService audit,
                                    CurrentUserAccess currentUser) {
        this(jdbc, pricing, calculation, inventory, audit, null, currentUser,
            new OrderEditService(jdbc, pricing, calculation, inventory, audit, currentUser));
    }

    @Transactional
    public ConfirmationResult confirm(ConfirmationCommand request) {
        OrderLineSupport.validateRequest(request);
        validateOverrides(request);
        UUID customerId = request.customerId();
        currentUser.requireCustomerAccess(customerId);
        String fingerprint = OrderIdempotencySupport.fingerprint(request, OrderLineSupport.previousBalanceAmount(request));
        OrderIdempotencySupport.lock(jdbc, request.idempotencyKey());
        ConfirmationResult existing = findExisting(request, fingerprint);
        if (existing != null) {
            return existing;
        }
        UUID hintedResellerId = jdbc.queryForObject(
            "select reseller_id from customer.customers where id = ?", UUID.class, customerId);
        boolean hasPrevPayments = request.previousDebtPayments() != null && !request.previousDebtPayments().isEmpty();
        UUID provisionalAccountId = hintedResellerId == null ? customerId : hintedResellerId;
        List<Map<String, Object>> lockedCandidates = List.of();
        if (hasPrevPayments) {
            if (accountPayments == null) throw new IllegalStateException("El pago de deuda anterior no está disponible");
            lockedCandidates = accountPayments.lockCandidateSalesForAccount(provisionalAccountId);
        }
        Map<String, Object> customer;
        Map<String, Object> account;
        if (hintedResellerId == null || hintedResellerId.equals(customerId)) {
            customer = jdbc.queryForMap(
                "select id, status, seller_id, balance, price_list_id, kind, reseller_id from customer.customers where id = ? for update",
                customerId);
            account = customer;
        } else {
            boolean customerFirst = customerId.toString().compareTo(hintedResellerId.toString()) <= 0;
            if (customerFirst) {
                customer = jdbc.queryForMap(
                    "select id, status, seller_id, balance, price_list_id, kind, reseller_id from customer.customers where id = ? for update",
                    customerId);
                account = jdbc.queryForMap(
                    "select id, status, seller_id, balance, price_list_id, kind, reseller_id from customer.customers where id = ? for update",
                    hintedResellerId);
            } else {
                account = jdbc.queryForMap(
                    "select id, status, seller_id, balance, price_list_id, kind, reseller_id from customer.customers where id = ? for update",
                    hintedResellerId);
                customer = jdbc.queryForMap(
                    "select id, status, seller_id, balance, price_list_id, kind, reseller_id from customer.customers where id = ? for update",
                    customerId);
            }
        }
        if (!Objects.equals(hintedResellerId, OrderLineSupport.uuidOrNull(customer.get("reseller_id")))) {
            throw new IllegalStateException("El revendedor del cliente cambió. Volvé a confirmar el pedido");
        }
        if (!"ACTIVE".equals(customer.get("status"))) {
            throw new IllegalStateException("El cliente no está activo");
        }
        validatePriceList(request, customer);
        // The account that owes the sale: the customer's reseller, or the customer itself.
        UUID resellerId = hintedResellerId;
        UUID accountId = resellerId == null ? customerId : resellerId;
        if (!"ACTIVE".equals(account.get("status"))) {
            throw new IllegalStateException("La cuenta del revendedor no está activa");
        }
        boolean collects = monetaryPaid(request.payments()).signum() > 0 || hasPrevPayments;
        if (collects && "RESELLER".equals(account.get("kind")) && !hasAuthority("ADMIN_ALL")) {
            throw new AccessDeniedException("Los cobros de cuentas de revendedor los registra un administrador");
        }
        // The delivery note goes to the customer, who owes nothing to the distributor for a reseller's order.
        BigDecimal previousBalanceAmount = OrderLineSupport.previousBalanceAmount(request);
        if (resellerId != null && previousBalanceAmount.signum() > 0) {
            throw new IllegalArgumentException("El saldo anterior del remito no está disponible para pedidos a cuenta de un revendedor");
        }
        BigDecimal previousDebtPaid = hasPrevPayments
            ? accountPayments.applyPreviousDebtPaymentsWithLockedContext(
                accountId,
                request.previousDebtPayments().stream()
                    .<AccountPaymentService.PaymentCommand>map(payment -> new AccountPaymentService.PaymentCommand() {
                        @Override public BigDecimal amount() { return payment.amount(); }
                        @Override public String method() { return payment.method(); }
                        @Override public String transferReference() { return payment.transferReference(); }
                        @Override public UUID saleId() { return null; }
                    })
                    .toList(),
                lockedCandidates, account)
            : ZERO;
        // Paying older debts lowers the balance this order starts from (credit limit and previous balance checks).
        BigDecimal balanceBeforeOrder = previousDebtPaid.signum() > 0
            ? OrderLineSupport.decimalOrZero(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, accountId))
            : OrderLineSupport.decimalOrZero(account.get("balance"));
        if (previousBalanceAmount.signum() > 0) {
            BigDecimal availablePreviousBalance = currentUser.isAdmin()
                ? balanceBeforeOrder.max(ZERO).add(previousDebtPaid)
                : new CustomerAccountBalanceQuery(jdbc, currentUser).balance(accountId).max(ZERO).add(previousDebtPaid);
            if (previousBalanceAmount.compareTo(availablePreviousBalance) > 0) {
                throw new IllegalArgumentException("El importe del saldo anterior supera el saldo actual del cliente");
            }
        }
        UUID sellerId = resolveSellerId(customer, request.sellerId());
        if (sellerId != null) {
            String sellerStatus;
            try {
                sellerStatus = jdbc.queryForObject(
                    "select status from seller.seller_profiles where id = ? for share", String.class, sellerId);
            } catch (EmptyResultDataAccessException missing) {
                throw new EmptyResultDataAccessException(1);
            }
            if (!"ACTIVE".equals(sellerStatus)) {
                throw new IllegalStateException("El vendedor asignado no está activo");
            }
        }

        List<ResolvedLine> resolved = OrderLineSupport.resolveLines(jdbc, pricing, request);
        for (ResolvedLine line : resolved) {
            if (!"ACTIVE".equals(line.productStatus())) {
                throw new IllegalStateException("El producto no está activo");
            }
        }
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
        // Numbering locks the customer row, so a reseller change cannot slip in between this check and the sale.
        if (!Objects.equals(resellerId, jdbc.queryForObject(
                "select reseller_id from customer.customers where id = ?", UUID.class, customerId))) {
            throw new IllegalStateException("El revendedor del cliente cambió. Volvé a confirmar el pedido");
        }
        Timestamp now = Timestamp.from(Instant.now());
        var businessNow = now.toInstant().atZone(BusinessTime.ZONE);
        Timestamp registeredAt = request.orderDate() == null ? now : Timestamp.from(
            request.orderDate().atTime(businessNow.toLocalTime()).atZone(businessNow.getZone()).toInstant());
        String saleNumber = orderNumber;
        String accountKind = "RESELLER".equals(account.get("kind")) ? "RESELLER" : "REGULAR";
        UUID auditActor = currentUser.actorIdOrSystem();
        jdbc.update("insert into orders.orders(id, order_number, customer_id, seller_id, status, subtotal, discount, total, created_at, idempotency_key, idempotency_fingerprint, credit_limit_exceeded, credit_limit_snapshot, projected_balance_snapshot, order_discount_percent, previous_balance_amount, created_by_user_id, created_by_admin) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            orderId, orderNumber, customerId, sellerId, calculated.subtotal(),
            calculated.lineDiscount().add(calculated.orderDiscount()), calculated.total(), registeredAt,
            request.idempotencyKey(), fingerprint, creditLimitExceeded, creditLimit, projectedBalance,
            calculated.orderDiscountPercent(), previousBalanceAmount, currentUser.actorIdOrNull(), hasAuthority("ADMIN_ALL"));
        OrderLineSupport.insertItems(jdbc, "orders.order_items", orderId, resolved, calculated.lines());

        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, order_discount_percent, account_customer_id, account_kind) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, ?, ?)",
            saleId, saleNumber, orderId, customerId, calculated.total(), monetaryPaid, registeredAt,
            calculated.orderDiscountPercent(), accountId, accountKind);
        OrderLineSupport.insertItems(jdbc, "sale.sale_items", saleId, resolved, calculated.lines());

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
        if (resellerId != null) auditDetails.put("accountCustomerId", accountId.toString());
        auditDetails.put("orderDate", registeredAt.toInstant().atZone(businessNow.getZone()).toLocalDate().toString());
        auditDetails.put("creditLimit", creditLimit);
        auditDetails.put("projectedBalance", projectedBalance);
        auditDetails.put("creditLimitExceeded", creditLimitExceeded);
        audit.recordWithinTransaction(auditActor, "ORDER_CONFIRM", "ORDER", orderId.toString(), "SUCCESS", auditDetails);
        if (creditWarning != null) {
            audit.recordWithinTransaction(auditActor, "CREDIT_LIMIT_WARNING", "CUSTOMER", accountId.toString(), "SUCCESS",
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
        return editService.editConfirmed(orderId, request);
    }

    private UUID resolveSellerId(Map<String, Object> customer, UUID requestedSellerId) {
        if (!currentUser.isAdmin()) {
            UUID authenticatedSellerId = currentUser.requireSellerProfile();
            if (requestedSellerId != null && !requestedSellerId.equals(authenticatedSellerId)) {
                throw new AccessDeniedException("No se puede cambiar el vendedor asignado al pedido");
            }
            return authenticatedSellerId;
        }
        return requestedSellerId != null ? requestedSellerId : OrderLineSupport.uuidOrNull(customer.get("seller_id"));
    }

    private void insertPayments(AccountLedger ledger, UUID saleId, UUID accountId, List<? extends PaymentCommand> payments) {
        if (payments == null) return;
        Timestamp now = Timestamp.from(Instant.now());
        for (var payment : payments) {
            if (!"CUSTOMER_ACCOUNT".equals(payment.method())) {
                ledger.recordPayment(accountId, saleId, payment.amount(), payment.method(),
                    "BANK_TRANSFER".equals(payment.method()) ? OrderLineSupport.normalizeReference(payment.transferReference()) : null, now);
            }
        }
    }

    private ConfirmationResult findExisting(ConfirmationCommand request, String fingerprint) {
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
        currentUser.requireOrderAccess(existing.orderId());
        if (!Objects.equals(existing.fingerprint(), fingerprint)) {
            throw new IdempotencyConflictException();
        }
        CreditLimitWarningResult warning = existing.creditLimitExceeded()
            ? new CreditLimitWarningResult(existing.creditLimit(), existing.projectedBalance(),
                existing.projectedBalance().subtract(existing.creditLimit()).setScale(4)) : null;
        return new ConfirmationResult(existing.orderId(), existing.saleId(), existing.orderNumber(),
            existing.saleNumber(), existing.total(), existing.paid(), existing.total().subtract(existing.paid()).setScale(4), warning,
            OrderLineSupport.previousBalanceAmount(request));
    }

    private void validatePriceList(ConfirmationCommand request, Map<String, Object> customer) {
        if (hasAuthority("ADMIN_ALL") || hasAuthority("ORDER_PRICE_LIST_CHANGE") || request.priceListId() == null) return;
        UUID assignedListId = (UUID) customer.get("price_list_id");
        if (assignedListId == null) {
            assignedListId = jdbc.queryForObject(
                "select id from catalog.price_lists where is_default = true and status = 'ACTIVE'", UUID.class);
        }
        if (!request.priceListId().equals(assignedListId)) {
            throw new AccessDeniedException("ORDER_PRICE_LIST_CHANGE es requerido para cambiar la lista de precios del cliente");
        }
    }

    private void validateOverrides(ConfirmationCommand request) {
        if (request.lines().stream().anyMatch(line -> line.unitPriceOverride() != null)) {
            PermissionAccess.require("ORDER_PRICE_OVERRIDE");
        }
        if (request.orderDiscountPercent().signum() != 0 || request.lines().stream().anyMatch(line -> line.lineDiscountPercent().signum() != 0)) {
            PermissionAccess.require("ORDER_DISCOUNT");
        }
        if (request.previousDebtPayments() != null && !request.previousDebtPayments().isEmpty()) {
            PermissionAccess.require("SALE_PAYMENT");
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

    private boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private record ExistingOrder(UUID orderId, UUID saleId, UUID customerId, String orderNumber, String saleNumber,
                                 BigDecimal total, BigDecimal paid, String fingerprint, boolean creditLimitExceeded,
                                 BigDecimal creditLimit, BigDecimal projectedBalance) { }
}
