package com.distribuidora.sale;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SaleReturnMigrationContractTest {
    @Test
    void createsAppendOnlyReturnRecordsWithQuantityConstraints() {
        String sql = readMigration().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        assertThat(sql)
            .contains("CREATE TABLE SALE.RETURNS")
            .contains("SALE_ID UUID NOT NULL REFERENCES SALE.SALES (ID)")
            .contains("RETURNED_BY UUID NOT NULL REFERENCES IDENTITY.USERS (ID)")
            .contains("CREATE TABLE SALE.RETURN_ITEMS")
            .contains("SALE_ID UUID NOT NULL")
            .contains("FOREIGN KEY (RETURN_ID, SALE_ID) REFERENCES SALE.RETURNS (ID, SALE_ID)")
            .contains("FOREIGN KEY (SALE_ITEM_ID, SALE_ID) REFERENCES SALE.SALE_ITEMS (ID, SALE_ID)")
            .contains("QUANTITY > 0 AND MOD(QUANTITY * 2, 1) = 0")
            .contains("UNIQUE (RETURN_ID, SALE_ITEM_ID)")
            .doesNotContain("DROP TABLE", "DELETE FROM", "TRUNCATE");
    }

    private String readMigration() {
        try (InputStream stream = getClass().getResourceAsStream("/db/migration/V15__add_sale_returns.sql")) {
            assertThat(stream).isNotNull();
            if (stream == null) throw new AssertionError("Migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
