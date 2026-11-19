package com.distribuidora.catalog.application;

public class CatalogEntryInUseException extends RuntimeException {
    public CatalogEntryInUseException(String message) {
        super(message);
    }

    public CatalogEntryInUseException(String message, Throwable cause) {
        super(message, cause);
    }
}
