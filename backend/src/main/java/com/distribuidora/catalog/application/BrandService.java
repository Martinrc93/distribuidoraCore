package com.distribuidora.catalog.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

@Service
public class BrandService {
    public interface BrandCommand {
        String name();
    }

    public record BrandView(UUID id, String name, String status, Instant createdAt, long productCount) { }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public BrandService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    @Transactional
    public UUID create(BrandCommand request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name es obligatorio");
        }

        String name = request.name().trim();
        if (name.length() > 100) {
            throw new IllegalArgumentException("name no puede superar 100 caracteres");
        }

        boolean nameExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.brands where lower(name) = ?)", Boolean.class, name.toLowerCase(Locale.ROOT))
        );
        if (nameExists) {
            throw new IllegalStateException("Ya existe una marca con ese nombre");
        }

        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        try {
            jdbc.update(
                "insert into catalog.brands(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
                id, name, id.toString(), now
            );
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Ya existe una marca con ese nombre", e);
        }

        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "BRAND_CREATE", "BRAND", id.toString(), "SUCCESS", Map.of("name", name));
        return id;
    }

    @Transactional
    public void update(UUID id, BrandCommand request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name es obligatorio");
        }

        boolean exists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.brands where id = ?)", Boolean.class, id)
        );
        if (!exists) {
            throw new EmptyResultDataAccessException("La marca no existe", 1);
        }

        String name = request.name().trim();
        if (name.length() > 100) {
            throw new IllegalArgumentException("name no puede superar 100 caracteres");
        }

        boolean nameExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.brands where lower(name) = ? and id <> ?)",
                Boolean.class, name.toLowerCase(Locale.ROOT), id)
        );
        if (nameExists) {
            throw new IllegalStateException("Ya existe una marca con ese nombre");
        }

        try {
            jdbc.update("update catalog.brands set name = ? where id = ?", name, id);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Ya existe una marca con ese nombre", e);
        }
        jdbc.update("update catalog.products set name = ? || ' ' || description where brand_id = ? and description is not null",
            name, id);
        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "BRAND_UPDATE", "BRAND", id.toString(), "SUCCESS", Map.of("name", name));
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }

        int rows = jdbc.update("update catalog.brands set status = ? where id = ?", status, id);
        if (rows == 0) {
            throw new EmptyResultDataAccessException("La marca no existe", 1);
        }

        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "BRAND_STATUS", "BRAND", id.toString(), "SUCCESS", Map.of("status", status));
    }

    @Transactional
    public void activate(UUID id) {
        setStatus(id, "ACTIVE");
    }

    @Transactional
    public void deactivate(UUID id) {
        setStatus(id, "INACTIVE");
    }

    @Transactional
    public void deletePermanently(UUID id) {
        int deleted;
        try {
            deleted = jdbc.update("""
                delete from catalog.brands b
                where b.id = ? and not exists (select 1 from catalog.products p where p.brand_id = b.id)
                """, id);
        } catch (DataIntegrityViolationException exception) {
            throw new CatalogEntryInUseException("La marca está en uso por productos o reglas de comisión.", exception);
        }
        if (deleted == 0) {
            getById(id);
            throw new CatalogEntryInUseException("La marca tiene productos asociados.");
        }
        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "BRAND_DELETE", "BRAND", id.toString(), "SUCCESS", Map.of());
    }

    public BrandView getById(UUID id) {
        List<BrandView> list = jdbc.query("""
            select b.id, b.name, b.status, b.created_at as "createdAt",
                   (select count(*) from catalog.products p where p.brand_id = b.id) as "productCount"
            from catalog.brands b
            where b.id = ?
            """,
            (rs, i) -> new BrandView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getLong("productCount")
            ),
            id
        );
        if (list.isEmpty()) {
            throw new EmptyResultDataAccessException("La marca no existe", 1);
        }
        return list.getFirst();
    }

    public List<BrandView> list(String search, String status) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String state = status == null ? "" : status.trim();
        StringBuilder sql = new StringBuilder("""
            select b.id, b.name, b.status, b.created_at as "createdAt",
                   (select count(*) from catalog.products p where p.brand_id = b.id) as "productCount"
            from catalog.brands b
            where 1 = 1
            """);
        List<Object> params = new java.util.ArrayList<>();
        if (!term.isEmpty()) {
            sql.append(" and lower(b.name) like ? escape '!'");
            params.add("%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (!state.isEmpty()) {
            sql.append(" and b.status = ?");
            params.add(state);
        }
        sql.append(" order by b.name");

        return jdbc.query(sql.toString(),
            (rs, i) -> new BrandView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getLong("productCount")
            ),
            params.toArray()
        );
    }

}
