package com.distribuidora.identity.application;

public class InvitationEmailUnavailableException extends RuntimeException {
    public InvitationEmailUnavailableException() {
        super("El envío de invitaciones por email no está configurado. Contacte al administrador.");
    }
}
