package com.distribuidora.dashboard.api;

import com.distribuidora.dashboard.application.CustomerPurchasesReportService;
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
@RequestMapping("/api/reports/customers/{customerId}/purchases")
public class CustomerPurchasesReportController {
    private final CustomerPurchasesReportService reports;

    public CustomerPurchasesReportController(CustomerPurchasesReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('REPORTS_VIEW', 'ADMIN_ALL')")
    public CustomerPurchasesReportService.Report report(
        @PathVariable UUID customerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return reports.report(customerId, dateMin, dateMax, page, size);
    }
}
