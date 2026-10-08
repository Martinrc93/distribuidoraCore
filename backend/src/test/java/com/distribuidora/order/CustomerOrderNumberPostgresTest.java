package com.distribuidora.order;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.api.OrderConfirmationDtos;
import com.distribuidora.order.application.OrderCalculationService;
import com.distribuidora.order.application.OrderConfirmationService;
import com.distribuidora.pricing.application.PricingQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class CustomerOrderNumberPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    static UUID firstCustomer = UUID.randomUUID();
    static UUID secondCustomer = UUID.randomUUID();
    static UUID fourthOrder = UUID.randomUUID();

    @BeforeAll static void migrateLegacyOrders() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).target("35").load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.update("insert into customer.customers(id,business_name,created_at) values (?, 'First customer', '2026-01-01'), (?, 'Second customer', '2026-01-02')", firstCustomer, secondCustomer);
        for (int index = 1; index <= 4; index++) {
            UUID id = index == 4 ? fourthOrder : UUID.randomUUID();
            jdbc.update("""
                insert into orders.orders(id,order_number,customer_id,status,subtotal,discount,total,created_at)
                values (?, ?, ?, ?, 10, 0, 10, '2026-02-01'::timestamptz + (? * interval '1 day'))
                """, id, "ORD-OLD-" + index, firstCustomer, index == 2 ? "CANCELLED" : "CONFIRMED", index);
            jdbc.update("""
                insert into sale.sales(id,sale_number,order_id,customer_id,status,total,paid,created_at)
                values (?, ?, ?, ?, 'CONFIRMED', 10, 0, now())
                """, UUID.randomUUID(), "SAL-OLD-" + index, id, firstCustomer);
        }
        jdbc.update("""
            insert into orders.orders(id,order_number,customer_id,status,subtotal,discount,total,created_at)
            values (?, 'OTHER-OLD', ?, 'CONFIRMED', 10, 0, 10, now())
            """, UUID.randomUUID(), secondCustomer);
        Flyway.configure().dataSource(source).load().migrate();
    }

    @Test void migratesExistingOrdersAndKeepsOldNumberLookupAndBusinessLinks() {
        assertThat(jdbc.queryForObject("select customer_number from customer.customers where id = ?", Integer.class, firstCustomer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select order_number from orders.orders where id = ?", String.class, fourthOrder)).isEqualTo("000100004");
        assertThat(jdbc.queryForObject("select order_number from orders.orders where legacy_order_number = 'OTHER-OLD'", String.class)).isEqualTo("000200001");
        assertThat(jdbc.queryForObject("select last_order_number from customer.customers where id = ?", Integer.class, firstCustomer)).isEqualTo(4);
        var reads = new ReadQueryService(jdbc);
        var oldLookup = (Map<?, ?>) reads.orderDetailByNumber("ORD-OLD-4").get("order");
        var newLookup = (Map<?, ?>) reads.orderDetailByNumber("000100004").get("order");
        assertThat(oldLookup.get("id")).isEqualTo(fourthOrder);
        assertThat(oldLookup.get("number")).isEqualTo("000100004");
        assertThat(newLookup).isEqualTo(oldLookup);
        assertThat(jdbc.queryForObject("select count(*) from sale.sales where order_id = ? and total = 10", Integer.class, fourthOrder)).isEqualTo(1);
    }

    @Test void numbersActualConfirmationsWithoutConsumingNumbersOnFailureOrReplayAndSerializesConcurrentOrders() throws Exception {
        UUID customer = newCustomer();
        UUID other = newCustomer();
        UUID product = UUID.randomUUID();
        UUID list = jdbc.queryForObject("select id from catalog.price_lists where code = 'GENERAL'", UUID.class);
        jdbc.update("insert into catalog.products(id,name,cost,created_at) values (?, 'Product', 10, now())", product);
        jdbc.update("insert into inventory.inventory_balances(product_id,quantity,updated_at) values (?, 100, now())", product);
        var pricing = mock(PricingQueryService.class);
        when(pricing.resolve(any(UUID.class), eq(product), isNull())).thenReturn(Map.of("priceListId", list, "priceListCode", "GENERAL", "unitPrice", BigDecimal.TEN));
        var audit = mock(AuditService.class);
        var service = new OrderConfirmationService(jdbc, pricing, new OrderCalculationService(), new InventoryMovementService(jdbc), audit);
        var first = request(customer, product, "first");
        doThrow(new IllegalStateException("audit failure")).when(audit).recordWithinTransaction(any(), anyString(), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> transactions.execute(status -> service.confirm(first))).hasMessage("audit failure");
        assertThat(jdbc.queryForObject("select last_order_number from customer.customers where id = ?", Integer.class, customer)).isZero();
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, product)).isEqualByComparingTo("100");
        reset(audit);
        var confirmed = transactions.execute(status -> service.confirm(first));
        assertThat(confirmed.orderNumber()).isEqualTo(prefix(customer) + "00001");
        var replay = transactions.execute(status -> service.confirm(first));
        assertThat(replay).isEqualTo(confirmed);
        assertThat(jdbc.queryForObject("select last_order_number from customer.customers where id = ?", Integer.class, customer)).isEqualTo(1);

        try (var executor = Executors.newFixedThreadPool(6)) {
            var requests = new ArrayList<Callable<String>>();
            for (int index = 0; index < 12; index++) {
                var request = request(customer, product, "concurrent-" + index);
                requests.add(() -> transactions.execute(status -> service.confirm(request)).orderNumber());
            }
            var numbers = new ArrayList<String>();
            for (var result : executor.invokeAll(requests)) numbers.add(result.get());
            assertThat(numbers).hasSize(12).doesNotHaveDuplicates();
            assertThat(numbers.stream().sorted().toList()).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(2, 13).mapToObj(index -> prefix(customer) + "%05d".formatted(index)).toList());
        }
        assertThat(transactions.execute(status -> service.confirm(request(other, product, "other"))).orderNumber()).isEqualTo(prefix(other) + "00001");
        assertThat(jdbc.queryForObject("select count(*) from orders.orders where customer_id = ?", Integer.class, customer)).isEqualTo(13);
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, product)).isEqualByComparingTo("86");
    }

    @Test void preservesAllDigitsAndRejectsExhaustedCountersInsteadOfTruncating() {
        UUID customer = UUID.randomUUID();
        jdbc.update("insert into customer.customers(id,business_name,created_at,customer_number,last_order_number) values (?, 'Boundary customer', now(), 9999, 99998)", customer);
        String boundary = transactions.execute(status -> jdbc.queryForObject("select orders.next_customer_order_number(?)", String.class, customer));
        assertThat(boundary).isEqualTo("999999999");
        assertThatThrownBy(() -> transactions.execute(status -> jdbc.queryForObject("select orders.next_customer_order_number(?)", String.class, customer))).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select last_order_number from customer.customers where id = ?", Integer.class, customer)).isEqualTo(99999);
    }

    UUID newCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into customer.customers(id,business_name,created_at) values (?, 'Customer', now())", id);
        return id;
    }
    String prefix(UUID id) {
        return "%04d".formatted(jdbc.queryForObject("select customer_number from customer.customers where id = ?", Integer.class, id));
    }
    OrderConfirmationDtos.ConfirmationRequest request(UUID customer, UUID product, String key) {
        return new OrderConfirmationDtos.ConfirmationRequest(key, customer, null,
            List.of(new OrderConfirmationDtos.LineRequest(product, BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of());
    }
}
