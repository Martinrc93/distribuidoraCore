package com.distribuidora.document.api;

import com.distribuidora.document.application.SaleDocumentService;
import com.distribuidora.document.rendering.OpenPdfA4Renderer;
import com.distribuidora.document.rendering.OpenPdfTicketRenderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public ResponseEntity<byte[]> a4(@PathVariable UUID orderId) {
        SaleDocumentModel model = service.load(orderId);
        byte[] pdf = renderer.render(model);
        String filename = "venta-" + sanitize(model.saleNumber()) + ".pdf";

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(pdf.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(pdf);
    }

    @GetMapping("/{orderId}/documents/ticket")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public ResponseEntity<byte[]> ticket(@PathVariable UUID orderId) {
        SaleDocumentModel model = service.load(orderId);
        byte[] pdf = ticketRenderer.render(model);
        String filename = "ticket-" + sanitize(model.saleNumber()) + ".pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(pdf.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(pdf);
    }

    private String sanitize(String saleNumber) {
        String sanitized = saleNumber.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "document" : sanitized;
    }
}
