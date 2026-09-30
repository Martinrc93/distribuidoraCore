package com.distribuidora.customer.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ZoneService {
    public record Zone(UUID id, String name) { }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ZoneService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Zone> list() {
        return jdbc.query("select id, name from customer.zones order by name",
            (rs, row) -> new Zone(rs.getObject("id", UUID.class), rs.getString("name")));
    }

    @Transactional
    public UUID create(String name) {
        String normalizedName = validateName(name);
        requireUniqueName(normalizedName, null);
        UUID id = UUID.randomUUID();
        Timestamp now = timestamp();
        jdbc.update("insert into customer.zones(id, name, created_at, updated_at) values (?, ?, ?, ?)", id, normalizedName, now, now);
        audit.record(actorId(), "ZONE_CREATE", "ZONE", id.toString(), "SUCCESS", Map.of("name", normalizedName));
        return id;
    }

    @Transactional
    public void rename(UUID id, String name) {
        String normalizedName = validateName(name);
        requireUniqueName(normalizedName, id);
        String previousName = jdbc.queryForObject("select name from customer.zones where id = ?", String.class, id);
        if (jdbc.update("update customer.zones set name = ?, updated_at = ? where id = ?", normalizedName, timestamp(), id) == 0) {
            throw new EmptyResultDataAccessException(1);
        }
        jdbc.update("update customer.customers set zone = ? where zone = ?", normalizedName, previousName);
        audit.record(actorId(), "ZONE_UPDATE", "ZONE", id.toString(), "SUCCESS", Map.of("name", normalizedName));
    }

    @Transactional
    public void delete(UUID id) {
        String name = jdbc.queryForObject("select name from customer.zones where id = ?", String.class, id);
        jdbc.update("update customer.customers set zone = null where zone = ?", name);
        jdbc.update("delete from customer.zones where id = ?", id);
        audit.record(actorId(), "ZONE_DELETE", "ZONE", id.toString(), "SUCCESS", Map.of("name", name));
    }

    private void requireUniqueName(String name, UUID excludedId) {
        Boolean exists = excludedId == null
            ? jdbc.queryForObject("select exists(select 1 from customer.zones where lower(name) = lower(?))", Boolean.class, name)
            : jdbc.queryForObject("select exists(select 1 from customer.zones where lower(name) = lower(?) and id <> ?)", Boolean.class, name, excludedId);
        if (Boolean.TRUE.equals(exists)) throw new IllegalStateException("Ya existe una zona con ese nombre");
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("El nombre de la zona es obligatorio");
        String normalized = name.trim();
        if (normalized.length() > 120) throw new IllegalArgumentException("El nombre no puede superar los 120 caracteres");
        return normalized;
    }

    private Timestamp timestamp() { return Timestamp.from(Instant.now()); }
    private UUID actorId() {
        try { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); }
        catch (Exception ignored) { return null; }
    }
}
