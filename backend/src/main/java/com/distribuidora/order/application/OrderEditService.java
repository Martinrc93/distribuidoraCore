package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.application.OrderConfirmationService.ConfirmationCommand;
import com.distribuidora.order.application.OrderConfirmationService.ConfirmationData;
import com.distribuidora.order.application.OrderConfirmationService.EditCommand;
import com.distribuidora.order.application.OrderConfirmationService.EditResult;
import com.distribuidora.order.application.OrderConfirmationService.LineCommand;
import com.distribuidora.order.application.OrderLineSupport.ResolvedLine;
import com.distribuidora.payment.application.AccountLedger;
import com.distribuidora.payment.application.CustomerAccountBalanceQuery;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.security.PermissionAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class OrderEditService {
    private static final BigDecimal ZERO = OrderLineSupport.ZERO;

    private final JdbcTemplate jdbc;
    private final PricingQueryService pricing;
    private final OrderCalculationService calculation;
    private final InventoryMovementService inventory;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public OrderEditService(JdbcTemplate jdbc, PricingQueryService pricing,
                            OrderCalculationService calculation, InventoryMovementService inventory,
                            AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.pricing = Objects.requireNonNull(pricing, "pricing");
        this.calculation = Objects.requireNonNull(calculation, "calculation");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
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
        currentUser.requireOrderAccess(orderId);
        if (!"CONFIRMED".equals(lifecycle.get("order_status")) || !"CONFIRMED".equals(lifecycle.get("sale_status"))) {
            throw new IllegalStateException("Solo se pueden editar pedidos y ventas confirmados");
        }

        // The recipient keeps the order's prices; the account that owes the sale absorbs the debt change.
        UUID saleId = OrderLineSupport.uuidOrNull(lifecycle.get("sale_id"));
        UUID customerId = OrderLineSupport.uuidOrNull(lifecycle.get("customer_id"));
        UUID accountId = OrderLineSupport.uuidOrNull(lifecycle.get("account_customer_id"));
        AccountLedger ledger = new AccountLedger(jdbc);
        Map<String, Object> account = ledger.lockAccount(accountId);
        BigDecimal currentAccountDebt = saleId == null ? ZERO : OrderLineSupport.decimalOrZero(ledger.saleBalance(saleId)).setScale(4);
        BigDecimal previousBalance = request.previousBalanceAmount() == null
            ? OrderLineSupport.decimal(lifecycle.get("previous_balance_amount")) : request.previousBalanceAmount();
        OrderLineSupport.validateScale(previousBalance, "previousBalanceAmount");
        if (previousBalance.signum() < 0) {
            throw new IllegalArgumentException("El importe del saldo anterior no puede ser negativo");
        }
        if (!accountId.equals(customerId) && previousBalance.signum() > 0) {
            throw new IllegalArgumentException("El saldo anterior del remito no está disponible para pedidos a cuenta de un revendedor");
        }
        if (previousBalance.compareTo(OrderLineSupport.decimal(lifecycle.get("previous_balance_amount"))) != 0) {
            BigDecimal maxPreviousBalance = currentUser.isAdmin()
                ? OrderLineSupport.decimalOrZero(account.get("balance")).subtract(currentAccountDebt.max(ZERO)).max(ZERO)
                : new CustomerAccountBalanceQuery(jdbc, currentUser).balanceExcludingSale(accountId, saleId).max(ZERO);
            if (previousBalance.compareTo(maxPreviousBalance) > 0) {
                throw new IllegalStateException("El importe del saldo anterior supera el saldo actual del cliente");
            }
        }
        ConfirmationCommand pricingRequest = new ConfirmationData(
            "confirmed-order-edit", customerId, request.priceListId(), request.lines(), request.orderDiscountPercent(), List.of());
        OrderLineSupport.validateRequest(pricingRequest);
        List<ResolvedLine> resolved = OrderLineSupport.resolveLines(jdbc, pricing, pricingRequest);
        validateEditOverrides(orderId, pricingRequest, resolved, lifecycle, customerId);
        OrderCalculationService.OrderCalculation calculated = calculation.calculate(
            resolved.stream().map(line -> new OrderCalculationService.CalculatedLine(
                line.productId(), line.quantity(), line.unitPrice(), line.discount())).toList(),
            request.orderDiscountPercent());

        BigDecimal paid = OrderLineSupport.decimal(lifecycle.get("paid")).setScale(4);
        if (calculated.total().compareTo(paid) < 0) {
            throw new IllegalStateException("El nuevo total no puede ser menor que el importe ya pagado");
        }

        Map<UUID, String> productStatuses = new HashMap<>();
        resolved.forEach(line -> productStatuses.put(line.productId(), line.productStatus()));
        Map<UUID, BigDecimal> oldQuantities = quantities("orders.order_items", "order_id", orderId);
        Map<UUID, BigDecimal> newQuantities = new HashMap<>();
        resolved.forEach(line -> newQuantities.merge(line.productId(), line.quantity(), BigDecimal::add));
        Set<UUID> productIds = new HashSet<>(oldQuantities.keySet());
        productIds.addAll(newQuantities.keySet());
        int inventoryMovementsApplied = 0;
        for (UUID productId : productIds.stream().sorted(Comparator.comparing(UUID::toString)).toList()) {
            BigDecimal stockDelta = oldQuantities.getOrDefault(productId, ZERO)
                .subtract(newQuantities.getOrDefault(productId, ZERO));
            if (stockDelta.signum() < 0 && !"ACTIVE".equals(productStatuses.get(productId))) {
                throw new IllegalStateException("El producto no está activo");
            }
            if (!"PREPARED".equals(lifecycle.get("fulfillment_status"))) continue;
            if (stockDelta.signum() < 0) {
                inventory.apply(productId, stockDelta, "SALE", orderId, "Confirmed order edit");
                inventoryMovementsApplied++;
            } else if (stockDelta.signum() > 0) {
                inventory.apply(productId, stockDelta, "SALE_CANCELLATION", orderId, "Confirmed order edit");
                inventoryMovementsApplied++;
            }
        }

        BigDecimal previousTotal = OrderLineSupport.decimal(lifecycle.get("sale_total")).setScale(4);
        BigDecimal targetAccountDebt = currentAccountDebt.add(calculated.total().subtract(previousTotal)).setScale(4);
        BigDecimal ledgerAdjustment = targetAccountDebt.subtract(currentAccountDebt).setScale(4);
        Timestamp now = Timestamp.from(Instant.now());
        if (ledgerAdjustment.signum() > 0) {
            ledger.debit(accountId, saleId, ledgerAdjustment, now);
            ledger.increaseBalance(accountId, ledgerAdjustment);
        } else if (ledgerAdjustment.signum() < 0) {
            BigDecimal maxCredit = currentAccountDebt.max(ZERO).min(OrderLineSupport.decimalOrZero(account.get("balance")).max(ZERO));
            BigDecimal actualCredit = ledgerAdjustment.negate().min(maxCredit).setScale(4);
            if (actualCredit.signum() > 0) {
                ledger.credit(accountId, saleId, actualCredit, now);
                ledger.decreaseBalance(accountId, actualCredit);
            }
            ledgerAdjustment = actualCredit.negate();
            targetAccountDebt = currentAccountDebt.add(ledgerAdjustment).setScale(4);
        }

        jdbc.update("delete from orders.order_items where order_id = ?", orderId);
        jdbc.update("delete from sale.sale_items where sale_id = ?", saleId);
        OrderLineSupport.insertItems(jdbc, "orders.order_items", orderId, resolved, calculated.lines());
        OrderLineSupport.insertItems(jdbc, "sale.sale_items", saleId, resolved, calculated.lines());
        BigDecimal discount = calculated.lineDiscount().add(calculated.orderDiscount()).setScale(4);
        jdbc.update("update orders.orders set subtotal = ?, discount = ?, total = ?, order_discount_percent = ?, previous_balance_amount = ? where id = ?",
            calculated.subtotal(), discount, calculated.total(), calculated.orderDiscountPercent(),
            previousBalance, orderId);
        jdbc.update("update sale.sales set total = ?, order_discount_percent = ? where id = ?",
            calculated.total(), calculated.orderDiscountPercent(), saleId);

        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "ORDER_EDIT", "ORDER", orderId.toString(), "SUCCESS",
            Map.of("saleId", saleId.toString(), "previousTotal", OrderLineSupport.decimal(lifecycle.get("sale_total")),
                "total", calculated.total(), "paid", paid, "accountDebtAdjustment", ledgerAdjustment,
                "inventoryMovementsApplied", inventoryMovementsApplied, "previousBalanceAmount", previousBalance));
        return new EditResult(orderId, saleId, calculated.total(), paid, targetAccountDebt);
    }

    private Map<UUID, BigDecimal> quantities(String table, String parentColumn, UUID parentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "select product_id, sum(quantity) as quantity from " + table + " where " + parentColumn + " = ? group by product_id", parentId);
        Map<UUID, BigDecimal> quantities = new HashMap<>();
        rows.forEach(row -> quantities.put(OrderLineSupport.uuidOrNull(row.get("product_id")), OrderLineSupport.decimal(row.get("quantity"))));
        return quantities;
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
            UUID selectedList = request.priceListId() != null ? request.priceListId() : jdbc.queryForObject(
                "select price_list_id from customer.customers where id = ?", UUID.class, customerId);
            UUID defaultList = jdbc.queryForObject(
                "select id from catalog.price_lists where is_default = true and status = 'ACTIVE'", UUID.class);
            if (selectedList == null) selectedList = defaultList;
            UUID targetList = selectedList;
            if (snapshots.stream().anyMatch(row -> {
                UUID rowList = OrderLineSupport.uuidOrNull(row.get("price_list_id"));
                return !targetList.equals(rowList) && (defaultList == null || !defaultList.equals(rowList));
            })) {
                throw new AccessDeniedException("Se requiere ORDER_PRICE_LIST_CHANGE para cambiar la lista del pedido");
            }
        }
        if (!discounts && request.orderDiscountPercent().compareTo(OrderLineSupport.decimal(lifecycle.get("order_discount_percent"))) != 0) {
            throw new AccessDeniedException("Se requiere ORDER_DISCOUNT para modificar descuentos");
        }
        List<Map<String, Object>> remainingSnapshots = new ArrayList<>(snapshots);
        for (int index = 0; index < request.lines().size(); index++) {
            LineCommand line = request.lines().get(index);
            UUID resolvedList = resolved.get(index).priceListId();
            boolean matchedSnapshot = false;
            for (int i = 0; i < remainingSnapshots.size(); i++) {
                Map<String, Object> row = remainingSnapshots.get(i);
                if (!line.productId().equals(OrderLineSupport.uuidOrNull(row.get("product_id")))) continue;
                boolean preserved = (prices || line.unitPriceOverride() == null || (resolvedList.equals(OrderLineSupport.uuidOrNull(row.get("price_list_id")))
                        && line.unitPriceOverride().compareTo(OrderLineSupport.decimal(row.get("unit_price"))) == 0))
                    && (discounts || line.lineDiscountPercent().compareTo(OrderLineSupport.decimal(row.get("line_discount_percent"))) == 0);
                if (preserved) {
                    remainingSnapshots.remove(i);
                    matchedSnapshot = true;
                    break;
                }
            }
            if (matchedSnapshot) continue;
            boolean standardNewLine = (prices || line.unitPriceOverride() == null)
                && (discounts || line.lineDiscountPercent().signum() == 0);
            if (!standardNewLine) throw new AccessDeniedException("Se requieren permisos para modificar precios o descuentos");
        }
    }
}
