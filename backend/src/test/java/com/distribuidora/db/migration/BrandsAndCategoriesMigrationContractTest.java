package com.distribuidora.db.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BrandsAndCategoriesMigrationContractTest {

    @Test
    void migrationCreatesBrandsAndCategoriesTables() {
        try (InputStream stream = BrandsAndCategoriesMigrationContractTest.class
                .getResourceAsStream("/db/migration/V14__create_brands_and_categories_tables.sql")) {
            assertThat(stream).as("migration resource V14").isNotNull();
            if (stream == null) {
                throw new AssertionError("Migration resource V14 is missing");
            }
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains("CREATE TABLE catalog.categories");
            assertThat(migration).contains("name VARCHAR(100) NOT NULL");
            assertThat(migration).contains("code VARCHAR(50) NOT NULL UNIQUE");
            assertThat(migration).contains("CONSTRAINT ck_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))");

            assertThat(migration).contains("CREATE TABLE catalog.brands");
            assertThat(migration).contains("CONSTRAINT ck_brand_status CHECK (status IN ('ACTIVE', 'INACTIVE'))");

            assertThat(migration).contains("ALTER TABLE catalog.products");
            assertThat(migration).contains("brand_id UUID REFERENCES catalog.brands (id)");
            assertThat(migration).contains("category_id UUID REFERENCES catalog.categories (id)");
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
