package com.distribuidora.document.api;

import com.distribuidora.dashboard.application.CustomerPurchasesReportService;
import com.distribuidora.document.rendering.CustomerPurchasesPdfRenderer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents/customers/{customerId}/purchases")
public class CustomerPurchasesDocumentController {
    private final CustomerPurchasesReportService reports;
    private final CustomerPurchasesPdfRenderer renderer;

    public CustomerPurchasesDocumentController(CustomerPurchasesReportService reports, CustomerPurchasesPdfRenderer renderer) {
        this.reports = reports;
        this.renderer = renderer;
    }

    @GetMapping(produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyAuthority('REPORTS_VIEW', 'ADMIN_ALL')")
    public ResponseEntity<StreamingResponseBody> pdf(
        @PathVariable UUID customerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax
    ) {
        var report = reports.export(customerId, dateMin, dateMax);
        String filename = "compras-" + report.summary().dateMin() + "-" + report.summary().dateMax() + ".pdf";
        return PdfDownload.attachment(filename, output -> renderer.render(report, output));
    }
}
