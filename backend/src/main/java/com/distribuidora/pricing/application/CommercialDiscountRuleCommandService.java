package com.distribuidora.pricing.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class CommercialDiscountRuleCommandService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CommercialDiscountRuleCommandService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record RuleInput(String code, String description, String kind, BigDecimal percent,
                            UUID customerId, UUID priceListId, UUID productId,
                            LocalDate validFrom, LocalDate validUntil, Integer priority) { }

    @Transactional
    public UUID create(RuleInput input) {
        NormalizedRule rule = normalize(input);
        if (Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from catalog.commercial_discount_rules where code = ?)",
            Boolean.class, rule.code()))) {
            throw new IllegalStateException("Ya existe una regla de descuento con ese código");
        }
        validateReferences(rule);
        UUID id = UUID.randomUUID();
        Timestamp now = timestamp();
        jdbc.update("""
            insert into catalog.commercial_discount_rules
                (id, code, description, kind, percent, customer_id, price_list_id, product_id,
                 priority, status, valid_from, valid_until, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
            """, id, rule.code(), rule.description(), rule.kind(), rule.percent(), rule.customerId(),
            rule.priceListId(), rule.productId(), rule.priority(), rule.validFrom(), rule.validUntil(), now, now);
        audit.record(actorId(), "DISCOUNT_RULE_CREATE", "DISCOUNT_RULE", id.toString(), "SUCCESS",
            auditDetails(rule));
        return id;
    }

    @Transactional
    public void update(UUID id, RuleInput input) {
        requireId(id);
        NormalizedRule rule = normalize(input);
        if (Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from catalog.commercial_discount_rules where code = ? and id <> ?)",
            Boolean.class, rule.code(), id))) {
            throw new IllegalStateException("Ya existe una regla de descuento con ese código");
        }
        validateReferences(rule);
        Timestamp now = timestamp();
        int updated = jdbc.update("""
            update catalog.commercial_discount_rules
            set code = ?, description = ?, kind = ?, percent = ?, customer_id = ?, price_list_id = ?,
                product_id = ?, priority = ?, valid_from = ?, valid_until = ?, updated_at = ?
            where id = ?
            """, rule.code(), rule.description(), rule.kind(), rule.percent(), rule.customerId(),
            rule.priceListId(), rule.productId(), rule.priority(), rule.validFrom(), rule.validUntil(), now, id);
        if (updated == 0) throw new EmptyResultDataAccessException(1);
        audit.record(actorId(), "DISCOUNT_RULE_UPDATE", "DISCOUNT_RULE", id.toString(), "SUCCESS",
            auditDetails(rule));
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        requireId(id);
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }
        int updated = jdbc.update("update catalog.commercial_discount_rules set status = ?, updated_at = ? where id = ?",
            status, timestamp(), id);
        if (updated == 0) throw new EmptyResultDataAccessException(1);
        audit.record(actorId(), "DISCOUNT_RULE_STATUS", "DISCOUNT_RULE", id.toString(), "SUCCESS",
            Map.of("status", status));
    }

    private NormalizedRule normalize(RuleInput input) {
        if (input == null) throw new IllegalArgumentException("rule es obligatorio");
        String code = input.code() == null ? "" : input.code().trim().toUpperCase(java.util.Locale.ROOT);
        String description = input.description() == null ? "" : input.description().trim();
        String kind = input.kind() == null ? "" : input.kind().trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[A-Z0-9_-]{2,40}")) {
            throw new IllegalArgumentException("code debe tener entre 2 y 40 caracteres alfanuméricos, guion o underscore");
        }
        if (description.isBlank() || description.length() > 160) {
            throw new IllegalArgumentException("description es obligatorio y no puede superar 160 caracteres");
        }
        if (!"LINE".equals(kind) && !"ORDER".equals(kind)) {
            throw new IllegalArgumentException("kind debe ser LINE u ORDER");
        }
        BigDecimal percent = input.percent();
        if (percent == null || percent.signum() <= 0 || percent.compareTo(new BigDecimal("100")) > 0
            || percent.scale() > 4 || percent.precision() > 7 || percent.precision() - percent.scale() > 3) {
            throw new IllegalArgumentException("percent debe ser mayor a 0 y hasta 100 con máximo 4 decimales");
        }
        if (("LINE".equals(kind) && input.productId() == null)
            || ("ORDER".equals(kind) && input.productId() != null)) {
            throw new IllegalArgumentException("LINE requiere productId y ORDER no admite productId");
        }
        int priority = input.priority() == null ? 0 : input.priority();
        if (priority < -1000 || priority > 1000) {
            throw new IllegalArgumentException("priority debe estar entre -1000 y 1000");
        }
        LocalDate validFrom = input.validFrom() == null
            ? jdbc.queryForObject("select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class)
            : input.validFrom();
        if (input.validUntil() != null && input.validUntil().isBefore(validFrom)) {
            throw new IllegalArgumentException("validUntil no puede ser anterior a validFrom");
        }
        return new NormalizedRule(code, description, kind, percent, input.customerId(), input.priceListId(),
            input.productId(), validFrom, input.validUntil(), priority);
    }

    private void validateReferences(NormalizedRule rule) {
        if (rule.customerId() != null && !exists("select exists(select 1 from customer.customers where id = ? and status = 'ACTIVE')", rule.customerId())) {
            throw new IllegalArgumentException("El cliente no existe o está inactivo");
        }
        if (rule.priceListId() != null && !exists("select exists(select 1 from catalog.price_lists where id = ? and status = 'ACTIVE')", rule.priceListId())) {
            throw new IllegalArgumentException("La lista de precios no existe o está inactiva");
        }
        if (rule.productId() != null && !exists("select exists(select 1 from catalog.products where id = ? and status = 'ACTIVE')", rule.productId())) {
            throw new IllegalArgumentException("El producto no existe o está inactivo");
        }
    }

    private Map<String, Object> auditDetails(NormalizedRule rule) {
        return Map.of("code", rule.code(), "kind", rule.kind(), "percent", rule.percent(),
            "validFrom", rule.validFrom().toString(), "priority", rule.priority());
    }

    private boolean exists(String sql, Object... values) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, values));
    }

    private void requireId(UUID id) {
        if (id == null) throw new IllegalArgumentException("id es obligatorio");
    }

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception exception) {
            throw new IllegalArgumentException("El actor autenticado no tiene un UUID válido", exception);
        }
    }

    private Timestamp timestamp() {
        return Timestamp.from(Instant.now());
    }

    private record NormalizedRule(String code, String description, String kind, BigDecimal percent,
                                  UUID customerId, UUID priceListId, UUID productId,
                                  LocalDate validFrom, LocalDate validUntil, int priority) { }
}
