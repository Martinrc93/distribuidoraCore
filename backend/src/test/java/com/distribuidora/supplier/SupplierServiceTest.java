package com.distribuidora.supplier;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.supplier.application.SupplierService;
import com.distribuidora.supplier.application.SupplierService.SupplierInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplierServiceTest {
    private SupplierService service;
    private JdbcTemplate jdbc;
    private AuditService audit;
    private LocalValidatorFactoryBean validator;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V33__create_suppliers.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        audit = mock(AuditService.class);
        var currentUser = mock(CurrentUserAccess.class);
        when(currentUser.userId()).thenReturn(actor);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        service = new SupplierService(jdbc, audit, currentUser, validator);
    }

    @AfterEach void close() { validator.close(); }

    @Test void createsWithOnlyNameAndNormalizesOptionalFields() {
        UUID id = service.create(new SupplierInput("  North supplier  ", " ", null, ""));
        var supplier = service.list(0, 20, "").content().getFirst();
        assertThat(supplier.id()).isEqualTo(id);
        assertThat(supplier.name()).isEqualTo("North supplier");
        assertThat(supplier.phone()).isNull();
        assertThat(supplier.email()).isNull();
        assertThat(supplier.address()).isNull();
        verify(audit).record(actor, "SUPPLIER_CREATE", "SUPPLIER", id.toString(), "SUCCESS", Map.of("name", "North supplier"));
        assertThat(jdbc.queryForObject("select created_at is not null and updated_at is not null from supplier.suppliers where id = ?", Boolean.class, id)).isTrue();
    }

    @Test void updatesContactsAndCanClearThemWithoutChangingIdentity() {
        UUID id = service.create(new SupplierInput("Supplier", null, null, null));
        service.update(id, new SupplierInput("New name", " 123 ", " contact@example.test ", " Main 10 "));
        assertThat(service.list(0, 20, "").content()).containsExactly(new SupplierService.Supplier(id, "New name", "123", "contact@example.test", "Main 10"));
        service.update(id, new SupplierInput("New name", "", "", ""));
        assertThat(service.list(0, 20, "").content()).containsExactly(new SupplierService.Supplier(id, "New name", null, null, null));
    }

    @Test void rejectsInvalidNamesEmailsAndLengthsBeforePersisting() {
        for (var input : new SupplierInput[] {
            new SupplierInput(" ", null, null, null), new SupplierInput("A", null, "invalid", null),
            new SupplierInput("a".repeat(201), null, null, null), new SupplierInput("A", "a".repeat(81), null, null),
            new SupplierInput("A", null, null, "a".repeat(501))
        }) assertThatThrownBy(() -> service.create(input)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list(0, 20, "").totalElements()).isZero();
        verifyNoInteractions(audit);
    }

    @Test void searchesLiterallyAndPaginatesInStableNameOrder() {
        service.create(new SupplierInput("Zulu", null, null, null));
        service.create(new SupplierInput("Alpha", null, null, null));
        service.create(new SupplierInput("50%_! supplier", null, null, null));
        var page = service.list(1, 1, "");
        assertThat(page.content().getFirst().name()).isEqualTo("Alpha");
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(service.list(0, 20, " ALPHA ").content()).hasSize(1);
        assertThat(service.list(0, 20, "%_!").content()).hasSize(1);
        assertThat(service.list(5, 20, "missing").content()).isEmpty();
        assertThatThrownBy(() -> service.list(-1, 20, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(0, 101, "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingSupplierDoesNotReportSuccessfulUpdate() {
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), new SupplierInput("Missing", null, null, null)))
            .isInstanceOf(EmptyResultDataAccessException.class);
        verifyNoInteractions(audit);
    }
}
