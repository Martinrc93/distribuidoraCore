package com.distribuidora.order;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryLifecycleMigrationContractTest {

    private static final String MIGRATION_RESOURCE =
            "/db/migration/V7__add_delivery_cancellation_support.sql";

    @Test
    void migrationDefinesDeliveryAndCancellationSchemaContract() {
        String normalized = normalize(readMigration());

        assertThat(normalized)
                .contains("ALTER TABLE ORDERS.ORDERS ADD COLUMN DELIVERED_AT TIMESTAMPTZ NULL, ADD COLUMN CANCELLED_AT TIMESTAMPTZ NULL")
                .contains("ALTER TABLE SALE.SALES ADD COLUMN DELIVERED_AT TIMESTAMPTZ NULL, ADD COLUMN CANCELLED_AT TIMESTAMPTZ NULL")
                .contains("CREATE TABLE ORDERS.DELIVERY_ATTEMPTS")
                .contains("ID UUID PRIMARY KEY")
                .contains("ORDER_ID UUID NOT NULL REFERENCES ORDERS.ORDERS (ID)")
                .contains("ATTEMPT_NUMBER INTEGER NOT NULL")
                .contains("RESULT VARCHAR(20) NOT NULL")
                .contains("OBSERVATION TEXT NULL")
                .contains("ATTEMPTED_BY UUID NOT NULL REFERENCES IDENTITY.USERS (ID)")
                .contains("ATTEMPTED_AT TIMESTAMPTZ NOT NULL")
                 .contains("CONSTRAINT CK_DELIVERY_ATTEMPT_NUMBER CHECK (ATTEMPT_NUMBER > 0)")
                 .contains("CONSTRAINT CK_DELIVERY_ATTEMPT_RESULT CHECK (RESULT IN ('DELIVERED', 'FAILED'))")
                 .contains("CONSTRAINT CK_DELIVERY_ATTEMPT_FAILED_OBSERVATION CHECK ( RESULT <> 'FAILED' OR (OBSERVATION IS NOT NULL AND BTRIM(OBSERVATION) <> '') )")
                 .contains("CONSTRAINT UX_DELIVERY_ATTEMPT_ORDER_NUMBER UNIQUE (ORDER_ID, ATTEMPT_NUMBER)")
                 .contains("CREATE INDEX IX_DELIVERY_ATTEMPTS_ORDER_TIME ON ORDERS.DELIVERY_ATTEMPTS (ORDER_ID, ATTEMPTED_AT DESC)")
                 .doesNotContain("CREATE INDEX IX_DELIVERY_ATTEMPTS_ORDER_ATTEMPT ON ORDERS.DELIVERY_ATTEMPTS (ORDER_ID, ATTEMPT_NUMBER)")
                 .doesNotContain("DROP TABLE")
                .doesNotContain("DELETE FROM")
                .doesNotContain("TRUNCATE");
    }

    @Test
    void paymentMigrationAddsOptionalTransferReference() {
        String normalized = normalize(readMigration("/db/migration/V16__add_payment_transfer_reference.sql"));

        assertThat(normalized).contains("ALTER TABLE PAYMENT.PAYMENTS ADD COLUMN TRANSFER_REFERENCE VARCHAR(100) NULL")
            .doesNotContain("DROP TABLE", "DELETE FROM", "TRUNCATE");
    }

    private static String readMigration() {
        return readMigration(MIGRATION_RESOURCE);
    }

    private static String readMigration(String resource) {
        try (InputStream stream = DeliveryLifecycleMigrationContractTest.class.getResourceAsStream(resource)) {
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
