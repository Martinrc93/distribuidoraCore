package com.distribuidora.dashboard.api;

import com.distribuidora.dashboard.application.SoldProductsReportService;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/reports/sold-products")
@PreAuthorize("hasAnyAuthority('REPORTS_VIEW', 'ADMIN_ALL')")
public class SoldProductsReportController {
    private final SoldProductsReportService reports;

    public SoldProductsReportController(SoldProductsReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public PageResponse<SoldProductsReportService.ProductTotals> products(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax,
        @RequestParam(defaultValue = "") String search,
        @RequestParam(required = false) UUID productId
    ) {
        return reports.products(page, size, dateMin, dateMax, search, productId);
    }

    @GetMapping("/{productId}/sales")
    public SoldProductsReportService.History sales(
        @PathVariable UUID productId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax
    ) {
        return reports.sales(productId, page, size, dateMin, dateMax);
    }
}
