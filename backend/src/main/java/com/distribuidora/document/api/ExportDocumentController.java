package com.distribuidora.document.api;

import com.distribuidora.document.application.AccountStatementDocumentService;
import com.distribuidora.document.application.PriceListDocumentService;
import com.distribuidora.document.rendering.AccountStatementModel;
import com.distribuidora.document.rendering.AccountStatementPdfRenderer;
import com.distribuidora.document.rendering.PriceListDocumentModel;
import com.distribuidora.document.rendering.PriceListPdfRenderer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Printable documents other than the sale document: price lists and customer debt statements. */
@RestController
@RequestMapping("/api/documents")
public class ExportDocumentController {
    private final PriceListDocumentService priceLists;
    private final PriceListPdfRenderer priceListRenderer;
    private final AccountStatementDocumentService statements;
    private final AccountStatementPdfRenderer statementRenderer;

    public ExportDocumentController(PriceListDocumentService priceLists, PriceListPdfRenderer priceListRenderer,
                                    AccountStatementDocumentService statements, AccountStatementPdfRenderer statementRenderer) {
        this.priceLists = priceLists;
        this.priceListRenderer = priceListRenderer;
        this.statements = statements;
        this.statementRenderer = statementRenderer;
    }

    /** A4 price list with current prices, optionally filtered; sellers can download it for offline use. */
    @GetMapping("/price-lists/{listId}")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public ResponseEntity<byte[]> priceList(@PathVariable UUID listId,
                                            @RequestParam(required = false) UUID categoryId,
                                            @RequestParam(required = false) UUID brandId,
                                            @RequestParam(required = false) String search) {
        PriceListDocumentModel model = priceLists.load(listId, categoryId, brandId, search);
        return pdf(priceListRenderer.render(model), "lista-" + sanitize(model.listCode()) + ".pdf");
    }

    /** Customer debt statement in A4 ({@code format=a4}, default) or 80 mm ticket ({@code format=ticket}). */
    @GetMapping("/customers/{customerId}/statement")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_STATEMENT_EXPORT', 'ADMIN_ALL')")
    public ResponseEntity<byte[]> statement(@PathVariable UUID customerId, @RequestParam(defaultValue = "a4") String format) {
        if (!"a4".equals(format) && !"ticket".equals(format)) throw new IllegalArgumentException("format debe ser a4 o ticket");
        AccountStatementModel model = statements.load(customerId);
        byte[] pdf = "ticket".equals(format) ? statementRenderer.renderTicket(model) : statementRenderer.renderA4(model);
        return pdf(pdf, "estado-deuda-" + sanitize(model.customerName()) + ("ticket".equals(format) ? "-ticket" : "") + ".pdf");
    }

    private ResponseEntity<byte[]> pdf(byte[] pdf, String filename) {
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(pdf.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(pdf);
    }

    private String sanitize(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "documento" : sanitized;
    }
}
