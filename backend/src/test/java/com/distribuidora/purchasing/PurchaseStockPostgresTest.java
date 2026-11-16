package com.distribuidora.purchasing;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.purchasing.application.SupplierOrderService;
import com.distribuidora.purchasing.application.SupplierOrderService.LineInput;
import com.distribuidora.purchasing.application.SupplierOrderService.OrderInput;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class PurchaseStockPostgresTest {
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private SupplierOrderService service;
    private UUID supplier;
    private UUID flour;
    private UUID rice;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),
            System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora"),
            System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora"));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @BeforeEach
    void fixtures() {
        service = new SupplierOrderService(jdbc, mock(AuditService.class), mock(CurrentUserAccess.class));
    }

    private void rollback(Runnable body) {
        transactions.executeWithoutResult(status -> {
            try {
                supplier = UUID.randomUUID();
                flour = UUID.randomUUID();
                rice = UUID.randomUUID();
                jdbc.update("insert into supplier.suppliers(id, name, created_at, updated_at) values (?, ?, now(), now())", supplier, "Purchase supplier " + supplier);
                for (UUID product : List.of(flour, rice)) {
                    jdbc.update("insert into catalog.products(id, name, description, cost, created_at) values (?, 'Purchase product', 'Purchase product', 10, now())", product);
                    jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, 5, now())", product);
                }
                body.run();
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private OrderInput input(boolean confirm, BigDecimal flourQuantity, BigDecimal riceQuantity) {
        var lines = riceQuantity == null
            ? List.of(new LineInput(flour, flourQuantity, null))
            : List.of(new LineInput(flour, flourQuantity, null), new LineInput(rice, riceQuantity, null));
        return new OrderInput(supplier, LocalDate.of(2026, 11, 16), lines, UUID.randomUUID().toString(), confirm);
    }

    private BigDecimal stock(UUID product) {
        return jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, product);
    }

    private String status(UUID order) {
        return jdbc.queryForObject("select status from purchasing.supplier_orders where id = ?", String.class, order);
    }

    @Test
    void draftDoesNotMoveStockUntilConfirmedAndCannotBeConfirmedTwice() {
        rollback(() -> {
            var draft = service.create(input(false, new BigDecimal("3"), null));
            assertThat(draft.status()).isEqualTo("DRAFT");
            assertThat(stock(flour)).isEqualByComparingTo("5");

            var detail = service.confirm(draft.id());
            assertThat(((Map<?, ?>) detail.get("order")).get("status")).isEqualTo("CONFIRMED");
            assertThat(stock(flour)).isEqualByComparingTo("8");
            assertThat(jdbc.queryForObject("select movement_type from inventory.stock_movements where reference_id = ?", String.class, draft.id()))
                .isEqualTo("PURCHASE");
            assertThatThrownBy(() -> service.confirm(draft.id())).isInstanceOf(IllegalStateException.class);
            assertThat(stock(flour)).isEqualByComparingTo("8");
        });
    }

    @Test
    void editingAConfirmedPurchaseMovesOnlyTheDifferenceAndCancellingRevertsIt() {
        rollback(() -> {
            var confirmed = service.create(input(true, new BigDecimal("4"), new BigDecimal("2")));
            assertThat(confirmed.status()).isEqualTo("CONFIRMED");
            assertThat(stock(flour)).isEqualByComparingTo("9");
            assertThat(stock(rice)).isEqualByComparingTo("7");

            // Flour 4 -> 6.5 (+2.5), rice removed (-2).
            service.update(confirmed.id(), input(false, new BigDecimal("6.5"), null));
            assertThat(stock(flour)).isEqualByComparingTo("11.5");
            assertThat(stock(rice)).isEqualByComparingTo("5");
            assertThat(status(confirmed.id())).isEqualTo("CONFIRMED");

            service.cancel(confirmed.id());
            assertThat(status(confirmed.id())).isEqualTo("CANCELLED");
            assertThat(stock(flour)).isEqualByComparingTo("5");
            assertThat(stock(rice)).isEqualByComparingTo("5");
            assertThatThrownBy(() -> service.update(confirmed.id(), input(false, BigDecimal.ONE, null))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.cancel(confirmed.id())).isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void draftsAndLegacyPurchasesNeverTouchStockAndQuantitiesMustBeHalfUnits() {
        rollback(() -> {
            var draft = service.create(input(false, new BigDecimal("2"), null));
            service.update(draft.id(), input(false, new BigDecimal("10"), null));
            service.cancel(draft.id());
            assertThat(stock(flour)).isEqualByComparingTo("5");

            var legacy = service.create(input(false, new BigDecimal("1"), null));
            jdbc.update("update purchasing.supplier_orders set status = 'LEGACY' where id = ?", legacy.id());
            service.update(legacy.id(), new OrderInput(supplier, LocalDate.of(2026, 11, 16),
                List.of(new LineInput(flour, new BigDecimal("1.25"), null)), null));
            assertThat(status(legacy.id())).isEqualTo("LEGACY");
            assertThatThrownBy(() -> service.confirm(legacy.id())).isInstanceOf(IllegalStateException.class);
            service.cancel(legacy.id());
            assertThat(stock(flour)).isEqualByComparingTo("5");

            assertThatThrownBy(() -> service.create(input(false, new BigDecimal("1.25"), null))).isInstanceOf(IllegalArgumentException.class);
            var other = service.create(input(true, new BigDecimal("1"), null));
            assertThatThrownBy(() -> service.update(other.id(), input(false, new BigDecimal("1.25"), null))).isInstanceOf(IllegalArgumentException.class);
        });
    }
}
