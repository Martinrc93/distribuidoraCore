package com.distribuidora.shared.security;

import com.distribuidora.identity.domain.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {
    private final String issuer;
    private final long accessTokenSeconds;
    private final SecretKey secretKey;

    public JwtService(
        @Value("${app.security.jwt-secret}") String secret,
        @Value("${app.security.jwt-issuer}") String issuer,
        @Value("${app.security.access-token-minutes:15}") long accessTokenMinutes
    ) {
        this.issuer = issuer;
        this.accessTokenSeconds = accessTokenMinutes * 60;
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(UserAccount user, List<String> authorities) {
        Date issuedAt = new Date();
        return Jwts.builder()
            .issuer(issuer)
            .issuedAt(issuedAt)
            .expiration(new Date(issuedAt.getTime() + accessTokenSeconds * 1000))
            .subject(user.getId().toString())
            .claim("email", user.getEmail())
            .claim("authorities", List.copyOf(authorities))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact();
    }

    public long accessTokenSeconds() {
        return accessTokenSeconds;
    }

    public Claims parse(String token) {
        Jws<Claims> parsed = Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token);
        return parsed.getPayload();
    }
}
