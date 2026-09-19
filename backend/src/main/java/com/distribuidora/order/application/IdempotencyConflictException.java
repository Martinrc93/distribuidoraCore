package com.distribuidora.order.application;

public class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException() {
        super("La clave de idempotencia ya fue utilizada con otro payload");
    }
}
