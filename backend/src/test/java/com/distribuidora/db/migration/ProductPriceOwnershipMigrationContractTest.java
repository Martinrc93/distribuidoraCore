package com.distribuidora.db.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductPriceOwnershipMigrationContractTest {

    @Test
    void migrationMovesProductPricesToPriceListsAndDropsPriceColumn() {
        try (InputStream stream = ProductPriceOwnershipMigrationContractTest.class
                .getResourceAsStream("/db/migration/V11__move_product_prices_to_price_lists.sql")) {
            assertThat(stream).as("migration resource V11").isNotNull();
            if (stream == null) {
                throw new AssertionError("Migration resource V11 is missing");
            }
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains("INSERT INTO catalog.product_prices");
            assertThat(migration).contains("FROM catalog.price_lists");
            assertThat(migration).contains("status = 'ACTIVE'");
            assertThat(migration).contains("DROP CONSTRAINT ck_product_amounts");
            assertThat(migration).contains("DROP COLUMN price");
            assertThat(migration).contains("ADD CONSTRAINT ck_product_cost CHECK (cost >= 0)");
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
