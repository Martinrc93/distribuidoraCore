package com.distribuidora.document.api;

import com.distribuidora.document.rendering.SupplierOrderPdfRenderer;
import com.distribuidora.purchasing.application.SupplierOrderService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class SupplierOrderDocumentController {
    private final SupplierOrderService orders;
    private final SupplierOrderPdfRenderer renderer;

    public SupplierOrderDocumentController(SupplierOrderService orders, SupplierOrderPdfRenderer renderer) {
        this.orders = orders;
        this.renderer = renderer;
    }

    @GetMapping(value = "/api/supplier-orders/{orderId}/documents/a4", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID orderId) {
        var order = orders.document(orderId);
        byte[] pdf = renderer.render(order);
        String filename = "compra-" + order.number() + ".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(pdf.length)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .body(pdf);
    }
}
