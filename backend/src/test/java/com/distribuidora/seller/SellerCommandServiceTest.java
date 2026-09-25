package com.distribuidora.seller;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.seller.api.SellerDtos;
import com.distribuidora.seller.application.SellerCommandService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerCommandServiceTest {
    private JdbcTemplate jdbc;
    private AuditService audit;
    private CurrentUserAccess currentUser;
    private SellerCommandService service;
    private UUID adminUserId;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        currentUser = mock(CurrentUserAccess.class);
        adminUserId = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(adminUserId);
        service = new SellerCommandService(jdbc, audit, currentUser);
    }

    @Test
    void create_rejectsNullOrBlankFields() {
        assertThatThrownBy(() -> service.create(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new SellerDtos.CreateSellerRequest(null, "Juan")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new SellerDtos.CreateSellerRequest(UUID.randomUUID(), "")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new SellerDtos.CreateSellerRequest(UUID.randomUUID(), "   ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsWhenUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from identity.users where id = ?)"), eq(Boolean.class), eq(userId)))
            .thenReturn(false);

        assertThatThrownBy(() -> service.create(new SellerDtos.CreateSellerRequest(userId, "Juan Pérez")))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void create_rejectsWhenUserIsBlockedOrDisabled() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from identity.users where id = ?)"), eq(Boolean.class), eq(userId)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select status from identity.users where id = ?"), eq(String.class), eq(userId)))
            .thenReturn("BLOCKED");

        assertThatThrownBy(() -> service.create(new SellerDtos.CreateSellerRequest(userId, "Juan Pérez")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inactivo o bloqueado");
    }

    @Test
    void create_rejectsWhenUserAlreadyHasSellerProfile() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from identity.users where id = ?)"), eq(Boolean.class), eq(userId)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select status from identity.users where id = ?"), eq(String.class), eq(userId)))
            .thenReturn("ACTIVE");
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where user_id = ?)"), eq(Boolean.class), eq(userId)))
            .thenReturn(true);

        assertThatThrownBy(() -> service.create(new SellerDtos.CreateSellerRequest(userId, "Juan Pérez")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ya tiene un perfil de vendedor");
    }

    @Test
    void create_succeeds_assignsRoleAndAudits() {
        UUID userId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from identity.users where id = ?)"), eq(Boolean.class), eq(userId)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select status from identity.users where id = ?"), eq(String.class), eq(userId)))
            .thenReturn("ACTIVE");
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where user_id = ?)"), eq(Boolean.class), eq(userId)))
            .thenReturn(false);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

        UUID sellerId = service.create(new SellerDtos.CreateSellerRequest(userId, "Juan Pérez"));

        assertThat(sellerId).isNotNull();
        // Verify role assignment
        verify(jdbc).update(
            eq("insert into identity.user_roles(user_id, role_id) select ?, id from identity.roles where code = 'SELLER' on conflict do nothing"),
            eq(userId)
        );
        // Verify audit
        verify(audit).record(eq(adminUserId), eq("SELLER_CREATE"), eq("SELLER"), eq(sellerId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void update_rejectsNullOrBlankName() {
        UUID sellerId = UUID.randomUUID();
        assertThatThrownBy(() -> service.update(sellerId, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(sellerId, new SellerDtos.UpdateSellerRequest("")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(sellerId, new SellerDtos.UpdateSellerRequest("   ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_rejectsNonExistentSeller() {
        UUID sellerId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(sellerId)))
            .thenReturn(false);

        assertThatThrownBy(() -> service.update(sellerId, new SellerDtos.UpdateSellerRequest("Nuevo Nombre")))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void update_succeeds_andAudits() {
        UUID sellerId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(sellerId)))
            .thenReturn(true);
        when(jdbc.update(anyString(), any(), any())).thenReturn(1);

        service.update(sellerId, new SellerDtos.UpdateSellerRequest("Nuevo Nombre"));

        verify(jdbc).update(eq("update seller.seller_profiles set display_name = ? where id = ?"), eq("Nuevo Nombre"), eq(sellerId));
        verify(audit).record(eq(adminUserId), eq("SELLER_UPDATE"), eq("SELLER"), eq(sellerId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void setStatus_rejectsInvalidStatus() {
        UUID sellerId = UUID.randomUUID();
        assertThatThrownBy(() -> service.setStatus(sellerId, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setStatus(sellerId, "DELETED"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setStatus(sellerId, "UNKNOWN"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setStatus_rejectsNonExistentSeller() {
        UUID sellerId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.setStatus(sellerId, "INACTIVE"))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void setStatus_succeeds_andAudits() {
        UUID sellerId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), eq(sellerId))).thenReturn(1);

        service.setStatus(sellerId, "INACTIVE");

        verify(jdbc).update(eq("update seller.seller_profiles set status = ? where id = ?"), eq("INACTIVE"), eq(sellerId));
        verify(audit).record(eq(adminUserId), eq("SELLER_STATUS"), eq("SELLER"), eq(sellerId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void activateAndDeactivate_delegateToSetStatus() {
        UUID sellerId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(), eq(sellerId))).thenReturn(1);

        service.deactivate(sellerId);
        verify(jdbc).update(eq("update seller.seller_profiles set status = ? where id = ?"), eq("INACTIVE"), eq(sellerId));

        service.activate(sellerId);
        verify(jdbc).update(eq("update seller.seller_profiles set status = ? where id = ?"), eq("ACTIVE"), eq(sellerId));
    }

    @Test
    void findSellerIdByUserId_returnsOptional() {
        UUID userId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(userId)))
            .thenReturn(List.of(sellerId));

        Optional<UUID> found = service.findSellerIdByUserId(userId);
        assertThat(found).contains(sellerId);

        when(jdbc.query(anyString(), any(RowMapper.class), eq(userId)))
            .thenReturn(List.of());
        assertThat(service.findSellerIdByUserId(userId)).isEmpty();
    }

    @Test
    void reassignCustomers_validatesRequestParameters() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();

        assertThatThrownBy(() -> service.reassignCustomers(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reassignCustomers(new SellerDtos.ReassignCustomersRequest(null, s2, null, true)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reassignCustomers(new SellerDtos.ReassignCustomersRequest(s1, null, null, true)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reassignCustomers(new SellerDtos.ReassignCustomersRequest(s1, s1, null, true)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no pueden ser el mismo");
    }

    @Test
    void reassignCustomers_rejectsWhenSourceDoesNotExist() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(source)))
            .thenReturn(false);

        assertThatThrownBy(() -> service.reassignCustomers(new SellerDtos.ReassignCustomersRequest(source, target, null, true)))
            .isInstanceOf(EmptyResultDataAccessException.class)
            .hasMessageContaining("origen");
    }

    @Test
    void reassignCustomers_rejectsWhenTargetDoesNotExist() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(source)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(target)))
            .thenReturn(false);

        assertThatThrownBy(() -> service.reassignCustomers(new SellerDtos.ReassignCustomersRequest(source, target, null, true)))
            .isInstanceOf(EmptyResultDataAccessException.class)
            .hasMessageContaining("destino");
    }

    @Test
    void reassignCustomers_rejectsWhenTargetIsInactive() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(source)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(target)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select status from seller.seller_profiles where id = ?"), eq(String.class), eq(target)))
            .thenReturn("INACTIVE");

        assertThatThrownBy(() -> service.reassignCustomers(new SellerDtos.ReassignCustomersRequest(source, target, null, true)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("debe estar activo");
    }

    @Test
    void reassignCustomers_reassignsAllCustomersAndPendingOrders_andAudits() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(source)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(target)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select status from seller.seller_profiles where id = ?"), eq(String.class), eq(target)))
            .thenReturn("ACTIVE");

        when(jdbc.update(eq("update customer.customers set seller_id = ? where seller_id = ?"), eq(target), eq(source)))
            .thenReturn(8);
        when(jdbc.update(eq("update orders.orders set seller_id = ? where seller_id = ? and status = 'CONFIRMED'"), eq(target), eq(source)))
            .thenReturn(3);

        SellerDtos.ReassignCustomersResponse response = service.reassignCustomers(
            new SellerDtos.ReassignCustomersRequest(source, target, null, true)
        );

        assertThat(response.sourceSellerId()).isEqualTo(source);
        assertThat(response.targetSellerId()).isEqualTo(target);
        assertThat(response.reassignedCustomersCount()).isEqualTo(8);
        assertThat(response.reassignedOrdersCount()).isEqualTo(3);

        verify(audit).record(eq(adminUserId), eq("SELLER_CUSTOMER_REASSIGNMENT"), eq("SELLER"), eq(target.toString()), eq("SUCCESS"), any());
    }

    @Test
    void reassignCustomers_reassignsSpecificCustomersWithoutOrdersWhenDisabled() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();

        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(source)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(target)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select status from seller.seller_profiles where id = ?"), eq(String.class), eq(target)))
            .thenReturn("ACTIVE");

        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2);

        SellerDtos.ReassignCustomersResponse response = service.reassignCustomers(
            new SellerDtos.ReassignCustomersRequest(source, target, List.of(c1, c2), false)
        );

        assertThat(response.reassignedCustomersCount()).isEqualTo(2);
        assertThat(response.reassignedOrdersCount()).isEqualTo(0);
    }

    @Test
    void reassignOrders_rejectsInvalidTargetOrEmptyOrders() {
        UUID target = UUID.randomUUID();
        assertThatThrownBy(() -> service.reassignOrders(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reassignOrders(new SellerDtos.ReassignOrdersRequest(null, List.of(UUID.randomUUID()), true)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reassignOrders(new SellerDtos.ReassignOrdersRequest(target, List.of(), true)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reassignOrders_reassignsConfirmedOrdersAndAudits() {
        UUID target = UUID.randomUUID();
        UUID o1 = UUID.randomUUID();
        UUID o2 = UUID.randomUUID();

        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(target)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select status from seller.seller_profiles where id = ?"), eq(String.class), eq(target)))
            .thenReturn("ACTIVE");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2);

        SellerDtos.ReassignOrdersResponse response = service.reassignOrders(
            new SellerDtos.ReassignOrdersRequest(target, List.of(o1, o2), true)
        );

        assertThat(response.targetSellerId()).isEqualTo(target);
        assertThat(response.reassignedOrdersCount()).isEqualTo(2);
        verify(audit).record(eq(adminUserId), eq("SELLER_ORDER_REASSIGNMENT"), eq("SELLER"), eq(target.toString()), eq("SUCCESS"), any());
    }

    @Test
    void reassignOrdersRejectsRequestsThatCouldModifyHistoricalOrders() {
        UUID target = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select exists(select 1 from seller.seller_profiles where id = ?)"), eq(Boolean.class), eq(target)))
            .thenReturn(true);
        when(jdbc.queryForObject(eq("select status from seller.seller_profiles where id = ?"), eq(String.class), eq(target)))
            .thenReturn("ACTIVE");

        assertThatThrownBy(() -> service.reassignOrders(
            new SellerDtos.ReassignOrdersRequest(target, List.of(UUID.randomUUID()), false)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CONFIRMED");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
