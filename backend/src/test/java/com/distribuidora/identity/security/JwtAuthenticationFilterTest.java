package com.distribuidora.identity.security;

import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.domain.UserStatus;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final JwtService jwtService = new JwtService("0123456789abcdef0123456789abcdef", "test-issuer", 15);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, users);

    @Test
    void acceptsJwtWhenSessionVersionAndUserStatusAreCurrent() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount user = user(userId, UserStatus.ACTIVE, 4);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        String token = jwtService.issue(user, List.of("ORDER_CREATE"));

        Authentication authentication = filter(token);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo(userId.toString());
    }

    @Test
    void rejectsJwtAfterSessionVersionChanges() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount tokenUser = user(userId, UserStatus.ACTIVE, 4);
        String token = jwtService.issue(tokenUser, List.of("ORDER_CREATE"));
        when(users.findById(userId)).thenReturn(Optional.of(user(userId, UserStatus.ACTIVE, 5)));

        assertThat(filter(token)).isNull();
    }

    @Test
    void rejectsJwtWhenUserIsBlocked() throws Exception {
        UUID userId = UUID.randomUUID();
        UserAccount tokenUser = user(userId, UserStatus.ACTIVE, 4);
        String token = jwtService.issue(tokenUser, List.of("ORDER_CREATE"));
        when(users.findById(userId)).thenReturn(Optional.of(user(userId, UserStatus.BLOCKED, 4)));

        assertThat(filter(token)).isNull();
    }

    private Authentication filter(String token) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request, response, chain);
        SecurityContextHolder.clearContext();
        return observed.get();
    }

    private UserAccount user(UUID userId, UserStatus status, long version) {
        UserAccount user = new UserAccount("session-test@example.com", "hash");
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "status", status);
        ReflectionTestUtils.setField(user, "version", version);
        return user;
    }
}
