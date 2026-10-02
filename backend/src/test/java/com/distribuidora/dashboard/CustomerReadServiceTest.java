package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.CustomerDetailController;
import com.distribuidora.dashboard.application.CustomerReadService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CustomerReadServiceTest {
    private JdbcTemplate jdbc;
    private CustomerReadService service;
    private final UUID customerId = UUID.randomUUID();
    private final UUID otherCustomer = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID otherSeller = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID priceList = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
        for (String schema : List.of("customer", "orders", "seller", "catalog")) jdbc.execute("create schema " + schema);
        jdbc.execute("create table seller.seller_profiles(id uuid primary key, user_id uuid, display_name varchar, status varchar)");
        jdbc.execute("create table catalog.price_lists(id uuid primary key, code varchar, name varchar)");
        jdbc.execute("create table customer.customers(id uuid primary key, business_name varchar, tax_id varchar, email varchar, phone varchar, address varchar, zone varchar, seller_id uuid, price_list_id uuid, balance decimal(19,4), status varchar, created_at timestamp with time zone)");
        jdbc.execute("create table orders.orders(id uuid primary key, customer_id uuid, seller_id uuid, order_number varchar, total decimal(19,4), status varchar, created_at timestamp with time zone)");
        jdbc.update("insert into seller.seller_profiles values (?, ?, 'Lucía', 'ACTIVE'), (?, ?, 'Martín', 'ACTIVE')", sellerId, userId, otherSeller, UUID.randomUUID());
        jdbc.update("insert into catalog.price_lists values (?, 'GENERAL', 'General')", priceList);
        jdbc.update("insert into customer.customers values (?, 'Almacén Norte', '30-123', 'cliente@example.test', '123456', 'San Martín 100', 'Centro', ?, ?, 100.50, 'INACTIVE', ?)", customerId, sellerId, priceList, Timestamp.from(Instant.parse("2025-01-01T12:00:00Z")));
        jdbc.update("insert into customer.customers (id, business_name, seller_id, balance, status) values (?, 'Almacén Norte', ?, 0, 'ACTIVE')", otherCustomer, otherSeller);
        service = new CustomerReadService(jdbc, new CurrentUserAccess(jdbc));
        authenticate(userId, "ADMIN_ALL");
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void returnsAllStoredDetailsIncludingInactiveCustomerAndAssignedList() {
        var result = service.customer(customerId);
        assertThat(result.get("id")).isEqualTo(customerId);
        assertThat(result.get("email")).isEqualTo("cliente@example.test");
        assertThat(result.get("phone")).isEqualTo("123456");
        assertThat(result.get("address")).isEqualTo("San Martín 100");
        assertThat(result.get("zone")).isEqualTo("Centro");
        assertThat(result.get("seller")).isEqualTo("Lucía");
        assertThat(result.get("priceList")).isEqualTo("General");
        assertThat(result.get("priceListCode")).isEqualTo("GENERAL");
        assertThat(result.get("status")).isEqualTo("INACTIVE");
        assertThat(result.get("balance").toString()).isEqualTo("100.5000");
    }

    @Test
    void preservesCustomersWithoutOptionalDetailsOrAssignments() {
        jdbc.update("update customer.customers set seller_id = null, price_list_id = null, email = null, tax_id = null where id = ?", customerId);
        assertThat(service.customer(customerId).get("seller")).isEqualTo("Sin asignar");
        assertThat(service.customer(customerId).get("priceList")).isNull();
        assertThat(service.orders(customerId, 0, 20).totalElements()).isZero();
    }

    @Test
    void pagesOnlyExactCustomerHistoryIncludingCancelledAndOldOrders() {
        order(customerId, sellerId, "OLD", "CONFIRMED", "2025-01-01T12:00:00Z");
        order(customerId, sellerId, "DELIVERED", "DELIVERED", "2025-02-01T12:00:00Z");
        order(customerId, otherSeller, "CANCELLED", "CANCELLED", "2025-03-01T12:00:00Z");
        order(otherCustomer, otherSeller, "OTHER", "CONFIRMED", "2026-10-01T12:00:00Z");
        var first = service.orders(customerId, 0, 2);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).extracting(row -> row.get("number")).containsExactly("CANCELLED", "DELIVERED");
        assertThat(service.orders(customerId, 1, 2).content()).extracting(row -> row.get("number")).containsExactly("OLD");
    }

    @Test
    void sellerHistoryRetainsOrderOwnershipIncludingInheritedAssignment() {
        order(customerId, null, "INHERITED", "DELIVERED", "2025-01-01T12:00:00Z");
        order(customerId, sellerId, "OWN", "CONFIRMED", "2025-02-01T12:00:00Z");
        order(customerId, otherSeller, "FOREIGN", "CANCELLED", "2025-03-01T12:00:00Z");
        authenticate(userId, "ORDER_CREATE");
        assertThat(service.customer(customerId).get("id")).isEqualTo(customerId);
        var history = service.orders(customerId, 0, 1);
        assertThat(history.totalElements()).isEqualTo(2);
        assertThat(history.content()).extracting(row -> row.get("number")).containsExactly("OWN");
        assertThat(service.orders(customerId, 1, 1).content()).extracting(row -> row.get("seller")).containsExactly("Lucía");
        assertThatThrownBy(() -> service.customer(otherCustomer)).isInstanceOf(EmptyResultDataAccessException.class);
        assertThatThrownBy(() -> service.orders(otherCustomer, 0, 20)).isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void rejectsMissingCustomerAndUserWithoutAnActiveSellerProfile() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> service.customer(missing)).isInstanceOf(EmptyResultDataAccessException.class);
        assertThatThrownBy(() -> service.orders(missing, 0, 20)).isInstanceOf(EmptyResultDataAccessException.class);
        authenticate(UUID.randomUUID(), "ORDER_CREATE");
        assertThatThrownBy(() -> service.customer(customerId)).isInstanceOf(EmptyResultDataAccessException.class);
        assertThatThrownBy(() -> service.orders(customerId, 0, 20)).isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void clampsPaginationAndReturnsEmptyPageBeyondHistory() {
        order(customerId, sellerId, "ONE", "CONFIRMED", "2025-01-01T12:00:00Z");
        assertThat(service.orders(customerId, -1, 0).page()).isZero();
        assertThat(service.orders(customerId, -1, 0).size()).isEqualTo(1);
        assertThat(service.orders(customerId, 0, 200).size()).isEqualTo(100);
        var result = service.orders(customerId, Integer.MAX_VALUE, 100);
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void controllerBindsExactIdAndHistoryPaginationAndRejectsMalformedIds() throws Exception {
        var queries = mock(CustomerReadService.class);
        when(queries.customer(customerId)).thenReturn(Map.of("id", customerId, "name", "Almacén Norte"));
        when(queries.orders(customerId, 2, 5)).thenReturn(PageResponse.of(List.of(Map.of("number", "PED-1")), 2, 5, 12));
        var mvc = standaloneSetup(new CustomerDetailController(queries)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/customers/{id}", customerId)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Almacén Norte"));
        mvc.perform(get("/api/customers/{id}/orders?page=2&size=5", customerId)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(12));
        mvc.perform(get("/api/customers/invalid/orders")).andExpect(status().isBadRequest());
        verify(queries).orders(customerId, 2, 5);
        when(queries.customer(otherCustomer)).thenThrow(new EmptyResultDataAccessException(1));
        mvc.perform(get("/api/customers/{id}", otherCustomer)).andExpect(status().isNotFound());
    }

    private void authenticate(UUID user, String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(), null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private void order(UUID customer, UUID seller, String number, String status, String date) {
        jdbc.update("insert into orders.orders values (?, ?, ?, ?, 50, ?, ?)", UUID.randomUUID(), customer, seller, number, status, Timestamp.from(Instant.parse(date)));
    }
}
