package com.distribuidora.document;

import com.distribuidora.document.application.AccountStatementDocumentService;
import com.distribuidora.document.application.PriceListDocumentService;
import com.distribuidora.document.rendering.AccountStatementPdfRenderer;
import com.distribuidora.document.rendering.PriceListPdfRenderer;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class DocumentExportPostgresTest {
    private static final UUID GENERAL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),
            System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora"),
            System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora"));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private void rollback(Runnable body) {
        transactions.executeWithoutResult(status -> {
            try { body.run(); } finally { status.setRollbackOnly(); }
        });
    }

    private static String text(byte[] pdf) {
        try {
            var reader = new PdfReader(pdf);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) text.append(new PdfTextExtractor(reader).getTextFromPage(page));
            return text.toString();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void priceListDocumentGroupsCurrentPricesByCategoryAndAppliesFilters() {
        rollback(() -> {
            UUID drinks = UUID.randomUUID();
            UUID grocery = UUID.randomUUID();
            jdbc.update("insert into catalog.categories(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', now())", drinks, "Bebidas " + drinks, "T_" + drinks);
            jdbc.update("insert into catalog.categories(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', now())", grocery, "Almacén " + grocery, "T_" + grocery);
            UUID soda = product("Gaseosa cola", drinks, "1500.5", "ACTIVE");
            product("Harina 000", grocery, "900", "ACTIVE");
            product("Gaseosa vieja", drinks, "10", "INACTIVE");

            var service = new PriceListDocumentService(jdbc);
            var model = service.load(GENERAL, drinks, null, null);
            assertThat(model.lines()).extracting(line -> line.product()).containsExactly("Gaseosa cola");
            assertThat(model.filters()).contains("Bebidas");
            String pdf = text(new PriceListPdfRenderer().render(model));
            assertThat(pdf).contains("Lista de precios", "Gaseosa cola", "1.500,50").doesNotContain("Harina 000", "Gaseosa vieja");

            assertThat(service.load(GENERAL, null, null, "harina").lines()).extracting(line -> line.product()).contains("Harina 000").doesNotContain("Gaseosa cola");
            assertThat(soda).isNotNull();
        });
    }

    @Test
    void accountStatementListsOpenDebtsOldestFirstAndRendersA4AndTicket() {
        rollback(() -> {
            UUID customer = UUID.randomUUID();
            jdbc.update("insert into customer.customers(id, business_name, tax_id, balance, status, created_at) values (?, 'Almacén Estado', ?, 0, 'ACTIVE', now())", customer, "ST-" + customer);
            debtSale(customer, "100", "30", 7200);
            debtSale(customer, "50", "0", 3600);
            debtSale(customer, "40", "40", 1800);

            CurrentUserAccess admin = mock(CurrentUserAccess.class);
            when(admin.isAdmin()).thenReturn(true);
            var model = new AccountStatementDocumentService(jdbc, admin).load(customer);
            assertThat(model.lines()).hasSize(2);
            assertThat(model.lines().getFirst().due()).isEqualByComparingTo("70");
            assertThat(model.totalDue()).isEqualByComparingTo("120");

            var renderer = new AccountStatementPdfRenderer();
            assertThat(text(renderer.renderA4(model))).contains("Estado de deuda", "Almacén Estado", "120,00");
            assertThat(text(renderer.renderTicket(model))).contains("ESTADO DE DEUDA", "TOTAL PENDIENTE");
        });
    }

    private UUID product(String name, UUID category, String price, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products(id, name, category_id, cost, status, created_at) values (?, ?, ?, 1, ?, now())", id, name, category, status);
        jdbc.update("""
            insert into catalog.product_price_history(id, price_list_id, product_id, price, effective_on, created_at, updated_at)
            values (?, ?, ?, ?, (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date, now(), now())
            """, UUID.randomUUID(), GENERAL, id, new BigDecimal(price));
        return id;
    }

    private void debtSale(UUID customer, String total, String paid, int secondsOlder) {
        UUID order = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        Timestamp createdAt = Timestamp.from(Instant.now().minusSeconds(secondsOlder));
        jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, discount, total, created_at) values (?, ?, ?, 'CONFIRMED', ?, 0, ?, ?)",
            order, "ST-" + order.toString().substring(0, 24), customer, new BigDecimal(total), new BigDecimal(total), createdAt);
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)",
            sale, "SALE-" + sale.toString().substring(0, 20), order, customer, new BigDecimal(total), new BigDecimal(paid), createdAt);
        jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)",
            UUID.randomUUID(), customer, sale, new BigDecimal(total), createdAt);
        if (new BigDecimal(paid).signum() > 0) {
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', ?, ?)",
                UUID.randomUUID(), customer, sale, new BigDecimal(paid), createdAt);
        }
    }
}
