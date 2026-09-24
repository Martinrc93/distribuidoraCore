package com.distribuidora.identity.security;

import com.distribuidora.identity.domain.UserAccount;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private final JwtService jwtService = new JwtService(
        "a-secure-secret-that-is-at-least-32-bytes-long",
        "distribuidora",
        15
    );

    @Test
    void tokenContainsPersistedAuthorities() throws Exception {
        UserAccount administrator = user("ordinary@distribuidora.local");

        Claims claims = jwtService.parse(jwtService.issue(administrator,
            List.of("ADMIN_ALL", "ORDER_CREATE", "STOCK_ADJUST")));

        assertThat(claims.get("authorities", List.class))
            .containsExactly("ADMIN_ALL", "ORDER_CREATE", "STOCK_ADJUST");
    }

    @Test
    void emailTextDoesNotGrantAuthorities() throws Exception {
        UserAccount user = user("admin-not-really@distribuidora.local");

        Claims claims = jwtService.parse(jwtService.issue(user, List.of("ORDER_CREATE")));

        assertThat(claims.get("authorities", List.class))
            .containsExactly("ORDER_CREATE")
            .doesNotContain("STOCK_ADJUST");
    }

    private static UserAccount user(String email) throws Exception {
        UserAccount user = new UserAccount(email, "password");
        Field id = UserAccount.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(user, UUID.randomUUID());
        return user;
    }
}
