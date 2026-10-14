package com.distribuidora.order;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.customer.application.CustomerCommandService;
import com.distribuidora.dashboard.application.CustomerReadService;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class CustomerNumberCapacityPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test void upgradesWithoutRenumberingAndPreservesLongNumbersInCreationReadsAndOrders() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).target("36").load().migrate();
        var jdbc = new JdbcTemplate(source);
        UUID smallCustomer = UUID.randomUUID();
        UUID existingCustomer = UUID.randomUUID();
        jdbc.update("""
            insert into customer.customers(id,business_name,created_at,customer_number,last_order_number)
            values (?, 'Small customer', now(), 1, 0), (?, 'Existing customer', now(), 9999, 1)
            """, smallCustomer, existingCustomer);
        UUID existingOrder = insertOrder(jdbc, existingCustomer, "999900001");
        jdbc.queryForObject("select setval('customer.customer_number_seq', 9999)", Long.class);

        Flyway.configure().dataSource(source).load().migrate();
        var commands = new CustomerCommandService(jdbc, mock(AuditService.class));
        var details = new CustomerReadService(jdbc, mock(CurrentUserAccess.class));
        var reads = new ReadQueryService(jdbc);
        assertThat(details.customer(smallCustomer).get("number")).isEqualTo("0001");
        assertThat(details.customer(existingCustomer).get("number")).isEqualTo("9999");
        assertThat(jdbc.queryForObject("select last_value from customer.customer_number_seq", Long.class)).isEqualTo(9999);
        assertThat(jdbc.queryForObject("select order_number from orders.orders where id = ?", String.class, existingOrder)).isEqualTo("999900001");
        assertThat(jdbc.queryForObject("select orders.next_customer_order_number(?)", String.class, existingCustomer)).isEqualTo("999900002");

        for (long expected : new long[] {10_000L, 2_147_483_648L, Long.MAX_VALUE}) {
            if (expected > 10_000L) jdbc.queryForObject("select setval('customer.customer_number_seq', ?)", Long.class, expected - 1);
            String name = "Expanded customer " + expected;
            UUID customer = commands.create(new CustomerCommandService.CustomerInput(name, null, null, null, null, null, null, null));
            String number = Long.toString(expected);
            assertThat(jdbc.queryForObject("select customer_number from customer.customers where id = ?", Long.class, customer)).isEqualTo(expected);
            assertThat(details.customer(customer).get("number")).isEqualTo(number);
            assertThat(reads.customers(0, 20, name).content()).singleElement().satisfies(row -> assertThat(row.get("number")).isEqualTo(number));
            String orderNumber = jdbc.queryForObject("select orders.next_customer_order_number(?)", String.class, customer);
            assertThat(orderNumber).isEqualTo(number + "00001");
            UUID order = insertOrder(jdbc, customer, orderNumber);
            assertThat(jdbc.queryForObject("select order_number from orders.orders where id = ?", String.class, order)).isEqualTo(orderNumber);
        }

        assertThatThrownBy(() -> jdbc.update("insert into customer.customers(id,business_name,created_at,customer_number) values (?, 'Invalid customer', now(), 0)", UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into customer.customers(id,business_name,created_at,customer_number) values (?, 'Duplicate customer', now(), 9999)", UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class);
    }

    private UUID insertOrder(JdbcTemplate jdbc, UUID customer, String number) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into orders.orders(id,order_number,customer_id,status,subtotal,discount,total,created_at)
            values (?, ?, ?, 'CONFIRMED', 10, 0, 10, now())
            """, id, number, customer);
        return id;
    }
}
