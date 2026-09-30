package com.distribuidora.pricing;

import com.distribuidora.pricing.application.PricingQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class PricingBatchPostgresTest {
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrateDisposableDatabase() {
        var datasource = new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),
            System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora"),
            System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora"));
        Flyway.configure().dataSource(datasource).load().migrate();
        jdbc = new JdbcTemplate(datasource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
    }

    @Test
    void batchMatchesIndividualPricesIncludingFallbackScheduledPricesMissingProductsAndZero() {
        transactions.executeWithoutResult(transaction -> {
            try {
                UUID customerId = UUID.randomUUID();
                UUID first = product();
                UUID second = product();
                UUID missing = product();
                UUID general = UUID.fromString("00000000-0000-0000-0000-000000000001");
                UUID previous = UUID.fromString("00000000-0000-0000-0000-000000000002");
                UUID selected = UUID.fromString("00000000-0000-0000-0000-000000000003");
                jdbc.update("insert into customer.customers(id, business_name, price_list_id, created_at) values (?, 'Batch test', ?, current_timestamp)", customerId, selected);
                price(general, first, "20", 0, 0);
                price(previous, first, "25", 0, -1);
                price(previous, first, "30", 0, 0);
                price(selected, first, "999", 1, 0);
                price(general, second, "50", 0, 0);
                price(selected, second, "0", 0, 0);
                var service = new PricingQueryService(jdbc);
                var batch = service.resolveBatch(customerId, List.of(first, second, missing, first), null);
                assertThat(batch).hasSize(2);
                for (UUID product : List.of(first, second)) {
                    var actual = batch.stream().filter(row -> row.get("productId").equals(product)).findFirst().orElseThrow();
                    var individual = service.resolve(customerId, product, null);
                    assertThat(actual).containsEntry("priceListId", individual.get("priceListId"))
                        .containsEntry("priceListCode", individual.get("priceListCode"))
                        .containsEntry("unitPrice", individual.get("unitPrice"));
                }
                assertThat((BigDecimal) batch.stream().filter(row -> row.get("productId").equals(first)).findFirst().orElseThrow().get("unitPrice")).isEqualByComparingTo("30");
                assertThat((BigDecimal) batch.stream().filter(row -> row.get("productId").equals(second)).findFirst().orElseThrow().get("unitPrice")).isZero();
                jdbc.update("update catalog.price_lists set status = 'INACTIVE' where id = ?", previous);
                assertThat(service.resolveBatch(customerId, List.of(first), selected).getFirst())
                    .containsEntry("priceListId", general).containsEntry("unitPrice", new BigDecimal("20.0000"));
                assertThat(service.resolveBatch(customerId, List.of(first), general).getFirst())
                    .containsEntry("priceListId", general);
                assertThatThrownBy(() -> service.resolveBatch(customerId, List.of(first), previous)).isInstanceOf(IllegalStateException.class);
            } finally {
                transaction.setRollbackOnly();
            }
        });
    }

    private UUID product() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products(id, name, cost, created_at) values (?, 'Batch test', 0, current_timestamp)", id);
        return id;
    }

    private void price(UUID listId, UUID productId, String value, int days, int seconds) {
        jdbc.update("""
            insert into catalog.product_price_history(id, price_list_id, product_id, price, effective_on, created_at, updated_at)
            values (?, ?, ?, ?, (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date + ?,
                current_timestamp + ? * interval '1 second', current_timestamp)
            """, UUID.randomUUID(), listId, productId, new BigDecimal(value), days, seconds);
    }
}
