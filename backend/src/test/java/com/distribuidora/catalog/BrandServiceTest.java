package com.distribuidora.catalog;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.api.CatalogAdminDtos;
import com.distribuidora.catalog.application.BrandService;
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

class BrandServiceTest {
    private JdbcTemplate jdbc;
    private AuditService audit;
    private CurrentUserAccess currentUser;
    private BrandService service;
    private UUID adminUserId;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        currentUser = mock(CurrentUserAccess.class);
        adminUserId = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(adminUserId);
        service = new BrandService(jdbc, audit, currentUser);
    }

    @Test
    void create_rejectsNullOrBlankName() {
        assertThatThrownBy(() -> service.create(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CatalogAdminDtos.CreateBrandRequest("")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new CatalogAdminDtos.CreateBrandRequest("   ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsDuplicateName() {
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.brands where lower(name) = ?)"), eq(Boolean.class), eq("coca cola")))
            .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CatalogAdminDtos.CreateBrandRequest("Coca Cola")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nombre");
    }

    @Test
    void create_succeedsAndAudits() {
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.brands where lower(name) = ?)"), eq(Boolean.class), eq("coca cola")))
            .thenReturn(false);
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        UUID brandId = service.create(new CatalogAdminDtos.CreateBrandRequest("Coca Cola"));

        assertThat(brandId).isNotNull();
        verify(jdbc).update(
            eq("insert into catalog.brands(id, name, status, created_at) values (?, ?, 'ACTIVE', ?)"),
            eq(brandId), eq("Coca Cola"), any(Timestamp.class)
        );
        verify(audit).record(eq(adminUserId), eq("BRAND_CREATE"), eq("BRAND"), eq(brandId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void update_rejectsNullOrBlankName() {
        UUID brandId = UUID.randomUUID();
        assertThatThrownBy(() -> service.update(brandId, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(brandId, new CatalogAdminDtos.UpdateBrandRequest("")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_rejectsNonExistentBrand() {
        UUID brandId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.brands where id = ?)"), eq(Boolean.class), eq(brandId)))
            .thenReturn(false);

        assertThatThrownBy(() -> service.update(brandId, new CatalogAdminDtos.UpdateBrandRequest("Nueva Marca")))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void update_rejectsDuplicateNameOnOtherBrand() {
        UUID brandId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.brands where id = ?)"), eq(Boolean.class), eq(brandId)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.brands where lower(name) = ? and id <> ?)"), eq(Boolean.class), eq("pepsi"), eq(brandId)))
            .thenReturn(true);

        assertThatThrownBy(() -> service.update(brandId, new CatalogAdminDtos.UpdateBrandRequest("Pepsi")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nombre");
    }

    @Test
    void update_succeeds_andAudits() {
        UUID brandId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.brands where id = ?)"), eq(Boolean.class), eq(brandId)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select exists(select 1 from catalog.brands where lower(name) = ? and id <> ?)"), eq(Boolean.class), eq("pepsi"), eq(brandId)))
            .thenReturn(false);
        when(jdbc.update(anyString(), any(), eq(brandId))).thenReturn(1);

        service.update(brandId, new CatalogAdminDtos.UpdateBrandRequest("Pepsi"));

        verify(jdbc).update(eq("update catalog.brands set name = ? where id = ?"), eq("Pepsi"), eq(brandId));
        verify(jdbc).update(eq("update catalog.products set name = ? || ' ' || description where brand_id = ? and description is not null"), eq("Pepsi"), eq(brandId));
        verify(audit).record(eq(adminUserId), eq("BRAND_UPDATE"), eq("BRAND"), eq(brandId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void setStatus_rejectsInvalidStatusOrMissingBrand() {
        UUID brandId = UUID.randomUUID();
        assertThatThrownBy(() -> service.setStatus(brandId, "OTHER"))
            .isInstanceOf(IllegalArgumentException.class);

        when(jdbc.update(anyString(), any(), eq(brandId))).thenReturn(0);
        assertThatThrownBy(() -> service.setStatus(brandId, "INACTIVE"))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void setStatus_succeeds_andAudits() {
        UUID brandId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), eq(brandId))).thenReturn(1);

        service.setStatus(brandId, "INACTIVE");

        verify(jdbc).update(eq("update catalog.brands set status = ? where id = ?"), eq("INACTIVE"), eq(brandId));
        verify(audit).record(eq(adminUserId), eq("BRAND_STATUS"), eq("BRAND"), eq(brandId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void activateAndDeactivate_delegateToSetStatus() {
        UUID brandId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), eq(brandId))).thenReturn(1);

        service.deactivate(brandId);
        verify(jdbc).update(eq("update catalog.brands set status = ? where id = ?"), eq("INACTIVE"), eq(brandId));

        service.activate(brandId);
        verify(jdbc).update(eq("update catalog.brands set status = ? where id = ?"), eq("ACTIVE"), eq(brandId));
    }

    @Test
    void getById_succeeds() {
        UUID brandId = UUID.randomUUID();
        BrandService.BrandView response = new BrandService.BrandView(
            brandId, "Coca Cola", "ACTIVE", Instant.now(), 12L
        );
        when(jdbc.query(anyString(), any(RowMapper.class), eq(brandId)))
            .thenReturn(List.of(response));

        BrandService.BrandView result = service.getById(brandId);
        assertThat(result.id()).isEqualTo(brandId);
        assertThat(result.name()).isEqualTo("Coca Cola");
        assertThat(result.productCount()).isEqualTo(12L);
    }

    @Test
    void list_returnsResults() {
        UUID brandId = UUID.randomUUID();
        BrandService.BrandView response = new BrandService.BrandView(
            brandId, "Coca Cola", "ACTIVE", Instant.now(), 12L
        );
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
            .thenReturn(List.of(response));

        List<BrandService.BrandView> results = service.list("coca", "ACTIVE");
        assertThat(results).hasSize(1);
    }
}
