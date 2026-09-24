package com.distribuidora.notification.application;

public class NotificationChannelNotConfiguredException extends RuntimeException {
    public NotificationChannelNotConfiguredException(String channel) {
        super("El canal " + channel + " no tiene un proveedor configurado");
    }
}
