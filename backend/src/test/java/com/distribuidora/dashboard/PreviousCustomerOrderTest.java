package com.distribuidora.dashboard;

import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.dashboard.api.ReadQueryController;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

class PreviousCustomerOrderTest {
    private JdbcTemplate jdbc;
    private ReadQueryService service;
    private final UUID customerId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        for (String schema : List.of("orders", "customer", "catalog", "inventory")) jdbc.execute("create schema " + schema);
        jdbc.execute("create table customer.customers(id uuid primary key, seller_id uuid)");
        jdbc.execute("create table orders.orders(id uuid primary key, order_number varchar, customer_id uuid, seller_id uuid, status varchar, created_at timestamp with time zone, order_discount_percent decimal)");
        jdbc.execute("create table catalog.products(id uuid primary key, name varchar, sku varchar, presentation varchar, status varchar)");
        jdbc.execute("create table inventory.inventory_balances(product_id uuid primary key, quantity decimal)");
        jdbc.execute("create table orders.order_items(id uuid primary key, order_id uuid, product_id uuid, product_name varchar, quantity decimal, line_discount_percent decimal)");
        jdbc.update("insert into customer.customers values (?, ?)", customerId, sellerId);
        jdbc.update("insert into catalog.products values (?, 'Current name', 'SKU-1', 'Bag', 'ACTIVE')", productId);
        jdbc.update("insert into inventory.inventory_balances values (?, 20)", productId);
        service = new ReadQueryService(jdbc);
    }

    @Test
    void loadsLatestNonCancelledOrderForExactCustomerWithCurrentProductMetadata() {
        insert("OLD", customerId, sellerId, "CONFIRMED", "2026-08-01T12:00:00Z");
        UUID expected = insert("LATEST", customerId, sellerId, "DELIVERED", "2026-09-19T12:00:00Z");
        insert("CANCELLED", customerId, sellerId, "CANCELLED", "2026-09-20T12:00:00Z");
        UUID otherCustomer = UUID.randomUUID();
        jdbc.update("insert into customer.customers values (?, ?)", otherCustomer, sellerId);
        insert("OTHER", otherCustomer, sellerId, "CONFIRMED", "2026-09-30T12:00:00Z");
        var result = service.lastCustomerOrder(customerId);
        assertThat(result).containsEntry("available", true).containsEntry("orderId", expected).containsEntry("orderNumber", "LATEST");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("productName", "Current name").containsEntry("SKU", "SKU-1");
        assertThat(items.getFirst().get("QUANTITY").toString()).isEqualTo("3");
        assertThat(items.getFirst().get("lineDiscountPercent").toString()).isEqualTo("10");
    }

    @Test
    void returnsUnavailableWhenNoPreviousOrderExists() {
        assertThat(service.lastCustomerOrder(customerId)).containsExactlyEntriesOf(Map.of("available", false));
        insert("CANCELLED", customerId, sellerId, "CANCELLED", "2026-09-30T12:00:00Z");
        assertThat(service.lastCustomerOrder(customerId)).containsExactlyEntriesOf(Map.of("available", false));
    }

    @Test
    void keepsSellerScopeAndCustomerOwnership() {
        UUID expected = insert("OWN", customerId, null, "CONFIRMED", "2026-09-19T12:00:00Z");
        insert("OTHER-SELLER", customerId, UUID.randomUUID(), "DELIVERED", "2026-09-20T12:00:00Z");
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.requireSellerProfile()).thenReturn(sellerId);
        var scoped = new ReadQueryService(jdbc, access);
        assertThat(scoped.lastCustomerOrder(customerId)).containsEntry("orderId", expected);
        verify(access).requireCustomerAccess(customerId);
        doThrow(new EmptyResultDataAccessException(1)).when(access).requireCustomerAccess(customerId);
        assertThatThrownBy(() -> scoped.lastCustomerOrder(customerId)).isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void preservesUnavailableProductsForTheUiToExplainWhyImportIsBlocked() {
        insert("LATEST", customerId, sellerId, "CONFIRMED", "2026-09-19T12:00:00Z");
        jdbc.update("delete from catalog.products where id = ?", productId);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) service.lastCustomerOrder(customerId).get("items");
        assertThat(items.getFirst()).containsEntry("productName", "Historic name").containsEntry("STATUS", null);
    }

    @Test
    void bindsCustomerIdAndReturnsExplicitAvailability() throws Exception {
        ReadQueryService queries = mock(ReadQueryService.class);
        when(queries.lastCustomerOrder(customerId)).thenReturn(Map.of("available", false));
        standaloneSetup(new ReadQueryController(queries)).build()
            .perform(get("/api/customers/{customerId}/last-order", customerId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        verify(queries).lastCustomerOrder(customerId);
    }

    private UUID insert(String number, UUID customer, UUID seller, String status, String date) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into orders.orders values (?, ?, ?, ?, ?, ?, 5)", id, number, customer, seller, status, Timestamp.from(Instant.parse(date)));
        jdbc.update("insert into orders.order_items values (?, ?, ?, 'Historic name', 3, 10)", UUID.randomUUID(), id, productId);
        return id;
    }
}
