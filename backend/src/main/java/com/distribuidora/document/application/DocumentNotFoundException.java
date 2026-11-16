package com.distribuidora.document.application;

/** A document's source record (price list, customer, filter) does not exist or is out of the user's scope. */
public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException(String message) {
        super(message);
    }
}
