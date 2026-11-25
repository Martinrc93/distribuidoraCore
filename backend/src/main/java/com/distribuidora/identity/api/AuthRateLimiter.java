package com.distribuidora.identity.api;

import com.distribuidora.identity.application.AuthRateLimitExceededException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthRateLimiter {
    private static final int MAX_TRACKED_KEYS = 10_000;

    private record Window(long windowStartMillis, int count) { }

    private final boolean enabled;
    private final long windowMillis;
    private final int loginPerIp;
    private final int loginPerAccount;
    private final int refreshPerIp;
    private final int activatePerIp;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public AuthRateLimiter(
        @Value("${app.security.rate-limit.enabled:true}") boolean enabled,
        @Value("${app.security.rate-limit.window-seconds:60}") long windowSeconds,
        @Value("${app.security.rate-limit.login-per-ip:20}") int loginPerIp,
        @Value("${app.security.rate-limit.login-per-account:10}") int loginPerAccount,
        @Value("${app.security.rate-limit.refresh-per-ip:60}") int refreshPerIp,
        @Value("${app.security.rate-limit.activate-per-ip:10}") int activatePerIp
    ) {
        this(enabled, windowSeconds, loginPerIp, loginPerAccount, refreshPerIp, activatePerIp, Clock.systemUTC());
    }

    public AuthRateLimiter(
        boolean enabled,
        long windowSeconds,
        int loginPerIp,
        int loginPerAccount,
        int refreshPerIp,
        int activatePerIp,
        Clock clock
    ) {
        this.enabled = enabled;
        this.windowMillis = Math.max(1L, windowSeconds) * 1000L;
        this.loginPerIp = Math.max(1, loginPerIp);
        this.loginPerAccount = Math.max(1, loginPerAccount);
        this.refreshPerIp = Math.max(1, refreshPerIp);
        this.activatePerIp = Math.max(1, activatePerIp);
        this.clock = clock;
    }

    public static AuthRateLimiter disabled() {
        return new AuthRateLimiter(false, 60, 20, 10, 60, 10, Clock.systemUTC());
    }

    public void checkLogin(String clientIp, String email) {
        if (!enabled) return;
        consume("login:ip:" + normalizeIp(clientIp), loginPerIp);
        if (email != null && !email.isBlank()) {
            String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
            consume("login:acct:" + normalizedEmail, loginPerAccount);
        }
    }

    public void checkRefresh(String clientIp) {
        if (!enabled) return;
        consume("refresh:ip:" + normalizeIp(clientIp), refreshPerIp);
    }

    public void checkActivate(String clientIp) {
        if (!enabled) return;
        consume("activate:ip:" + normalizeIp(clientIp), activatePerIp);
    }

    private void consume(String key, int limit) {
        long now = clock.millis();
        if (windows.size() >= MAX_TRACKED_KEYS && !windows.containsKey(key)) {
            prune(now);
        }
        long[] retryAfter = {0L};
        Window updated = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMillis() >= windowMillis) {
                return new Window(now, 1);
            }
            int nextCount = existing.count() + 1;
            if (nextCount > limit) {
                long remainingMillis = Math.max(1L, windowMillis - (now - existing.windowStartMillis()));
                retryAfter[0] = (remainingMillis + 999L) / 1000L;
                return existing;
            }
            return new Window(existing.windowStartMillis(), nextCount);
        });
        if (retryAfter[0] > 0L || updated.count() > limit) {
            throw new AuthRateLimitExceededException(
                "Demasiados intentos de autenticación. Esperá un momento antes de volver a intentar.",
                Math.max(1L, retryAfter[0])
            );
        }
    }

    private void prune(long now) {
        windows.entrySet().removeIf(entry -> now - entry.getValue().windowStartMillis() >= windowMillis);
        if (windows.size() < MAX_TRACKED_KEYS) return;
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (windows.size() >= MAX_TRACKED_KEYS && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String normalizeIp(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.trim();
    }
}
