package com.distribuidora.identity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationMigrationContractTest {

    private static final String MIGRATION_RESOURCE =
            "/db/migration/V8__seed_roles_and_permissions.sql";

    @Test
    void migrationSeedsCanonicalAuthorizationRowsIdempotently() {
        String migration = normalize(readMigration());

        assertThat(migration)
                .contains("INSERT INTO IDENTITY.ROLES (ID, CODE, DESCRIPTION)")
                .contains("'ADMIN'")
                .contains("'SELLER'")
                .contains("ON CONFLICT (CODE) DO NOTHING")
                .contains("INSERT INTO IDENTITY.PERMISSIONS (ID, CODE, DESCRIPTION)")
                .contains("'ADMIN_ALL'")
                .contains("'USER_MANAGE'")
                .contains("'ORDER_CREATE'")
                .contains("'SALE_DELIVER'")
                .contains("INSERT INTO IDENTITY.ROLE_PERMISSIONS (ROLE_ID, PERMISSION_ID)")
                .contains("WHERE R.CODE = 'ADMIN' AND P.CODE IN ('ADMIN_ALL', 'USER_MANAGE')")
                .contains("WHERE R.CODE = 'SELLER' AND P.CODE IN ('ORDER_CREATE', 'SALE_DELIVER')")
                .contains("ON CONFLICT (ROLE_ID, PERMISSION_ID) DO NOTHING");
    }

    private static String readMigration() {
        try (InputStream stream = AuthorizationMigrationContractTest.class.getResourceAsStream(MIGRATION_RESOURCE)) {
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
