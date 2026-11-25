package com.distribuidora.identity.application;

public class AuthRateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public AuthRateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
