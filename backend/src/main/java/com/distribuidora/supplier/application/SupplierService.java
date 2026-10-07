package com.distribuidora.supplier.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class SupplierService {
    public record Supplier(UUID id, String name, String phone, String email, String address) { }

    public record SupplierInput(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 80) String phone,
        @Email @Size(max = 200) String email,
        @Size(max = 500) String address
    ) {
        public SupplierInput {
            name = normalize(name);
            phone = normalize(phone);
            email = normalize(email);
            address = normalize(address);
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;
    private final Validator validator;

    public SupplierService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser, Validator validator) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentUser = currentUser;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public PageResponse<Supplier> list(int page, int size, String search) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Paginación inválida");
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (term.length() > 200) throw new IllegalArgumentException("La búsqueda no puede superar los 200 caracteres");
        String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String filter = " where lower(name) like ? escape '!'";
        long total = jdbc.queryForObject("select count(*) from supplier.suppliers" + filter, Long.class, pattern);
        var suppliers = jdbc.query("select id, name, phone, email, address from supplier.suppliers" + filter
                + " order by name, id limit ? offset ?",
            (rs, row) -> new Supplier(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("phone"), rs.getString("email"), rs.getString("address")), pattern, size, (long) page * size);
        return PageResponse.of(suppliers, page, size, total);
    }

    @Transactional
    public UUID create(SupplierInput input) {
        validate(input);
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into supplier.suppliers(id, name, phone, email, address, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
            id, input.name(), input.phone(), input.email(), input.address(), now, now);
        audit.record(currentUser.userId(), "SUPPLIER_CREATE", "SUPPLIER", id.toString(), "SUCCESS", Map.of("name", input.name()));
        return id;
    }

    @Transactional
    public void update(UUID id, SupplierInput input) {
        validate(input);
        int changed = jdbc.update("update supplier.suppliers set name = ?, phone = ?, email = ?, address = ?, updated_at = ? where id = ?",
            input.name(), input.phone(), input.email(), input.address(), Timestamp.from(Instant.now()), id);
        if (changed == 0) throw new EmptyResultDataAccessException(1);
        audit.record(currentUser.userId(), "SUPPLIER_UPDATE", "SUPPLIER", id.toString(), "SUCCESS", Map.of("name", input.name()));
    }

    private void validate(SupplierInput input) {
        if (input == null || !validator.validate(input).isEmpty()) throw new IllegalArgumentException("Los datos del proveedor son inválidos");
    }
}
