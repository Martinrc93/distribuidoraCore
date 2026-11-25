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
public class CategoryService {
    public interface CategoryCommand {
        String name();
    }

    public record CategoryView(UUID id, String name, String status, Instant createdAt, long productCount) { }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public CategoryService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    @Transactional
    public UUID create(CategoryCommand request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name es obligatorio");
        }

        String name = request.name().trim();
        if (name.length() > 100) {
            throw new IllegalArgumentException("name no puede superar 100 caracteres");
        }

        boolean nameExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.categories where lower(name) = ?)",
                Boolean.class, name.toLowerCase(Locale.ROOT))
        );
        if (nameExists) {
            throw new IllegalStateException("Ya existe una categoría con ese nombre");
        }

        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        try {
            jdbc.update(
                "insert into catalog.categories(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
                id, name, id.toString(), now
            );
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Ya existe una categoría con ese nombre", e);
        }

        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "CATEGORY_CREATE", "CATEGORY", id.toString(), "SUCCESS", Map.of("name", name));
        return id;
    }

    @Transactional
    public void update(UUID id, CategoryCommand request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name es obligatorio");
        }

        boolean exists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.categories where id = ?)", Boolean.class, id)
        );
        if (!exists) {
            throw new EmptyResultDataAccessException("La categoría no existe", 1);
        }

        String name = request.name().trim();
        if (name.length() > 100) {
            throw new IllegalArgumentException("name no puede superar 100 caracteres");
        }

        boolean nameExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.categories where lower(name) = ? and id <> ?)",
                Boolean.class, name.toLowerCase(Locale.ROOT), id)
        );
        if (nameExists) {
            throw new IllegalStateException("Ya existe una categoría con ese nombre");
        }

        try {
            jdbc.update("update catalog.categories set name = ? where id = ?", name, id);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Ya existe una categoría con ese nombre", e);
        }
        jdbc.update("update catalog.products set category = ? where category_id = ?", name, id);
        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "CATEGORY_UPDATE", "CATEGORY", id.toString(), "SUCCESS", Map.of("name", name));
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }

        int rows = jdbc.update("update catalog.categories set status = ? where id = ?", status, id);
        if (rows == 0) {
            throw new EmptyResultDataAccessException("La categoría no existe", 1);
        }

        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "CATEGORY_STATUS", "CATEGORY", id.toString(), "SUCCESS", Map.of("status", status));
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
                delete from catalog.categories c
                where c.id = ? and not exists (
                    select 1 from catalog.products p where p.category_id = c.id or lower(p.category) = lower(c.name)
                )
                """, id);
        } catch (DataIntegrityViolationException exception) {
            throw new CatalogEntryInUseException("La categoría está en uso por productos o reglas de comisión.", exception);
        }
        if (deleted == 0) {
            getById(id);
            throw new CatalogEntryInUseException("La categoría tiene productos asociados.");
        }
        audit.recordWithinTransaction(currentUser.actorIdOrSystem(), "CATEGORY_DELETE", "CATEGORY", id.toString(), "SUCCESS", Map.of());
    }

    public CategoryView getById(UUID id) {
        List<CategoryView> list = jdbc.query("""
            select c.id, c.name, c.status, c.created_at as "createdAt",
                   (select count(*) from catalog.products p where p.category_id = c.id or lower(p.category) = lower(c.name)) as "productCount"
            from catalog.categories c
            where c.id = ?
            """,
            (rs, i) -> new CategoryView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("status"),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getLong("productCount")
            ),
            id
        );
        if (list.isEmpty()) {
            throw new EmptyResultDataAccessException("La categoría no existe", 1);
        }
        return list.getFirst();
    }

    public List<CategoryView> list(String search, String status) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String state = status == null ? "" : status.trim();
        StringBuilder sql = new StringBuilder("""
            select c.id, c.name, c.status, c.created_at as "createdAt",
                   (select count(*) from catalog.products p where p.category_id = c.id or lower(p.category) = lower(c.name)) as "productCount"
            from catalog.categories c
            where 1 = 1
            """);
        List<Object> params = new java.util.ArrayList<>();
        if (!term.isEmpty()) {
            sql.append(" and lower(c.name) like ? escape '!'");
            params.add("%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (!state.isEmpty()) {
            sql.append(" and c.status = ?");
            params.add(state);
        }
        sql.append(" order by c.name");

        return jdbc.query(sql.toString(),
            (rs, i) -> new CategoryView(
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
