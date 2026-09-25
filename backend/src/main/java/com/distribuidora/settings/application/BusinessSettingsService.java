package com.distribuidora.settings.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BusinessSettingsService {
    public interface CreditLimitCommand {
        BigDecimal creditLimit();
    }

    public record CreditLimitResult(BigDecimal creditLimit, boolean enabled, Instant updatedAt, UUID updatedBy) { }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public BusinessSettingsService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public CreditLimitResult creditLimit() {
        requireAdmin();
        Map<String, Object> row = jdbc.queryForMap(
            "select credit_limit, updated_at, updated_by from app.business_settings where id = 1");
        BigDecimal limit = (BigDecimal) row.get("credit_limit");
        Object updatedAt = row.get("updated_at");
        Object updatedBy = row.get("updated_by");
        return new CreditLimitResult(limit, limit != null,
            updatedAt instanceof Timestamp timestamp ? timestamp.toInstant() : null,
            updatedBy instanceof UUID uuid ? uuid : updatedBy == null ? null : UUID.fromString(String.valueOf(updatedBy)));
    }

    @Transactional
    public CreditLimitResult updateCreditLimit(CreditLimitCommand request) {
        requireAdmin();
        if (request == null || request.creditLimit() != null
            && (request.creditLimit().signum() < 0 || request.creditLimit().scale() > 4)) {
            throw new IllegalArgumentException("creditLimit debe ser null para desactivar o un importe no negativo de hasta 4 decimales");
        }
        BigDecimal previous = jdbc.queryForObject(
            "select credit_limit from app.business_settings where id = 1 for update", BigDecimal.class);
        UUID actor = actorId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("update app.business_settings set credit_limit = ?, updated_by = ?, updated_at = ? where id = 1",
            request.creditLimit(), actor, now);
        Map<String, Object> details = new HashMap<>();
        details.put("previousCreditLimit", previous);
        details.put("creditLimit", request.creditLimit());
        details.put("enabled", request.creditLimit() != null);
        audit.recordWithinTransaction(actor, "GLOBAL_CREDIT_LIMIT_UPDATE", "BUSINESS_SETTINGS", "1", "SUCCESS", details);
        return new CreditLimitResult(request.creditLimit(), request.creditLimit() != null,
            now.toInstant(), actor);
    }

    private void requireAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities().stream()
            .noneMatch(authority -> "ADMIN_ALL".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("ADMIN_ALL es requerido para consultar o modificar la configuración");
        }
    }

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo identificar al usuario actual");
        }
    }
}
