package com.distribuidora.identity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SalePaymentPermissionMigrationContractTest {
    @Test
    void grantsAccountPaymentPermissionToSellerRole() {
        String migration = readMigration().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);

        assertThat(migration)
            .contains("'SALE_PAYMENT', 'REGISTRAR PAGOS DE CUENTA CORRIENTE'")
            .contains("R.CODE = 'SELLER' AND P.CODE = 'SALE_PAYMENT'")
            .contains("ON CONFLICT (ROLE_ID, PERMISSION_ID) DO NOTHING")
            .doesNotContain("DROP TABLE", "DELETE FROM", "TRUNCATE");
    }

    private String readMigration() {
        try (InputStream stream = getClass().getResourceAsStream(
            "/db/migration/V17__add_sale_payment_permission.sql")) {
            assertThat(stream).isNotNull();
            if (stream == null) throw new AssertionError("Migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
