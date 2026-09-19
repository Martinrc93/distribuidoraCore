package com.distribuidora.order;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class OrderConfirmationMigrationContractTest {

    private static final String MIGRATION_RESOURCE =
            "/db/migration/V6__prepare_order_confirmation.sql";

    @Test
    void migrationDefinesOrderConfirmationSchemaContract() {
        String migration = readMigration();
        String normalized = normalize(migration);

        assertThat(normalized)
                .contains("ALTER TABLE ORDERS.ORDERS ADD COLUMN IDEMPOTENCY_KEY VARCHAR(100) NULL")
                .contains("ADD COLUMN IDEMPOTENCY_FINGERPRINT VARCHAR(64) NULL")
                .contains("CREATE UNIQUE INDEX UX_ORDERS_IDEMPOTENCY_KEY ON ORDERS.ORDERS (IDEMPOTENCY_KEY) WHERE IDEMPOTENCY_KEY IS NOT NULL")
                .contains("ALTER TABLE ORDERS.ORDER_ITEMS ADD COLUMN PRICE_LIST_ID UUID NULL")
                .contains("ADD COLUMN PRICE_LIST_CODE VARCHAR(40) NOT NULL DEFAULT 'GENERAL'")
                .contains("ADD COLUMN LINE_DISCOUNT_PERCENT NUMERIC(19,4) NOT NULL DEFAULT 0")
                .contains("ALTER TABLE SALE.SALE_ITEMS ADD COLUMN PRICE_LIST_ID UUID NULL")
                .contains("ADD COLUMN PRICE_LIST_CODE VARCHAR(40) NOT NULL DEFAULT 'GENERAL'")
                .contains("ADD COLUMN LINE_DISCOUNT_PERCENT NUMERIC(19,4) NOT NULL DEFAULT 0")
                .contains("CREATE TABLE CUSTOMER.ACCOUNT_LEDGER")
                .contains("ENTRY_TYPE VARCHAR(10) NOT NULL")
                .contains("CONSTRAINT CK_ACCOUNT_LEDGER_ENTRY_TYPE CHECK (ENTRY_TYPE IN ('DEBIT', 'CREDIT'))")
                .contains("AMOUNT NUMERIC(19,4) NOT NULL")
                .contains("CONSTRAINT CK_ACCOUNT_LEDGER_AMOUNT CHECK (AMOUNT > 0)")
                .contains("CREATE INDEX IX_ACCOUNT_LEDGER_CUSTOMER_CREATED_AT ON CUSTOMER.ACCOUNT_LEDGER (CUSTOMER_ID, CREATED_AT DESC)");

        assertThat(normalized.indexOf("PRICE_LIST_CODE VARCHAR(40) NOT NULL DEFAULT 'GENERAL'"))
                .isLessThan(normalized.indexOf("ALTER COLUMN PRICE_LIST_CODE DROP DEFAULT"));
        assertThat(normalized.indexOf("LINE_DISCOUNT_PERCENT NUMERIC(19,4) NOT NULL DEFAULT 0"))
                .isLessThan(normalized.indexOf("ALTER COLUMN LINE_DISCOUNT_PERCENT DROP DEFAULT"));
        assertThat(normalized.indexOf("ALTER COLUMN PRICE_LIST_CODE DROP DEFAULT"))
                .isGreaterThan(normalized.indexOf("ALTER TABLE SALE.SALE_ITEMS ADD COLUMN PRICE_LIST_ID UUID NULL"));
        assertThat(normalized.indexOf("ALTER COLUMN LINE_DISCOUNT_PERCENT DROP DEFAULT"))
                .isGreaterThan(normalized.indexOf("ALTER TABLE SALE.SALE_ITEMS ADD COLUMN PRICE_LIST_ID UUID NULL"));
    }

    private static String readMigration() {
        try (InputStream stream = OrderConfirmationMigrationContractTest.class.getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(stream).as("migration resource").isNotNull();
            if (stream == null) {
                throw new AssertionError("Migration resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }
}
