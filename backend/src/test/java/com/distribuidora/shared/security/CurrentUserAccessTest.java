package com.distribuidora.shared.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CurrentUserAccessTest {
    private final JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
    private final CurrentUserAccess access = new CurrentUserAccess(jdbc);
    private final UUID userId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void adminBypassesOwnershipQueries() {
        authenticate(List.of("ADMIN_ALL"));

        assertThat(access.isAdmin()).isTrue();
        access.requireCustomerAccess(UUID.randomUUID());
        access.requireOrderAccess(UUID.randomUUID());
        Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void sellerCanAccessAssignedCustomerAndOrder() {
        authenticate(List.of("ORDER_CREATE"));
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(userId))).thenReturn(sellerId);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any())).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any())).thenReturn(true);

        access.requireCustomerAccess(UUID.randomUUID());
        access.requireOrderAccess(UUID.randomUUID());
    }

    @Test
    void sellerCannotAccessForeignOrder() {
        authenticate(List.of("ORDER_CREATE"));
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(userId))).thenReturn(sellerId);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> access.requireOrderAccess(UUID.randomUUID()))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    private void authenticate(List<String> authorities) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(userId.toString(), null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
