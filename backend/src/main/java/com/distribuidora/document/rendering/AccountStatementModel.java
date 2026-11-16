package com.distribuidora.document.rendering;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Open debts of a customer at {@code date}: one line per sale with pending balance, oldest first. */
public record AccountStatementModel(String customerName, String customerTaxId, String customerAddress, LocalDate date,
                                    List<Line> lines, BigDecimal totalDue) {
    public AccountStatementModel {
        lines = List.copyOf(lines);
    }

    public record Line(LocalDate date, String saleNumber, String orderNumber, BigDecimal total, BigDecimal paid, BigDecimal due) { }
}
