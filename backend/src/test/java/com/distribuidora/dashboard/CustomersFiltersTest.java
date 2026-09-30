package com.distribuidora.dashboard;

import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomersFiltersTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        "jdbc:h2:mem:customers-filters;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
    private final ReadQueryService service = new ReadQueryService(jdbc);
    private final UUID ana = UUID.randomUUID();
    private final UUID lucia = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE SCHEMA customer");
        jdbc.execute("CREATE SCHEMA seller");
        jdbc.execute("CREATE TABLE seller.seller_profiles (id UUID PRIMARY KEY, display_name VARCHAR(120))");
        jdbc.execute("CREATE TABLE customer.customers (id UUID PRIMARY KEY, business_name VARCHAR(120), tax_id VARCHAR(30), email VARCHAR, phone VARCHAR, address VARCHAR, zone VARCHAR, seller_id UUID, price_list_id UUID, balance NUMERIC(19,4), status VARCHAR(20))");
        jdbc.update("INSERT INTO seller.seller_profiles VALUES (?, 'Ana'), (?, 'Lucía')", ana, lucia);
        customer("North debt", "30-111", ana, 100, "ACTIVE");
        customer("North credit", null, ana, -25, "ACTIVE");
        customer("North settled", "30-222", ana, 0, "ACTIVE");
        customer("North inactive", "30-333", ana, 50, "INACTIVE");
        customer("South debt", "30-444", lucia, 200, "ACTIVE");
        customer("Unassigned", null, null, 10, "ACTIVE");
    }

    @Test
    void combinesSellerBalanceStatusAndSearchBeforePaginationAndCounts() {
        var first = service.customers(0, 1, " north ", ana, true, "ACTIVE");
        var second = service.customers(1, 1, " north ", ana, true, "ACTIVE");
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).extracting(row -> row.get("NAME")).containsExactly("North credit");
        assertThat(second.content()).extracting(row -> row.get("NAME")).containsExactly("North debt");
        assertThat(service.customers(0, 20, "30-333", ana, true, "INACTIVE").totalElements()).isEqualTo(1);
        assertThat(service.customers(0, 20, "30-333", ana, true, "ACTIVE").totalElements()).isZero();
        assertThat(service.customers(0, 20, "", ana, false, "ACTIVE").totalElements()).isEqualTo(3);
        assertThat(service.customers(0, 20, "", ana, true, "").totalElements()).isEqualTo(3);
    }

    @Test
    void preservesUnfilteredConsumersAndIncludesUnassignedCustomers() {
        assertThat(service.customers(0, 20, "").totalElements()).isEqualTo(6);
        assertThat(service.customers(0, 20, "Unassigned", null, true, "ACTIVE").totalElements()).isEqualTo(1);
        assertThat(service.customers(0, 20, "Unassigned", ana, true, "ACTIVE").totalElements()).isZero();
    }

    @Test
    void neverExpandsTheAuthenticatedSellersScope() {
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.isAdmin()).thenReturn(false);
        when(access.requireSellerProfile()).thenReturn(ana);
        var scoped = new ReadQueryService(jdbc, access);
        assertThat(scoped.customers(0, 20, "", lucia, false, "").totalElements()).isZero();
        assertThat(scoped.customers(0, 20, "", null, true, "ACTIVE").totalElements()).isEqualTo(2);
        assertThat(scoped.customers(0, 20, "", null, true, "INACTIVE").totalElements()).isEqualTo(1);
        assertThat((List<?>) scoped.customerFilterOptions().get("sellers")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void includesSellerOptionsBeyondTheFirstPageAndWithInactiveCustomers() {
        UUID duplicate = UUID.randomUUID();
        jdbc.update("INSERT INTO seller.seller_profiles VALUES (?, 'Ana')", duplicate);
        for (int i = 0; i < 105; i++) customer("Extra " + i, null, ana, 0, "ACTIVE");
        customer("Only inactive", null, duplicate, 0, "INACTIVE");
        assertThat((List<Map<String, Object>>) service.customerFilterOptions().get("sellers"))
            .extracting(row -> row.get("ID")).containsExactlyInAnyOrder(ana, lucia, duplicate);
    }

    @Test
    void rejectsUnknownStates() {
        assertThatThrownBy(() -> service.customers(0, 20, "", null, false, "%"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private void customer(String name, String taxId, UUID sellerId, int balance, String status) {
        jdbc.update("INSERT INTO customer.customers (id, business_name, tax_id, seller_id, balance, status) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), name, taxId, sellerId, balance, status);
    }
}
