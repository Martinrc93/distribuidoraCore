package com.distribuidora.customer;

import com.distribuidora.customer.application.CustomerCommandService;
import com.distribuidora.customer.api.CustomerCommandController;
import com.distribuidora.audit.application.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class CustomerCommandServiceTest {
    private JdbcTemplate jdbc;
    private AuditService audit;
    private CustomerCommandService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        var access = mock(com.distribuidora.shared.security.CurrentUserAccess.class);
        when(access.isAdmin()).thenReturn(true);
        service = new CustomerCommandService(jdbc, audit, access);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(UUID.randomUUID().toString(), "fixture", "ADMIN_ALL"));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsBlankBusinessName() {
        assertThatThrownBy(() -> CustomerCommandService.validate(
            new CustomerCommandService.CustomerInput("", "30-71234567-1", null, null, null, null, null, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsMissingTaxId() {
        CustomerCommandService.validate(new CustomerCommandService.CustomerInput("Cliente sin identificacion", null, null, null, null, null, null, null));
    }

    @Test
    void assignsActivePriceListAndAuditsChange() {
        UUID customerId = UUID.randomUUID();
        UUID priceListId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(customerId))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(priceListId))).thenReturn("ACTIVE");
        when(jdbc.update(anyString(), any(), eq(customerId))).thenReturn(1);

        service.assignPriceList(customerId, priceListId);

        verify(jdbc).update(anyString(), eq(priceListId), eq(customerId));
        verify(audit).recordWithinTransaction(any(), eq("CUSTOMER_PRICE_LIST_ASSIGNMENT"), eq("CUSTOMER"),
            eq(customerId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void clearsPriceListAssignmentWithNull() {
        UUID customerId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(customerId))).thenReturn(true);
        when(jdbc.update(anyString(), any(), eq(customerId))).thenReturn(1);

        service.assignPriceList(customerId, null);

        verify(jdbc).update(anyString(), eq(null), eq(customerId));
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).recordWithinTransaction(any(), eq("CUSTOMER_PRICE_LIST_ASSIGNMENT"), eq("CUSTOMER"),
            eq(customerId.toString()), eq("SUCCESS"), details.capture());
        assertThat(details.getValue()).containsKey("priceListId").containsEntry("priceListId", null);
    }

    @Test
    void rejectsInactivePriceList() {
        UUID customerId = UUID.randomUUID();
        UUID priceListId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(customerId))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(priceListId))).thenReturn("INACTIVE");

        assertThatThrownBy(() -> service.assignPriceList(customerId, priceListId))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingPriceList() {
        UUID customerId = UUID.randomUUID();
        UUID priceListId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(customerId))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(priceListId)))
            .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> service.assignPriceList(customerId, priceListId))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void rejectsMissingCustomer() {
        UUID customerId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(customerId))).thenReturn(false);

        assertThatThrownBy(() -> service.assignPriceList(customerId, UUID.randomUUID()))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void requiresPriceListPermissionAndReturnsNoContent() throws Exception {
        PreAuthorize authorization = CustomerCommandController.class
            .getDeclaredMethod("assignPriceList", UUID.class, CustomerCommandController.PriceListPayload.class)
            .getAnnotation(PreAuthorize.class);
        assertThat(authorization.value()).isEqualTo("hasAnyAuthority('CUSTOMER_PRICE_LIST_CHANGE', 'ADMIN_ALL')");

        CustomerCommandService controllerService = mock(CustomerCommandService.class);
        CustomerCommandController controller = new CustomerCommandController(controllerService);
        ResponseEntity<Void> response = controller.assignPriceList(UUID.randomUUID(),
            new CustomerCommandController.PriceListPayload(null));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void rejectsNullPriceListPayload() {
        CustomerCommandController controller = new CustomerCommandController(mock(CustomerCommandService.class));

        assertThatThrownBy(() -> controller.assignPriceList(UUID.randomUUID(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resellerChangesRequireAdministratorWithResellerPermissionBeforeReadingData() {
        assertThatThrownBy(() -> service.changeKind(UUID.randomUUID(), "RESELLER"))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> service.assignReseller(UUID.randomUUID(), UUID.randomUUID()))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(jdbc, audit);
    }

    @Test
    void marksResellerAndAuditsOnlyEffectiveChanges() {
        authenticateResellerManager();
        UUID customerId = UUID.randomUUID();
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.contains("for update"), eq(customerId)))
            .thenReturn(new java.util.HashMap<>(Map.of("kind", "REGULAR")));

        service.changeKind(customerId, "RESELLER");

        verify(jdbc).update("update customer.customers set kind = ? where id = ?", "RESELLER", customerId);
        verify(audit).recordWithinTransaction(any(), eq("CUSTOMER_RESELLER_MARK"), eq("CUSTOMER"), eq(customerId.toString()),
            eq("SUCCESS"), eq(Map.of("kind", "RESELLER")));
        assertThatThrownBy(() -> service.changeKind(customerId, "OTHER")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignsOnlyActiveResellersAndReportsPendingOrdersKept() {
        authenticateResellerManager();
        UUID customerId = UUID.randomUUID();
        UUID resellerId = UUID.randomUUID();
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.contains("for update"), eq(customerId)))
            .thenReturn(new java.util.HashMap<>(Map.of("kind", "REGULAR")));
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.contains("for share"), eq(resellerId)))
            .thenReturn(Map.of("kind", "RESELLER", "status", "ACTIVE"));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("from orders.orders"), eq(Long.class), eq(customerId)))
            .thenReturn(2L);

        var result = service.assignReseller(customerId, resellerId);

        assertThat(result.pendingOrdersKept()).isEqualTo(2);
        verify(jdbc).update("update customer.customers set reseller_id = ?, reseller_kind = ? where id = ?",
            resellerId, "RESELLER", customerId);
        verify(audit).recordWithinTransaction(any(), eq("CUSTOMER_RESELLER_ASSIGN"), eq("CUSTOMER"), eq(customerId.toString()),
            eq("SUCCESS"), org.mockito.ArgumentMatchers.anyMap());
        assertThatThrownBy(() -> service.assignReseller(customerId, customerId)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInactiveOrRegularResellersWithReadableConflicts() {
        authenticateResellerManager();
        UUID customerId = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID regular = UUID.randomUUID();
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.contains("for update"), eq(customerId)))
            .thenReturn(new java.util.HashMap<>(Map.of("kind", "REGULAR")));
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.contains("for share"), eq(inactive)))
            .thenReturn(Map.of("kind", "RESELLER", "status", "INACTIVE"));
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.contains("for share"), eq(regular)))
            .thenReturn(Map.of("kind", "REGULAR", "status", "ACTIVE"));

        assertThatThrownBy(() -> service.assignReseller(customerId, inactive))
            .isInstanceOf(com.distribuidora.customer.application.ResellerConflictException.class).hasMessageContaining("no está activo");
        assertThatThrownBy(() -> service.assignReseller(customerId, regular))
            .isInstanceOf(com.distribuidora.customer.application.ResellerConflictException.class).hasMessageContaining("no es revendedor");
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    @Test
    void resellerEndpointsAreReservedToAdministratorsWithTheirPermission() throws Exception {
        for (String method : new String[]{"changeKind", "assignReseller"}) {
            var endpoint = java.util.Arrays.stream(CustomerCommandController.class.getMethods())
                .filter(candidate -> candidate.getName().equals(method)).findFirst().orElseThrow();
            assertThat(endpoint.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('ADMIN_ALL') and hasAuthority('RESELLER_MANAGE')");
        }
    }

    private void authenticateResellerManager() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.TestingAuthenticationToken(UUID.randomUUID().toString(), "fixture",
                "ADMIN_ALL", "RESELLER_MANAGE"));
    }
}
