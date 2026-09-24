package com.distribuidora.seller;

import com.distribuidora.seller.api.SellerCommandController;
import com.distribuidora.seller.api.SellerDtos;
import com.distribuidora.seller.application.SellerCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerCommandControllerTest {
    private SellerCommandService service;
    private SellerCommandController controller;

    @BeforeEach
    void setUp() {
        service = mock(SellerCommandService.class);
        controller = new SellerCommandController(service);
    }

    @Test
    void allEndpointsRequireAdminAuthority() throws Exception {
        Method createMethod = SellerCommandController.class.getDeclaredMethod("create", SellerDtos.CreateSellerRequest.class);
        assertThat(createMethod.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method updateMethod = SellerCommandController.class.getDeclaredMethod("update", UUID.class, SellerDtos.UpdateSellerRequest.class);
        assertThat(updateMethod.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method statusMethod = SellerCommandController.class.getDeclaredMethod("status", UUID.class, SellerDtos.SellerStatusRequest.class);
        assertThat(statusMethod.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method activateMethod = SellerCommandController.class.getDeclaredMethod("activate", UUID.class);
        assertThat(activateMethod.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method deactivateMethod = SellerCommandController.class.getDeclaredMethod("deactivate", UUID.class);
        assertThat(deactivateMethod.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method deleteMethod = SellerCommandController.class.getDeclaredMethod("delete", UUID.class);
        assertThat(deleteMethod.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
    }

    @Test
    void create_returnsCreatedWithId() {
        UUID sellerId = UUID.randomUUID();
        when(service.create(any())).thenReturn(sellerId);

        SellerDtos.CreateSellerRequest request = new SellerDtos.CreateSellerRequest(UUID.randomUUID(), "Vendedor Uno");
        ResponseEntity<SellerDtos.IdResponse> response = controller.create(request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(sellerId);
        verify(service).create(request);
    }

    @Test
    void update_returnsNoContent() {
        UUID sellerId = UUID.randomUUID();
        SellerDtos.UpdateSellerRequest request = new SellerDtos.UpdateSellerRequest("Vendedor Actualizado");

        ResponseEntity<Void> response = controller.update(sellerId, request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).update(sellerId, request);
    }

    @Test
    void status_returnsNoContent() {
        UUID sellerId = UUID.randomUUID();
        SellerDtos.SellerStatusRequest request = new SellerDtos.SellerStatusRequest("INACTIVE");

        ResponseEntity<Void> response = controller.status(sellerId, request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).setStatus(sellerId, "INACTIVE");
    }

    @Test
    void activate_returnsNoContent() {
        UUID sellerId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.activate(sellerId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).activate(sellerId);
    }

    @Test
    void deactivate_returnsNoContent() {
        UUID sellerId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.deactivate(sellerId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).deactivate(sellerId);
    }

    @Test
    void delete_callsDeactivateAndReturnsNoContent() {
        UUID sellerId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.delete(sellerId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).deactivate(sellerId);
    }
}
