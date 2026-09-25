package com.distribuidora.identity.application;

public class InvalidActivationTokenException extends RuntimeException {

    public InvalidActivationTokenException(String message) {
        super(message);
    }
}
