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
        service = new CustomerCommandService(jdbc, audit);
    }

    @Test
    void rejectsBlankBusinessName() {
        assertThatThrownBy(() -> CustomerCommandService.validate(
            new CustomerCommandService.CustomerInput("", "30-71234567-1", null)))
            .isInstanceOf(IllegalArgumentException.class);
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
        verify(audit).record(any(), eq("CUSTOMER_PRICE_LIST_ASSIGNMENT"), eq("CUSTOMER"),
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
        verify(audit).record(any(), eq("CUSTOMER_PRICE_LIST_ASSIGNMENT"), eq("CUSTOMER"),
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
    void requiresAdminAuthorityAndReturnsNoContent() throws Exception {
        PreAuthorize authorization = CustomerCommandController.class
            .getDeclaredMethod("assignPriceList", UUID.class, CustomerCommandController.PriceListPayload.class)
            .getAnnotation(PreAuthorize.class);
        assertThat(authorization.value()).isEqualTo("hasAuthority('ADMIN_ALL')");

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
}
