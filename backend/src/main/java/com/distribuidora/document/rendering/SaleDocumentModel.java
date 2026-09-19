package com.distribuidora.document.rendering;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record SaleDocumentModel(
        String saleNumber,
        LocalDate saleDate,
        String customerName,
        String sellerName,
        List<Line> lines,
        List<Payment> payments,
        BigDecimal total,
        BigDecimal paid
) {

    public SaleDocumentModel {
        saleNumber = Objects.requireNonNull(saleNumber, "saleNumber");
        saleDate = Objects.requireNonNull(saleDate, "saleDate");
        customerName = Objects.requireNonNull(customerName, "customerName");
        sellerName = Objects.requireNonNull(sellerName, "sellerName");
        lines = List.copyOf(lines);
        payments = List.copyOf(payments);
        total = money(total);
        paid = money(paid);
    }

    public BigDecimal pendingBalance() {
        return total.subtract(paid).max(BigDecimal.ZERO);
    }

    public record Line(
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal subtotal
    ) {
        public Line {
            description = Objects.requireNonNull(description, "description");
            quantity = Objects.requireNonNull(quantity, "quantity");
            unitPrice = money(unitPrice);
            discount = money(discount);
            subtotal = money(subtotal);
        }
    }

    public record Payment(String method, BigDecimal amount) {
        public Payment {
            method = Objects.requireNonNull(method, "method");
            amount = money(amount);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return Objects.requireNonNull(value, "monetary value");
    }
}
