package com.distribuidora.document.api;

import com.distribuidora.document.application.AccountStatementDocumentService;
import com.distribuidora.document.application.PriceListDocumentService;
import com.distribuidora.document.rendering.AccountStatementModel;
import com.distribuidora.document.rendering.AccountStatementPdfRenderer;
import com.distribuidora.document.rendering.PriceListDocumentModel;
import com.distribuidora.document.rendering.PriceListPdfRenderer;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    @GetMapping("/price-lists")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<StreamingResponseBody> allPriceLists() {
        var lists = priceLists.loadActiveLists();
        return PdfDownload.attachment("listas-de-precios.pdf", output -> priceListRenderer.renderAll(lists, output));
    }

    /** A4 price list with current prices, optionally filtered; sellers can download it for offline use. */
    @GetMapping("/price-lists/{listId}")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public ResponseEntity<StreamingResponseBody> priceList(@PathVariable UUID listId,
                                            @RequestParam(required = false) UUID categoryId,
                                            @RequestParam(required = false) UUID brandId,
                                            @RequestParam(required = false) String search) {
        PriceListDocumentModel model = priceLists.load(listId, categoryId, brandId, search);
        return PdfDownload.attachment("lista-" + sanitize(model.listCode()) + ".pdf",
            output -> priceListRenderer.render(model, output));
    }

    /** Customer debt statement in A4 ({@code format=a4}, default) or 80 mm ticket ({@code format=ticket}). */
    @GetMapping("/customers/{customerId}/statement")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_STATEMENT_EXPORT', 'ADMIN_ALL')")
    public ResponseEntity<StreamingResponseBody> statement(@PathVariable UUID customerId, @RequestParam(defaultValue = "a4") String format) {
        if (!"a4".equals(format) && !"ticket".equals(format)) throw new IllegalArgumentException("format debe ser a4 o ticket");
        AccountStatementModel model = statements.load(customerId);
        boolean ticket = "ticket".equals(format);
        String filename = "estado-deuda-" + sanitize(model.customerName()) + (ticket ? "-ticket" : "") + ".pdf";
        return PdfDownload.attachment(filename, output -> {
            if (ticket) statementRenderer.renderTicket(model, output);
            else statementRenderer.renderA4(model, output);
        });
    }

    private String sanitize(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "documento" : sanitized;
    }
}
