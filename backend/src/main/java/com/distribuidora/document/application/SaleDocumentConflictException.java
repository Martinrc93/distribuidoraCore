package com.distribuidora.document.application;

import java.util.UUID;

public class SaleDocumentConflictException extends RuntimeException {
    public SaleDocumentConflictException(UUID orderId) {
        super("Order has no associated sale: " + orderId);
    }
}
