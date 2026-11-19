package com.distribuidora.document.rendering;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Open debts of a customer account at {@code date}: one line per sale with pending balance, oldest first.
 * {@code showCustomers} is true when the account owes orders of other customers (a reseller).
 */
public record AccountStatementModel(String customerName, String customerTaxId, String customerAddress, LocalDate date,
                                    List<Line> lines, BigDecimal totalDue, boolean showCustomers) {
    public AccountStatementModel {
        lines = List.copyOf(lines);
    }

    public AccountStatementModel(String customerName, String customerTaxId, String customerAddress, LocalDate date,
                                 List<Line> lines, BigDecimal totalDue) {
        this(customerName, customerTaxId, customerAddress, date, lines, totalDue, false);
    }

    /** {@code customer} is the customer that received the order. */
    public record Line(LocalDate date, String saleNumber, String orderNumber, String customer,
                       BigDecimal total, BigDecimal paid, BigDecimal due) {
        public Line(LocalDate date, String saleNumber, String orderNumber, BigDecimal total, BigDecimal paid, BigDecimal due) {
            this(date, saleNumber, orderNumber, null, total, paid, due);
        }
    }
}
