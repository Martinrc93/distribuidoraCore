package com.distribuidora.customer.application;

/** A reseller change that the current customer state does not allow; its message is safe to show. */
public class ResellerConflictException extends IllegalStateException {
    public ResellerConflictException(String message) {
        super(message);
    }
}
