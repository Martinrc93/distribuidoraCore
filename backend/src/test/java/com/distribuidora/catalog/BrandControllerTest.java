package com.distribuidora.catalog;

import com.distribuidora.catalog.api.BrandController;
import com.distribuidora.catalog.api.CatalogAdminDtos;
import com.distribuidora.catalog.application.BrandService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BrandControllerTest {
    private BrandService service;
    private BrandController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(BrandService.class);
        controller = new BrandController(service);
        mockMvc = standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void mutationsRequireAdminAuthority() throws Exception {
        Method create = BrandController.class.getDeclaredMethod("create", CatalogAdminDtos.CreateBrandRequest.class);
        assertThat(create.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method update = BrandController.class.getDeclaredMethod("update", UUID.class, CatalogAdminDtos.UpdateBrandRequest.class);
        assertThat(update.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method status = BrandController.class.getDeclaredMethod("status", UUID.class, CatalogAdminDtos.StatusRequest.class);
        assertThat(status.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method delete = BrandController.class.getDeclaredMethod("delete", UUID.class);
        assertThat(delete.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
    }

    @Test
    void create_returnsCreatedWithId() {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(id);

        CatalogAdminDtos.CreateBrandRequest request = new CatalogAdminDtos.CreateBrandRequest("Quilmes", "QUIL");
        ResponseEntity<CatalogAdminDtos.IdResponse> response = controller.create(request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(id);
        verify(service).create(request);
    }

    @Test
    void update_returnsNoContent() {
        UUID id = UUID.randomUUID();
        CatalogAdminDtos.UpdateBrandRequest request = new CatalogAdminDtos.UpdateBrandRequest("Quilmes Clásica", "QUIL");

        ResponseEntity<Void> response = controller.update(id, request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).update(id, request);
    }

    @Test
    void status_returnsNoContent() {
        UUID id = UUID.randomUUID();
        CatalogAdminDtos.StatusRequest request = new CatalogAdminDtos.StatusRequest("INACTIVE");

        ResponseEntity<Void> response = controller.status(id, request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).setStatus(id, "INACTIVE");
    }

    @Test
    void delete_returnsNoContent() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = controller.delete(id);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).deactivate(id);
    }

    @Test
    void list_returnsOk() {
        BrandService.BrandView brand = new BrandService.BrandView(
            UUID.randomUUID(), "Quilmes", "QUIL", "ACTIVE", Instant.now(), 5L
        );
        CatalogAdminDtos.BrandResponse expected = new CatalogAdminDtos.BrandResponse(
            brand.id(), brand.name(), brand.code(), brand.status(), brand.createdAt(), brand.productCount());
        when(service.list("quil", "ACTIVE")).thenReturn(List.of(brand));

        ResponseEntity<List<CatalogAdminDtos.BrandResponse>> response = controller.list("quil", "ACTIVE");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(expected);
    }

    @Test
    void getById_returnsOk() {
        UUID id = UUID.randomUUID();
        BrandService.BrandView brand = new BrandService.BrandView(
            id, "Quilmes", "QUIL", "ACTIVE", Instant.now(), 5L
        );
        when(service.getById(id)).thenReturn(brand);

        ResponseEntity<CatalogAdminDtos.BrandResponse> response = controller.getById(id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(new CatalogAdminDtos.BrandResponse(
            brand.id(), brand.name(), brand.code(), brand.status(), brand.createdAt(), brand.productCount()));
    }

    @Test
    void httpCreateBindsRequestAndSerializesCreatedId() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(id);

        mockMvc.perform(post("/api/brands")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"name":"Quilmes","code":"QUIL"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(id.toString()));

        verify(service).create(new CatalogAdminDtos.CreateBrandRequest("Quilmes", "QUIL"));
    }

    @Test
    void httpCreateRejectsBlankNameBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/brands")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"name":"   ","code":"QUIL"}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
