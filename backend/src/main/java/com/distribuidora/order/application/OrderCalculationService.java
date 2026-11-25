package com.distribuidora.order.application;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
public class OrderCalculationService {
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");
    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public OrderCalculation calculate(List<CalculatedLine> lines, BigDecimal orderDiscountPercent) {
        validateDiscount(orderDiscountPercent, "orderDiscountPercent");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines es obligatorio y no puede estar vacío");
        }

        BigDecimal subtotal = zero();
        BigDecimal discountedSubtotal = zero();
        BigDecimal lineDiscount = zero();
        List<CalculatedLineResult> results = new java.util.ArrayList<>(lines.size());
        for (CalculatedLine line : lines) {
            validateLine(line);
            BigDecimal base = ensureWithinLimit(scale(line.quantity().multiply(line.unitPrice())), "base");
            BigDecimal discount = percentage(base, line.lineDiscountPercent());
            BigDecimal lineTotal = ensureWithinLimit(scale(base.subtract(discount)), "lineTotal");
            results.add(new CalculatedLineResult(
                line.productId(), line.quantity(), scale(line.unitPrice()),
                scale(line.lineDiscountPercent()), base, discount, lineTotal));
            subtotal = ensureWithinLimit(scale(subtotal.add(base)), "subtotal");
            discountedSubtotal = ensureWithinLimit(scale(discountedSubtotal.add(lineTotal)), "discountedSubtotal");
            lineDiscount = scale(lineDiscount.add(discount));
        }

        BigDecimal orderDiscount = percentage(discountedSubtotal, orderDiscountPercent);
        BigDecimal total = ensureWithinLimit(scale(discountedSubtotal.subtract(orderDiscount)), "total");
        if (total.signum() < 0) {
            throw new IllegalArgumentException("El total no puede ser negativo");
        }
        return new OrderCalculation(results, subtotal, lineDiscount, scale(orderDiscountPercent),
            orderDiscount, total, zero(), total);
    }

    public OrderCalculation calculate(
        List<CalculatedLine> lines,
        BigDecimal orderDiscountPercent,
        List<? extends OrderConfirmationService.PaymentCommand> payments
    ) {
        OrderCalculation calculation = calculate(lines, orderDiscountPercent);
        BigDecimal paid = zero();
        BigDecimal paymentTotal = zero();
        if (payments != null) {
            for (OrderConfirmationService.PaymentCommand payment : payments) {
                if (payment == null || payment.amount() == null || payment.amount().signum() <= 0) {
                    throw new IllegalArgumentException("El importe del pago debe ser positivo");
                }
                ensureWithinLimit(payment.amount(), "payment.amount");
                paymentTotal = ensureWithinLimit(scale(paymentTotal.add(payment.amount())), "paymentTotal");
                if (!"CUSTOMER_ACCOUNT".equals(payment.method())) {
                    paid = scale(paid.add(payment.amount()));
                }
            }
        }
        if (paymentTotal.compareTo(calculation.total()) > 0) {
            throw new IllegalStateException("Los pagos no pueden superar el total");
        }
        return calculation.withPaid(paid);
    }

    private void validateLine(CalculatedLine line) {
        if (line == null || line.productId() == null || line.quantity() == null
            || line.unitPrice() == null || line.lineDiscountPercent() == null) {
            throw new IllegalArgumentException("La línea contiene valores obligatorios inválidos");
        }
        if (line.quantity().signum() <= 0 || line.quantity().remainder(HALF).signum() != 0) {
            throw new IllegalArgumentException("quantity debe ser positiva y múltiplo de 0.5");
        }
        if (line.unitPrice().signum() < 0) {
            throw new IllegalArgumentException("unitPrice no puede ser negativo");
        }
        ensureWithinLimit(line.unitPrice(), "unitPrice");
        validateDiscount(line.lineDiscountPercent(), "lineDiscountPercent");
    }

    private static BigDecimal ensureWithinLimit(BigDecimal value, String field) {
        if (value.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException(field + " supera el máximo permitido");
        }
        return value;
    }

    private void validateDiscount(BigDecimal discount, String field) {
        if (discount == null || discount.signum() < 0 || discount.compareTo(ONE_HUNDRED) > 0
            || discount.scale() > SCALE) {
            throw new IllegalArgumentException(field + " debe estar entre 0 y 100 con máximo 4 decimales");
        }
    }

    private BigDecimal percentage(BigDecimal amount, BigDecimal percent) {
        return scale(amount.multiply(percent).divide(ONE_HUNDRED, SCALE, ROUNDING));
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    public record CalculatedLine(UUID productId, BigDecimal quantity, BigDecimal unitPrice,
                                BigDecimal lineDiscountPercent) {
    }

    public record CalculatedLineResult(UUID productId, BigDecimal quantity, BigDecimal unitPrice,
                                       BigDecimal lineDiscountPercent, BigDecimal base,
                                       BigDecimal lineDiscount, BigDecimal lineTotal) {
    }

    public record OrderCalculation(List<CalculatedLineResult> lines, BigDecimal subtotal,
                                   BigDecimal lineDiscount, BigDecimal orderDiscountPercent,
                                   BigDecimal orderDiscount, BigDecimal total, BigDecimal paid,
                                   BigDecimal balance) {
        public OrderCalculation {
            lines = List.copyOf(lines);
        }

        private OrderCalculation withPaid(BigDecimal newPaid) {
            return new OrderCalculation(lines, subtotal, lineDiscount, orderDiscountPercent,
                orderDiscount, total, newPaid, total.subtract(newPaid).setScale(SCALE, ROUNDING));
        }
    }
}
