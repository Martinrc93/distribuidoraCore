package com.distribuidora.settings.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class CommissionPlansService {
    public enum Scope { DEFAULT, CATEGORY, BRAND, PRODUCT }
    public enum Kind { FIXED, PERCENT }
    public record Rule(Scope scope, UUID targetId, Kind kind,
                       @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal value) { }
    public record Overview(UUID id, String name, long ruleCount, long sellerCount, long resellerCount) { }
    public record Plan(UUID id, String name, long version, List<Rule> rules) { }
    public record Assignment(UUID sellerId, UUID planId) { }
    public record ResellerAssignment(UUID customerId, UUID planId) { }
    private record RuleTarget(Scope scope, UUID id) { }

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess user;
    private final AuditService audit;

    public CommissionPlansService(JdbcTemplate jdbc, CurrentUserAccess user, AuditService audit) {
        this.jdbc = jdbc;
        this.user = user;
        this.audit = audit;
    }

    public List<Overview> list() {
        requireAdmin();
        return jdbc.query("""
            select p.id, p.name, (select count(*) from app.commission_rules r where r.plan_id = p.id),
                   (select count(*) from app.seller_commission_plans s where s.plan_id = p.id),
                   (select count(*) from app.reseller_commission_plans r where r.plan_id = p.id)
            from app.commission_plans p order by lower(p.name), p.id
            """, (row, idx) -> new Overview(row.getObject(1, UUID.class), row.getString(2), row.getLong(3), row.getLong(4),
                row.getLong(5)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Plan find(UUID id) {
        requireAdmin();
        return readPlan(id);
    }

    @Transactional
    public Plan create(String name, List<Rule> rules) {
        requireAdmin();
        String normalized = validate(name, rules);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into app.commission_plans(id, name) values (?, ?)", id, normalized);
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("Ya existe un plan con ese nombre", exception);
        }
        return saveRules(id, rules, "COMMISSION_PLAN_CREATE");
    }

    @Transactional
    public Plan update(UUID id, Long expectedVersion, String name, List<Rule> rules) {
        requireAdmin();
        String normalized = validate(name, rules);
        if (expectedVersion == null || expectedVersion < 1) {
            throw new IllegalArgumentException("Se requiere la versión del plan");
        }
        long currentVersion = jdbc.queryForObject(
            "select version from app.commission_plans where id = ? for update", Long.class, id);
        if (currentVersion != expectedVersion) {
            throw new IllegalStateException("El plan cambió mientras lo editabas");
        }
        try {
            jdbc.update("update app.commission_plans set name = ?, version = version + 1 where id = ?", normalized, id);
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("Ya existe un plan con ese nombre", exception);
        }
        jdbc.update("delete from app.commission_rules where plan_id = ?", id);
        return saveRules(id, rules, "COMMISSION_PLAN_UPDATE");
    }

    public Assignment assignment(UUID sellerId) {
        requireAdmin();
        return jdbc.queryForObject("""
            select a.plan_id from seller.seller_profiles s
            left join app.seller_commission_plans a on a.seller_id = s.id where s.id = ?
            """, (row, idx) -> new Assignment(sellerId, row.getObject(1, UUID.class)), sellerId);
    }

    @Transactional
    public Assignment assign(UUID sellerId, UUID planId, UUID expectedPlanId) {
        requireAdmin();
        jdbc.queryForObject("select id from seller.seller_profiles where id = ? for update", UUID.class, sellerId);
        UUID before = assignment(sellerId).planId();
        if (!Objects.equals(before, expectedPlanId)) {
            throw new IllegalStateException("La asignación del vendedor cambió");
        }
        if (Objects.equals(before, planId)) return new Assignment(sellerId, planId);
        if (planId == null) {
            jdbc.update("delete from app.seller_commission_plans where seller_id = ?", sellerId);
        } else {
            jdbc.queryForObject("select id from app.commission_plans where id = ? for key share", UUID.class, planId);
            jdbc.update("""
                insert into app.seller_commission_plans(seller_id, plan_id) values (?, ?)
                on conflict (seller_id) do update set plan_id = excluded.plan_id
                """, sellerId, planId);
        }
        audit.recordWithinTransaction(user.userId(), "SELLER_COMMISSION_ASSIGNMENT", "SELLER", sellerId.toString(),
            "SUCCESS", Map.of("previousPlanId", Objects.toString(before, ""), "planId", Objects.toString(planId, "")));
        return new Assignment(sellerId, planId);
    }

    public ResellerAssignment resellerAssignment(UUID customerId) {
        requireAdmin();
        return jdbc.queryForObject("""
            select a.plan_id from customer.customers c
            left join app.reseller_commission_plans a on a.customer_id = c.id where c.id = ?
            """, (row, idx) -> new ResellerAssignment(customerId, row.getObject(1, UUID.class)), customerId);
    }

    /**
     * Assigns a plan to a reseller, or removes it with {@code null}. Only later deliveries use it: accrued
     * commissions keep the plan they were calculated with.
     */
    @Transactional
    public ResellerAssignment assignReseller(UUID customerId, UUID planId, UUID expectedPlanId) {
        requireAdmin();
        // The same row CustomerCommandService locks to change the kind: a reseller cannot lose its mark meanwhile.
        String kind = jdbc.queryForObject("select kind from customer.customers where id = ? for no key update",
            String.class, customerId);
        UUID before = resellerAssignment(customerId).planId();
        if (!Objects.equals(before, expectedPlanId)) {
            throw new IllegalStateException("La asignación del revendedor cambió");
        }
        if (Objects.equals(before, planId)) return new ResellerAssignment(customerId, planId);
        if (planId == null) {
            jdbc.update("delete from app.reseller_commission_plans where customer_id = ?", customerId);
        } else {
            if (!"RESELLER".equals(kind)) {
                throw new IllegalStateException("Solo los revendedores pueden tener un plan de comisión");
            }
            jdbc.queryForObject("select id from app.commission_plans where id = ? for key share", UUID.class, planId);
            jdbc.update("""
                insert into app.reseller_commission_plans(customer_id, plan_id) values (?, ?)
                on conflict (customer_id) do update set plan_id = excluded.plan_id
                """, customerId, planId);
        }
        audit.recordWithinTransaction(user.userId(), "RESELLER_COMMISSION_ASSIGNMENT", "CUSTOMER", customerId.toString(),
            "SUCCESS", Map.of("previousPlanId", Objects.toString(before, ""), "planId", Objects.toString(planId, "")));
        return new ResellerAssignment(customerId, planId);
    }

    private Plan readPlan(UUID id) {
        return jdbc.queryForObject("select name, version from app.commission_plans where id = ?",
            (row, idx) -> new Plan(id, row.getString(1), row.getLong(2), readRules(id)), id);
    }

    private List<Rule> readRules(UUID id) {
        return jdbc.query("""
            select case when product_id is not null then 'PRODUCT' when brand_id is not null then 'BRAND'
                        when category_id is not null then 'CATEGORY' else 'DEFAULT' end scope,
                   coalesce(product_id, brand_id, category_id) as target_id, kind, value
            from app.commission_rules where plan_id = ? order by scope, target_id
            """, (row, idx) -> new Rule(Scope.valueOf(row.getString("scope")), row.getObject("target_id", UUID.class),
                Kind.valueOf(row.getString("kind")), row.getBigDecimal("value")), id);
    }

    private Plan saveRules(UUID id, List<Rule> rules, String operation) {
        List<Object[]> args = rules.stream().map(rule -> new Object[]{
            id, target(rule, Scope.CATEGORY), target(rule, Scope.BRAND), target(rule, Scope.PRODUCT),
            rule.kind().name(), rule.value()
        }).toList();
        try {
            jdbc.batchUpdate("""
                insert into app.commission_rules(plan_id, category_id, brand_id, product_id, kind, value)
                values (?, ?, ?, ?, ?, ?)
                """, args);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("Revisá los artículos, marcas y categorías de las reglas", exception);
        }
        Plan saved = readPlan(id);
        audit.recordWithinTransaction(user.userId(), operation, "COMMISSION_PLAN", id.toString(), "SUCCESS",
            Map.of("name", saved.name(), "version", saved.version(), "rules", saved.rules()));
        return saved;
    }

    private String validate(String name, List<Rule> rules) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new IllegalArgumentException("Ingresá un nombre de hasta 100 caracteres");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Agregá al menos una regla de comisión");
        }
        var targets = new HashSet<RuleTarget>();
        for (Rule rule : rules) {
            if (rule == null || rule.scope() == null || rule.kind() == null || rule.value() == null
                || (rule.scope() == Scope.DEFAULT) != (rule.targetId() == null)) {
                throw new IllegalArgumentException("Completá el alcance, tipo e importe de cada regla");
            }
            if (!targets.add(new RuleTarget(rule.scope(), rule.targetId()))) {
                throw new IllegalArgumentException("Hay reglas repetidas para el mismo alcance");
            }
            if (rule.value().signum() < 0 || rule.value().scale() > 4 || rule.value().compareTo(BigDecimal.TEN.pow(15)) >= 0
                || rule.kind() == Kind.PERCENT && rule.value().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("Usá valores no negativos de hasta 4 decimales y porcentajes de hasta 100");
            }
        }
        return name.strip();
    }

    private UUID target(Rule rule, Scope scope) {
        return rule.scope() == scope ? rule.targetId() : null;
    }

    private void requireAdmin() {
        if (!user.isAdmin()) throw new AccessDeniedException("ADMIN_ALL es requerido para administrar comisiones");
    }
}
