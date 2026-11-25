package com.distribuidora.document.rendering;

import com.distribuidora.settings.application.BusinessProfile;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
        BigDecimal paid,
        BigDecimal orderDiscountPercent,
        BusinessProfile businessProfile,
        byte[] businessLogo
) {

    public SaleDocumentModel(String saleNumber, LocalDate saleDate, String customerName, String sellerName,
                             List<Line> lines, List<Payment> payments, BigDecimal total, BigDecimal paid) {
        this(saleNumber, saleDate, customerName, sellerName, lines, payments, total, paid, BigDecimal.ZERO);
    }

    public SaleDocumentModel(String saleNumber, LocalDate saleDate, String customerName, String sellerName,
                             List<Line> lines, List<Payment> payments, BigDecimal total, BigDecimal paid,
                             BigDecimal orderDiscountPercent) {
        this(saleNumber, saleDate, customerName, sellerName, lines, payments, total, paid, orderDiscountPercent,
            BusinessProfile.EMPTY);
    }

    public SaleDocumentModel(String saleNumber, LocalDate saleDate, String customerName, String sellerName,
                             List<Line> lines, List<Payment> payments, BigDecimal total, BigDecimal paid,
                             BigDecimal orderDiscountPercent, BusinessProfile businessProfile) {
        this(saleNumber, saleDate, customerName, sellerName, lines, payments, total, paid, orderDiscountPercent,
            businessProfile, null);
    }

    public SaleDocumentModel {
        saleNumber = Objects.requireNonNull(saleNumber, "saleNumber");
        saleDate = Objects.requireNonNull(saleDate, "saleDate");
        customerName = Objects.requireNonNull(customerName, "customerName");
        sellerName = Objects.requireNonNull(sellerName, "sellerName");
        lines = List.copyOf(lines);
        payments = List.copyOf(payments);
        total = money(total);
        paid = money(paid);
        orderDiscountPercent = Objects.requireNonNull(orderDiscountPercent, "orderDiscountPercent");
        businessProfile = Objects.requireNonNull(businessProfile, "businessProfile");
    }

    public BigDecimal pendingBalance() {
        return total.subtract(paid).max(BigDecimal.ZERO);
    }

    public Totals totals() {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (Line line : lines) {
            quantity = quantity.add(line.quantity());
            gross = gross.add(line.grossAmount());
            net = net.add(line.subtotal());
        }
        return new Totals(quantity, gross, gross.subtract(net), net.subtract(total));
    }

    public record Totals(BigDecimal quantity, BigDecimal gross, BigDecimal productDiscount, BigDecimal generalDiscount) { }

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

        public BigDecimal grossAmount() {
            return quantity.multiply(unitPrice).setScale(4, RoundingMode.HALF_UP);
        }

        public BigDecimal discountAmount() {
            return grossAmount().subtract(subtotal);
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
