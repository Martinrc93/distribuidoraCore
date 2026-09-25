package com.distribuidora.catalog;

import com.distribuidora.catalog.api.CatalogAdminDtos;
import com.distribuidora.catalog.api.CategoryController;
import com.distribuidora.catalog.application.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CategoryControllerTest {
    private CategoryService service;
    private CategoryController controller;

    @BeforeEach
    void setUp() {
        service = mock(CategoryService.class);
        controller = new CategoryController(service);
    }

    @Test
    void mutationsRequireAdminAuthority() throws Exception {
        Method create = CategoryController.class.getDeclaredMethod("create", CatalogAdminDtos.CreateCategoryRequest.class);
        assertThat(create.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method update = CategoryController.class.getDeclaredMethod("update", UUID.class, CatalogAdminDtos.UpdateCategoryRequest.class);
        assertThat(update.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method status = CategoryController.class.getDeclaredMethod("status", UUID.class, CatalogAdminDtos.StatusRequest.class);
        assertThat(status.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method delete = CategoryController.class.getDeclaredMethod("delete", UUID.class);
        assertThat(delete.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
    }

    @Test
    void create_returnsCreatedWithId() {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(id);

        CatalogAdminDtos.CreateCategoryRequest request = new CatalogAdminDtos.CreateCategoryRequest("Bebidas", "BEB");
        ResponseEntity<CatalogAdminDtos.IdResponse> response = controller.create(request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(id);
        verify(service).create(request);
    }

    @Test
    void update_returnsNoContent() {
        UUID id = UUID.randomUUID();
        CatalogAdminDtos.UpdateCategoryRequest request = new CatalogAdminDtos.UpdateCategoryRequest("Bebidas con Alcohol", "BEB");

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
        CatalogAdminDtos.CategoryResponse category = new CatalogAdminDtos.CategoryResponse(
            UUID.randomUUID(), "Bebidas", "BEB", "ACTIVE", Instant.now(), 10L
        );
        when(service.list("beb", "ACTIVE")).thenReturn(List.of(category));

        ResponseEntity<List<CatalogAdminDtos.CategoryResponse>> response = controller.list("beb", "ACTIVE");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsExactly(category);
    }

    @Test
    void getById_returnsOk() {
        UUID id = UUID.randomUUID();
        CatalogAdminDtos.CategoryResponse category = new CatalogAdminDtos.CategoryResponse(
            id, "Bebidas", "BEB", "ACTIVE", Instant.now(), 10L
        );
        when(service.getById(id)).thenReturn(category);

        ResponseEntity<CatalogAdminDtos.CategoryResponse> response = controller.getById(id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(category);
    }
}
