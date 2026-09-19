package com.distribuidora.shared.web;

public final class RequestIdContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestIdContext() {
    }

    public static void set(String requestId) {
        CURRENT.set(requestId);
    }

    public static String currentOrGenerate() {
        String value = CURRENT.get();
        return value == null ? java.util.UUID.randomUUID().toString() : value;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
