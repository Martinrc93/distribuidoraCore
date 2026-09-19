package com.distribuidora.document.application;

import java.util.UUID;

public class SaleDocumentNotFoundException extends RuntimeException {
    public SaleDocumentNotFoundException(UUID orderId) {
        super("Order not found: " + orderId);
    }
}
