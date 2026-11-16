package com.distribuidora.document.rendering;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Current prices of a list for printing. {@code filters} describes the applied category/brand/search filters. */
public record PriceListDocumentModel(String listName, String listCode, LocalDate date, String filters, List<Line> lines) {
    public PriceListDocumentModel {
        lines = List.copyOf(lines);
    }

    public record Line(String category, String product, BigDecimal price) { }
}
