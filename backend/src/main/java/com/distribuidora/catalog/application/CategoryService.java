package com.distribuidora.catalog.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.api.CatalogAdminDtos;
import com.distribuidora.shared.security.CurrentUserAccess;
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

@Service
public class CategoryService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public CategoryService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public UUID create(CatalogAdminDtos.CreateCategoryRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name es obligatorio");
        }

        String name = request.name().trim();
        String code = normalizeCode(request.code(), name);

        boolean nameExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.categories where lower(name) = ?)",
                Boolean.class, name.toLowerCase(Locale.ROOT))
        );
        if (nameExists) {
            throw new IllegalStateException("Ya existe una categoría con ese nombre");
        }

        boolean codeExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.categories where lower(code) = ?)",
                Boolean.class, code.toLowerCase(Locale.ROOT))
        );
        if (codeExists) {
            throw new IllegalStateException("Ya existe una categoría con ese código");
        }

        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update(
            "insert into catalog.categories(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
            id, name, code, now
        );

        audit.record(actorId(), "CATEGORY_CREATE", "CATEGORY", id.toString(), "SUCCESS", Map.of("name", name, "code", code));
        return id;
    }

    @Transactional
    public void update(UUID id, CatalogAdminDtos.UpdateCategoryRequest request) {
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
        String code = normalizeCode(request.code(), name);

        boolean nameExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.categories where lower(name) = ? and id <> ?)",
                Boolean.class, name.toLowerCase(Locale.ROOT), id)
        );
        if (nameExists) {
            throw new IllegalStateException("Ya existe una categoría con ese nombre");
        }

        boolean codeExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from catalog.categories where lower(code) = ? and id <> ?)",
                Boolean.class, code.toLowerCase(Locale.ROOT), id)
        );
        if (codeExists) {
            throw new IllegalStateException("Ya existe una categoría con ese código");
        }

        jdbc.update("update catalog.categories set name = ?, code = ? where id = ?", name, code, id);
        audit.record(actorId(), "CATEGORY_UPDATE", "CATEGORY", id.toString(), "SUCCESS", Map.of("name", name, "code", code));
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

        audit.record(actorId(), "CATEGORY_STATUS", "CATEGORY", id.toString(), "SUCCESS", Map.of("status", status));
    }

    @Transactional
    public void activate(UUID id) {
        setStatus(id, "ACTIVE");
    }

    @Transactional
    public void deactivate(UUID id) {
        setStatus(id, "INACTIVE");
    }

    public CatalogAdminDtos.CategoryResponse getById(UUID id) {
        List<CatalogAdminDtos.CategoryResponse> list = jdbc.query("""
            select c.id, c.name, c.code, c.status, c.created_at as "createdAt",
                   (select count(*) from catalog.products p where p.category_id = c.id or lower(p.category) = lower(c.name)) as "productCount"
            from catalog.categories c
            where c.id = ?
            """,
            (rs, i) -> new CatalogAdminDtos.CategoryResponse(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("code"),
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

    public List<CatalogAdminDtos.CategoryResponse> list(String search, String status) {
        String term = "%" + (search == null ? "" : search.trim().toLowerCase(Locale.ROOT)) + "%";
        String state = status == null || status.isBlank() ? "%" : status.trim();

        return jdbc.query("""
            select c.id, c.name, c.code, c.status, c.created_at as "createdAt",
                   (select count(*) from catalog.products p where p.category_id = c.id or lower(p.category) = lower(c.name)) as "productCount"
            from catalog.categories c
            where (lower(c.name) like ? or lower(c.code) like ?)
              and c.status like ?
            order by c.name
            """,
            (rs, i) -> new CatalogAdminDtos.CategoryResponse(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("status"),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getLong("productCount")
            ),
            term, term, state
        );
    }

    private String normalizeCode(String rawCode, String name) {
        if (rawCode != null && !rawCode.isBlank()) {
            return rawCode.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]+", "-");
        }
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]+", "-");
    }

    private UUID actorId() {
        if (currentUser != null) {
            try {
                return currentUser.userId();
            } catch (Exception ignored) {
            }
        }
        return UUID.fromString("00000000-0000-0000-0000-000000000000");
    }
}
