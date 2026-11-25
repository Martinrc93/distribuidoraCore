package com.distribuidora.settings.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import com.distribuidora.shared.security.CurrentUserAccess;

@Service
public class BusinessSettingsService {
    private static final BigDecimal MAX_CREDIT_LIMIT = new BigDecimal("999999999999999.9999");
    private static final long BRAND_CACHE_NANOS = TimeUnit.SECONDS.toNanos(30);

    public interface CreditLimitCommand {
        BigDecimal creditLimit();
    }

    public record CreditLimitResult(BigDecimal creditLimit, boolean enabled, Instant updatedAt, UUID updatedBy) { }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private BusinessBrand cachedBrand;
    private long brandExpiresAt;

    private record BusinessBrand(BusinessProfile profile, byte[] logo) { }

    public BusinessSettingsService(JdbcTemplate jdbc, AuditService audit) {
        this(jdbc, audit, null);
    }

    @Autowired
    public BusinessSettingsService(JdbcTemplate jdbc, AuditService audit, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    public BusinessProfile businessProfile() {
        requireAdmin();
        return businessBrand().profile();
    }

    @Transactional
    public BusinessProfile updateBusinessProfile(BusinessProfile profile) {
        requireAdmin();
        if (profile == null || profile.name() == null || profile.address() == null || profile.phone() == null) {
            throw new IllegalArgumentException("El perfil del negocio y sus campos son obligatorios");
        }
        String name = profile.name().strip();
        String address = profile.address().strip();
        String phone = profile.phone().strip();
        if (name.length() > 120 || address.length() > 240 || phone.length() > 60) {
            throw new IllegalArgumentException("Los datos del negocio superan el largo máximo permitido");
        }
        var saved = new BusinessProfile(name, address, phone);
        UUID actor = CurrentUserAccess.requireAuthenticatedUserId();
        jdbc.update("""
            update app.business_settings set business_name = ?, business_address = ?, business_phone = ?,
                updated_by = ?, updated_at = current_timestamp where id = 1
            """, saved.name(), saved.address(), saved.phone(), actor);
        audit.recordWithinTransaction(actor, "BUSINESS_PROFILE_UPDATE", "BUSINESS_SETTINGS", "1", "SUCCESS",
            Map.of("name", saved.name(), "address", saved.address(), "phone", saved.phone()));
        invalidateBrandAfterCommit();
        return saved;
    }

    public byte[] businessLogo() {
        byte[] logo = businessBrand().logo();
        return logo == null ? null : logo.clone();
    }

    public byte[] updateBusinessLogo(String content) {
        requireAdmin();
        byte[] logo = BusinessLogoImage.normalize(content);
        if (tx != null) {
            tx.executeWithoutResult(status -> saveBusinessLogo(logo));
        } else {
            saveBusinessLogo(logo);
        }
        return logo;
    }

    @Transactional
    public void removeBusinessLogo() {
        requireAdmin();
        saveBusinessLogo(null);
    }

    private void saveBusinessLogo(byte[] logo) {
        UUID actor = CurrentUserAccess.requireAuthenticatedUserId();
        jdbc.update("""
            update app.business_settings set business_logo = ?, updated_by = ?, updated_at = current_timestamp where id = 1
            """, logo, actor);
        audit.recordWithinTransaction(actor, "BUSINESS_LOGO_UPDATE", "BUSINESS_SETTINGS", "1", "SUCCESS",
            Map.of("hasLogo", logo != null));
        invalidateBrandAfterCommit();
    }

    private synchronized BusinessBrand businessBrand() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return loadBrand();
        long now = System.nanoTime();
        if (cachedBrand == null || now - brandExpiresAt >= 0L) {
            cachedBrand = loadBrand();
            brandExpiresAt = now + BRAND_CACHE_NANOS;
        }
        return cachedBrand;
    }

    private BusinessBrand loadBrand() {
        return jdbc.queryForObject("""
            select business_name, business_address, business_phone, business_logo from app.business_settings where id = 1
            """, (row, index) -> new BusinessBrand(
                new BusinessProfile(row.getString(1), row.getString(2), row.getString(3)), row.getBytes(4)));
    }

    private void invalidateBrandAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            clearBrandCache();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                clearBrandCache();
            }
        });
    }

    private synchronized void clearBrandCache() {
        cachedBrand = null;
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
            && (request.creditLimit().signum() < 0 || request.creditLimit().scale() > 4
                || request.creditLimit().compareTo(MAX_CREDIT_LIMIT) > 0)) {
            throw new IllegalArgumentException("creditLimit debe ser null para desactivar o un importe no negativo de hasta 4 decimales");
        }
        BigDecimal previous = jdbc.queryForObject(
            "select credit_limit from app.business_settings where id = 1 for update", BigDecimal.class);
        UUID actor = CurrentUserAccess.requireAuthenticatedUserId();
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
}
