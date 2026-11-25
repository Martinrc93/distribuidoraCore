package com.distribuidora.document.api;

import com.distribuidora.document.application.SaleDocumentService;
import com.distribuidora.document.rendering.OpenPdfA4Renderer;
import com.distribuidora.document.rendering.OpenPdfTicketRenderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class DocumentController {
    private final SaleDocumentService service;
    private final OpenPdfA4Renderer renderer;
    private final OpenPdfTicketRenderer ticketRenderer;

    public DocumentController(SaleDocumentService service, OpenPdfA4Renderer renderer,
                              OpenPdfTicketRenderer ticketRenderer) {
        this.service = service;
        this.renderer = renderer;
        this.ticketRenderer = ticketRenderer;
    }

    @GetMapping("/{orderId}/documents/a4")
    @PreAuthorize("hasAnyAuthority('ORDER_DOCUMENT_EXPORT', 'ADMIN_ALL')")
    public ResponseEntity<StreamingResponseBody> a4(@PathVariable UUID orderId) {
        SaleDocumentModel model = service.load(orderId);
        String filename = "venta-" + sanitize(model.saleNumber()) + ".pdf";
        return PdfDownload.attachment(filename, output -> renderer.render(model, output));
    }

    @GetMapping("/{orderId}/documents/ticket")
    @PreAuthorize("hasAnyAuthority('ORDER_DOCUMENT_EXPORT', 'ADMIN_ALL')")
    public ResponseEntity<StreamingResponseBody> ticket(@PathVariable UUID orderId) {
        SaleDocumentModel model = service.load(orderId);
        String filename = "ticket-" + sanitize(model.saleNumber()) + ".pdf";
        return PdfDownload.attachment(filename, output -> ticketRenderer.render(model, output));
    }

    private String sanitize(String saleNumber) {
        String sanitized = saleNumber.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "document" : sanitized;
    }
}
