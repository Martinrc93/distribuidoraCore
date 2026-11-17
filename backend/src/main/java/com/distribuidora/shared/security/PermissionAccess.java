package com.distribuidora.shared.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

/** Action authorization only. Resource scope remains in CurrentUserAccess. */
public final class PermissionAccess {
    private PermissionAccess() { }

    public static boolean allows(String permission) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ADMIN_ALL".equals(authority.getAuthority()) || permission.equals(authority.getAuthority()));
    }

    public static void require(String permission) {
        if (!allows(permission)) throw new AccessDeniedException("Se requiere el permiso " + permission);
    }
}
