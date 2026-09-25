package com.distribuidora.catalog;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.api.CatalogAdminDtos;
import com.distribuidora.catalog.application.CategoryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryServiceTest {
    private JdbcTemplate jdbc;
    private AuditService audit;
    private CurrentUserAccess currentUser;
    private CategoryService service;
    private UUID adminUserId;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        currentUser = mock(CurrentUserAccess.class);
        adminUserId = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(adminUserId);
        service = new CategoryService(jdbc, audit, currentUser);
    }

    @Test
    void create_rejectsNullOrBlankName() {
        assertThatThrownBy(() -> service.create(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CatalogAdminDtos.CreateCategoryRequest("", null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CatalogAdminDtos.CreateCategoryRequest("   ", "CODE")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsDuplicateNameOrCode() {
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where lower(name) = ?)"), eq(Boolean.class), eq("bebidas")))
            .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CatalogAdminDtos.CreateCategoryRequest("Bebidas", "BEB")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nombre");

        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where lower(name) = ?)"), eq(Boolean.class), eq("bebidas")))
            .thenReturn(false);
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where lower(code) = ?)"), eq(Boolean.class), eq("beb")))
            .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CatalogAdminDtos.CreateCategoryRequest("Bebidas", "BEB")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("código");
    }

    @Test
    void create_succeeds_normalizesCodeAndAudits() {
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where lower(name) = ?)"), eq(Boolean.class), eq("bebidas")))
            .thenReturn(false);
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where lower(code) = ?)"), eq(Boolean.class), eq("bebidas")))
            .thenReturn(false);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        UUID categoryId = service.create(new CatalogAdminDtos.CreateCategoryRequest("Bebidas", ""));

        assertThat(categoryId).isNotNull();
        verify(jdbc).update(
            eq("insert into catalog.categories(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', ?)"),
            eq(categoryId), eq("Bebidas"), eq("BEBIDAS"), any(Timestamp.class)
        );
        verify(audit).record(eq(adminUserId), eq("CATEGORY_CREATE"), eq("CATEGORY"), eq(categoryId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void update_rejectsNullOrBlankName() {
        UUID categoryId = UUID.randomUUID();
        assertThatThrownBy(() -> service.update(categoryId, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(categoryId, new CatalogAdminDtos.UpdateCategoryRequest("", "CODE")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_rejectsNonExistentCategory() {
        UUID categoryId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where id = ?)"), eq(Boolean.class), eq(categoryId)))
            .thenReturn(false);

        assertThatThrownBy(() -> service.update(categoryId, new CatalogAdminDtos.UpdateCategoryRequest("Snacks", "SNK")))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void update_rejectsDuplicateNameOnOtherCategory() {
        UUID categoryId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where id = ?)"), eq(Boolean.class), eq(categoryId)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where lower(name) = ? and id <> ?)"), eq(Boolean.class), eq("snacks"), eq(categoryId)))
            .thenReturn(true);

        assertThatThrownBy(() -> service.update(categoryId, new CatalogAdminDtos.UpdateCategoryRequest("Snacks", "SNK")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nombre");
    }

    @Test
    void update_succeeds_andAudits() {
        UUID categoryId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where id = ?)"), eq(Boolean.class), eq(categoryId)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where lower(name) = ? and id <> ?)"), eq(Boolean.class), eq("snacks"), eq(categoryId)))
            .thenReturn(false);
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.categories where lower(code) = ? and id <> ?)"), eq(Boolean.class), eq("snk"), eq(categoryId)))
            .thenReturn(false);
        when(jdbc.update(anyString(), any(), any(), eq(categoryId))).thenReturn(1);

        service.update(categoryId, new CatalogAdminDtos.UpdateCategoryRequest("Snacks", "SNK"));

        verify(jdbc).update(eq("update catalog.categories set name = ?, code = ? where id = ?"), eq("Snacks"), eq("SNK"), eq(categoryId));
        verify(audit).record(eq(adminUserId), eq("CATEGORY_UPDATE"), eq("CATEGORY"), eq(categoryId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void setStatus_rejectsInvalidStatusOrMissingCategory() {
        UUID categoryId = UUID.randomUUID();
        assertThatThrownBy(() -> service.setStatus(categoryId, "OTHER"))
            .isInstanceOf(IllegalArgumentException.class);

        when(jdbc.update(anyString(), any(), eq(categoryId))).thenReturn(0);
        assertThatThrownBy(() -> service.setStatus(categoryId, "INACTIVE"))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void setStatus_succeeds_andAudits() {
        UUID categoryId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), eq(categoryId))).thenReturn(1);

        service.setStatus(categoryId, "INACTIVE");

        verify(jdbc).update(eq("update catalog.categories set status = ? where id = ?"), eq("INACTIVE"), eq(categoryId));
        verify(audit).record(eq(adminUserId), eq("CATEGORY_STATUS"), eq("CATEGORY"), eq(categoryId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void activateAndDeactivate_delegateToSetStatus() {
        UUID categoryId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), eq(categoryId))).thenReturn(1);

        service.deactivate(categoryId);
        verify(jdbc).update(eq("update catalog.categories set status = ? where id = ?"), eq("INACTIVE"), eq(categoryId));

        service.activate(categoryId);
        verify(jdbc).update(eq("update catalog.categories set status = ? where id = ?"), eq("ACTIVE"), eq(categoryId));
    }

    @Test
    void getById_succeeds() {
        UUID categoryId = UUID.randomUUID();
        CategoryService.CategoryView response = new CategoryService.CategoryView(
            categoryId, "Bebidas", "BEBIDAS", "ACTIVE", Instant.now(), 25L
        );
        when(jdbc.query(anyString(), any(RowMapper.class), eq(categoryId)))
            .thenReturn(List.of(response));

        CategoryService.CategoryView result = service.getById(categoryId);
        assertThat(result.id()).isEqualTo(categoryId);
        assertThat(result.name()).isEqualTo("Bebidas");
        assertThat(result.productCount()).isEqualTo(25L);
    }

    @Test
    void list_returnsResults() {
        UUID categoryId = UUID.randomUUID();
        CategoryService.CategoryView response = new CategoryService.CategoryView(
            categoryId, "Bebidas", "BEBIDAS", "ACTIVE", Instant.now(), 25L
        );
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
            .thenReturn(List.of(response));

        List<CategoryService.CategoryView> results = service.list("beb", "ACTIVE");
        assertThat(results).hasSize(1);
    }
}
