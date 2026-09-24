package com.distribuidora.shared.security;

import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.domain.UserStatus;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserAccountRepository users;

    public JwtAuthenticationFilter(JwtService jwtService, UserAccountRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                UUID userId = UUID.fromString(claims.getSubject());
                UserAccount user = users.findById(userId).orElse(null);
                Object tokenVersion = claims.get("sessionVersion");
                if (user == null || user.getStatus() != UserStatus.ACTIVE
                    || !(tokenVersion instanceof Number number) || user.getVersion() != number.longValue()) {
                    throw new IllegalArgumentException("Invalid or revoked session");
                }
                Collection<SimpleGrantedAuthority> authorities = authorities(claims);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    claims.getSubject(), null, authorities
                );
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private Collection<SimpleGrantedAuthority> authorities(Claims claims) {
        Object raw = claims.get("authorities");
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(SimpleGrantedAuthority::new).toList();
        }
        if (raw instanceof String value) {
            return Arrays.stream(value.split(",")).map(SimpleGrantedAuthority::new).toList();
        }
        return List.of();
    }
}
