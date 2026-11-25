package com.distribuidora.document.api;

import com.distribuidora.document.rendering.SupplierOrderPdfRenderer;
import com.distribuidora.purchasing.application.SupplierOrderQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

@RestController
public class SupplierOrderDocumentController {
    private final SupplierOrderQueryService orders;
    private final SupplierOrderPdfRenderer renderer;

    public SupplierOrderDocumentController(SupplierOrderQueryService orders, SupplierOrderPdfRenderer renderer) {
        this.orders = orders;
        this.renderer = renderer;
    }

    @GetMapping(value = "/api/supplier-orders/{orderId}/documents/a4", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<StreamingResponseBody> pdf(@PathVariable UUID orderId) {
        var order = orders.document(orderId);
        String filename = "compra-" + order.number() + ".pdf";
        return PdfDownload.attachment(filename, output -> renderer.render(order, output));
    }
}
