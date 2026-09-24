package com.distribuidora.seller;

import com.distribuidora.seller.api.SellerCommandController;
import com.distribuidora.seller.api.SellerDtos;
import com.distribuidora.seller.application.SellerCommandService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SellerCommandControllerTest {
    private SellerCommandService service;
    private SellerCommandController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(SellerCommandService.class);
        controller = new SellerCommandController(service);
        mockMvc = standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
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

        Method reassignCustomersMethod = SellerCommandController.class.getDeclaredMethod("reassignCustomers", SellerDtos.ReassignCustomersRequest.class);
        assertThat(reassignCustomersMethod.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");

        Method reassignOrdersMethod = SellerCommandController.class.getDeclaredMethod("reassignOrders", SellerDtos.ReassignOrdersRequest.class);
        assertThat(reassignOrdersMethod.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
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

    @Test
    void reassignCustomers_returnsOkWithResponse() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        SellerDtos.ReassignCustomersRequest request = new SellerDtos.ReassignCustomersRequest(s1, s2, null, true);
        SellerDtos.ReassignCustomersResponse expected = new SellerDtos.ReassignCustomersResponse(s1, s2, 5, 2);

        when(service.reassignCustomers(request)).thenReturn(new SellerCommandService.ReassignCustomersResult(
            expected.sourceSellerId(), expected.targetSellerId(), expected.reassignedCustomersCount(), expected.reassignedOrdersCount()));

        ResponseEntity<SellerDtos.ReassignCustomersResponse> response = controller.reassignCustomers(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(service).reassignCustomers(request);
    }

    @Test
    void reassignOrders_returnsOkWithResponse() {
        UUID s2 = UUID.randomUUID();
        UUID o1 = UUID.randomUUID();
        SellerDtos.ReassignOrdersRequest request = new SellerDtos.ReassignOrdersRequest(s2, java.util.List.of(o1), true);
        SellerDtos.ReassignOrdersResponse expected = new SellerDtos.ReassignOrdersResponse(s2, 1);

        when(service.reassignOrders(request)).thenReturn(new SellerCommandService.ReassignOrdersResult(
            expected.targetSellerId(), expected.reassignedOrdersCount()));

        ResponseEntity<SellerDtos.ReassignOrdersResponse> response = controller.reassignOrders(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(service).reassignOrders(request);
    }

    @Test
    void httpCreateBindsRequestAndSerializesSellerId() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        when(service.create(any())).thenReturn(sellerId);

        mockMvc.perform(post("/api/sellers")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"userId":"%s","displayName":"Vendedor Uno"}
                    """.formatted(userId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(sellerId.toString()));

        verify(service).create(new SellerDtos.CreateSellerRequest(userId, "Vendedor Uno"));
    }

    @Test
    void httpCreateRejectsMissingUserIdBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/sellers")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"displayName":"Vendedor Uno"}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
