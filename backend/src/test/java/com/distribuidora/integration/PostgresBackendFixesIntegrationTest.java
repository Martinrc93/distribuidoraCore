package com.distribuidora.integration;

import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.pricing.application.PricingCommandService;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.customer.api.AccountPaymentDtos;
import com.distribuidora.payment.application.AccountPaymentService;
import com.distribuidora.identity.api.AuthDtos;
import com.distribuidora.identity.application.AuthService;
import com.distribuidora.identity.application.InvalidRefreshTokenException;
import com.distribuidora.identity.application.UserAdminService;
import com.distribuidora.identity.application.RoleAdminService;
import com.distribuidora.identity.security.JwtService;
import com.distribuidora.identity.domain.RefreshToken;
import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.api.OrderConfirmationDtos;
import com.distribuidora.order.api.OrderEditDtos;
import com.distribuidora.order.application.DeliveryLifecycleService;
import com.distribuidora.order.application.OrderConfirmationService;
import com.distribuidora.notification.application.OutboxDispatchEvent;
import com.distribuidora.notification.application.OutboxWorker;
import com.distribuidora.notification.application.OutboxRepository;
import com.distribuidora.notification.api.NotificationDtos;
import com.distribuidora.notification.application.NotificationChannelSender;
import com.distribuidora.notification.application.NotificationRequestService;
import com.distribuidora.notification.application.NotificationRequestStateService;
import com.distribuidora.sale.api.SaleReturnDtos;
import com.distribuidora.sale.application.SaleReturnService;
import com.distribuidora.customer.application.CustomerCommandService;
import com.distribuidora.customer.application.ResellerConflictException;
import com.distribuidora.document.application.AccountStatementDocumentService;
import com.distribuidora.seller.api.SellerDtos;
import com.distribuidora.seller.application.SellerCommandService;
import com.distribuidora.settings.api.BusinessSettingsDtos;
import com.distribuidora.settings.application.BusinessSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in verification of the recent backend fixes against a disposable PostgreSQL database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class PostgresBackendFixesIntegrationTest {

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("POSTGRES_TEST_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora"));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("app.seed-demo", () -> "false");
        registry.add("app.outbox.worker.enabled", () -> "false");
        registry.add("app.notifications.retention.enabled", () -> "false");
        registry.add("app.security.jwt-secret", () -> "postgres-integration-test-secret-2026-09-23");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired AuthService authService;
    @Autowired JwtService jwtService;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserAdminService userAdminService;
    @Autowired RoleAdminService roleAdminService;
    @Autowired SellerCommandService sellerCommandService;
    @Autowired ProductCommandService productCommandService;
    @Autowired PricingCommandService pricingCommandService;
    @Autowired PricingQueryService pricingQueryService;
    @Autowired InventoryMovementService inventoryMovementService;
    @Autowired SaleReturnService saleReturnService;
    @Autowired CustomerCommandService customerCommandService;
    @Autowired AccountStatementDocumentService accountStatementDocumentService;
    @Autowired com.distribuidora.settings.application.CommissionPlansService commissionPlansService;
    @Autowired com.distribuidora.sale.application.CommissionSettlementService commissionSettlementService;
    @Autowired com.distribuidora.sale.application.CommissionAccrualService commissionAccrualService;
    @Autowired OrderConfirmationService orderConfirmationService;
    @Autowired DeliveryLifecycleService deliveryLifecycleService;
    @Autowired com.distribuidora.order.application.OrderFulfillmentService orderFulfillmentService;
    @Autowired ReadQueryService readQueryService;
    @Autowired AccountPaymentService accountPaymentService;
    @Autowired BusinessSettingsService businessSettingsService;
    @Autowired OutboxWorker outboxWorker;
    @Autowired OutboxRepository outboxRepository;
    @Autowired ApplicationEventMulticaster eventMulticaster;
    @Autowired NotificationRequestService notificationRequestService;
    @Autowired NotificationRequestStateService notificationRequestStateService;
    @Autowired MeterRegistry meterRegistry;
    @MockBean NotificationChannelSender notificationChannelSender;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactionManager;

    private final Set<UUID> users = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> roles = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> sellers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> customers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> orders = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> sales = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> saleItems = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> products = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> brands = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> categories = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> commissionPlans = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> notificationRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> auditResources = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanFixtures() {
        SecurityContextHolder.clearContext();
        jdbc.update("update app.business_settings set credit_limit = null, updated_by = null, updated_at = null where id = 1");
        deleteIds("delete from customer.account_ledger where sale_id in (%s)", sales);
        deleteIds("delete from payment.payments where sale_id in (%s)", sales);
        deleteIds("delete from sale.commission_entries where sale_item_id in (select id from sale.sale_items where sale_id in (%s))", sales);
        deleteIds("delete from sale.commission_settlements where seller_id in (%s)", sellers);
        deleteIds("delete from sale.commission_settlements where reseller_id in (%s)", customers);
        deleteIds("delete from sale.return_items where sale_id in (%s)", sales);
        deleteIds("delete from sale.returns where sale_id in (%s)", sales);
        deleteIds("delete from sale.sale_items where sale_id in (%s)", sales);
        deleteIds("delete from sale.sales where id in (%s)", sales);
        deleteIds("delete from orders.order_items where order_id in (%s)", orders);
        deleteIds("delete from orders.delivery_attempts where order_id in (%s)", orders);
        deleteIds("delete from notification.outbox_events where aggregate_id in (%s)", notificationRequests);
        deleteIds("delete from notification.outbox_events where aggregate_id in (%s)", orders);
        deleteIds("delete from orders.orders where id in (%s)", orders);
        deleteIds("delete from app.reseller_commission_plans where customer_id in (%s)", customers);
        deleteIds("delete from customer.customers where id in (%s)", customers);
        deleteIds("delete from app.seller_commission_plans where seller_id in (%s)", sellers);
        deleteIds("delete from app.commission_plans where id in (%s)", commissionPlans);
        deleteIds("delete from inventory.stock_movements where product_id in (%s)", products);
        deleteIds("delete from inventory.inventory_balances where product_id in (%s)", products);
        deleteIds("delete from catalog.product_price_history where product_id in (%s)", products);
        deleteIds("delete from catalog.product_prices where product_id in (%s)", products);
        deleteIds("delete from catalog.products where id in (%s)", products);
        deleteIds("delete from seller.seller_profiles where id in (%s)", sellers);
        deleteIds("delete from identity.refresh_tokens where user_id in (%s)", users);
        deleteIds("delete from identity.user_activation_tokens where user_id in (%s)", users);
        deleteIds("delete from identity.user_roles where user_id in (%s)", users);
        deleteIds("delete from identity.users where id in (%s)", users);
        deleteIds("delete from identity.role_permissions where role_id in (%s)", roles);
        deleteIds("delete from identity.roles where id in (%s)", roles);
        deleteStringIds("delete from audit.audit_events where resource_id in (%s)", auditResources);
        deleteIds("delete from catalog.brands where id in (%s)", brands);
        deleteIds("delete from catalog.categories where id in (%s)", categories);
        List<UUID> resources = new ArrayList<>();
        resources.addAll(users);
        resources.addAll(sellers);
        resources.addAll(customers);
        resources.addAll(orders);
        resources.addAll(sales);
        resources.addAll(products);
        resources.addAll(notificationRequests);
        if (!resources.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(resources.size(), "?"));
            jdbc.update("delete from audit.audit_events where resource_id in (" + placeholders + ")",
                resources.stream().map(UUID::toString).toArray());
        }
        deleteIds("delete from audit.audit_events where actor_user_id in (%s)", users);
        users.clear();
        roles.clear();
        sellers.clear();
        customers.clear();
        orders.clear();
        sales.clear();
        saleItems.clear();
        products.clear();
        brands.clear();
        categories.clear();
        notificationRequests.clear();
        auditResources.clear();
    }

    @Test
    void refreshTokenLookupSerializesConcurrentPostgresTransactions() throws Exception {
        UUID userId = createUser("lock");
        String hash = AuthService.hashToken("pg-lock-" + UUID.randomUUID());
        RefreshToken token = refreshTokens.saveAndFlush(new RefreshToken(userId, hash, Instant.now().plusSeconds(600)));
        CountDownLatch firstLockedRow = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Future<?> holder = executor.submit(() -> tx.execute(status -> {
                assertThat(refreshTokens.findByTokenHash(hash)).isPresent();
                firstLockedRow.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout holding row lock");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return null;
            }));
            assertThat(firstLockedRow.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch contenderStarted = new CountDownLatch(1);
            Future<?> contender = executor.submit(() -> {
                contenderStarted.countDown();
                return tx.execute(status -> refreshTokens.findByTokenHash(hash).orElseThrow());
            });
            assertThat(contenderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> contender.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(((RefreshToken) contender.get(5, TimeUnit.SECONDS)).getId()).isEqualTo(token.getId());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void refreshReplayRevokesSessionsButLogoutRevocationDoesNot() {
        UUID replayUser = createUser("replay");
        AuthService.LoginResult login = authService.login(new AuthDtos.LoginRequest(email(replayUser), "Passw0rd!"));
        AuthService.LoginResult rotated = authService.refresh(new AuthDtos.RefreshRequest(login.refreshToken()));
        assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(login.refreshToken());

        assertThatThrownBy(() -> authService.refresh(new AuthDtos.RefreshRequest(login.refreshToken())))
            .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, replayUser)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from identity.refresh_tokens where user_id = ? and revoked_at is null", Long.class, replayUser)).isZero();

        UUID logoutUser = createUser("logout");
        AuthService.LoginResult logoutLogin = authService.login(new AuthDtos.LoginRequest(email(logoutUser), "Passw0rd!"));
        AuthService.LoginResult anotherSession = authService.login(new AuthDtos.LoginRequest(email(logoutUser), "Passw0rd!"));
        authService.logout(new AuthDtos.LogoutRequest(logoutLogin.refreshToken()));
        assertThatThrownBy(() -> authService.refresh(new AuthDtos.RefreshRequest(logoutLogin.refreshToken())))
            .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, logoutUser)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from identity.refresh_tokens where user_id = ? and revoked_at is null", Long.class, logoutUser)).isEqualTo(1L);
        assertThat(anotherSession.refreshToken()).isNotBlank();
    }

    @Test
    void adminSessionActionsPersistVersionStatusAndTokenRevocation() {
        UUID userId = createUser("admin-session");
        refreshTokens.saveAndFlush(new RefreshToken(userId, AuthService.hashToken("pg-admin-token-1-" + userId), Instant.now().plusSeconds(600)));
        refreshTokens.saveAndFlush(new RefreshToken(userId, AuthService.hashToken("pg-admin-token-2-" + userId), Instant.now().plusSeconds(600)));
        UUID actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));

        userAdminService.revokeSessions(userId);
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, userId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from identity.refresh_tokens where user_id = ? and revoked_at is not null", Long.class, userId)).isEqualTo(2L);

        userAdminService.blockUser(userId);
        assertThat(jdbc.queryForObject("select status from identity.users where id = ?", String.class, userId)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, userId)).isEqualTo(2L);

        userAdminService.unblockUser(userId);
        assertThat(jdbc.queryForObject("select status from identity.users where id = ?", String.class, userId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, userId)).isEqualTo(3L);
    }

    @Test
    void roleAdministrationListsUsersAndInvalidatesSessionsWhenPermissionsChange() {
        UUID actorId = createUser("role-admin-actor");
        UUID targetId = createUser("role-admin-target");
        UUID roleId = UUID.randomUUID();
        roles.add(roleId);
        String roleCode = "PG_ROLE_" + roleId.toString().substring(0, 8).toUpperCase();
        auditResources.add(roleCode);
        jdbc.update("insert into identity.roles(id, code, description) values (?, ?, ?)",
            roleId, roleCode, "Role administration integration test");
        jdbc.update("insert into identity.user_roles(user_id, role_id) values (?, ?)", targetId, roleId);
        refreshTokens.saveAndFlush(new RefreshToken(targetId, AuthService.hashToken("role-admin-token-" + targetId),
            Instant.now().plusSeconds(600)));
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test",
                List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));

        assertThat(readQueryService.users(0, 100, "").content()).anySatisfy(user -> {
            assertThat(user.get("id")).isEqualTo(targetId);
            assertThat(user.get("roles").toString()).contains(roleCode);
        });
        assertThat(roleAdminService.listPermissions()).extracting("code").contains("ADMIN_ALL", "ORDER_CREATE");

        var updatedRole = roleAdminService.replacePermissions(roleCode, Set.of("ORDER_CREATE"));

        assertThat(updatedRole.permissions()).containsExactly("ORDER_CREATE");
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, targetId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from identity.refresh_tokens where user_id = ? and revoked_at is not null",
            Long.class, targetId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where actor_user_id = ? and operation = ? and resource_id = ?",
            Long.class, actorId, "ROLE_PERMISSIONS_UPDATE", roleCode)).isEqualTo(1L);
        assertThatThrownBy(() -> roleAdminService.replacePermissions(roleCode, Set.of("ADMIN_ALL")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("solo se pueden asignar al rol ADMIN");

        userAdminService.changeRole(targetId, "ADMIN");
        assertThat(jdbc.queryForList("""
            select r.code from identity.user_roles ur join identity.roles r on r.id = ur.role_id
            where ur.user_id = ?
            """, String.class, targetId)).containsExactly("ADMIN");
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, targetId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where actor_user_id = ? and operation = ? and resource_id = ?",
            Long.class, actorId, "USER_ROLE_CHANGE", targetId.toString())).isEqualTo(1L);
        assertThatThrownBy(() -> userAdminService.blockUser(targetId))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("último administrador activo");
        assertThat(jdbc.queryForObject("select status from identity.users where id = ?", String.class, targetId))
            .isEqualTo("ACTIVE");
    }

    @Test
    void httpLoginLoadsPersistedAuthoritiesAndRoleChangeRejectsOldAccessToken() throws Exception {
        UUID adminId = createUser("jwt-role-admin");
        assignRole(adminId, "ADMIN");
        UUID targetId = createUser("jwt-role-target");
        String roleCode = createRoleWithPermission("ORDER_DOCUMENT_EXPORT");
        assignRole(targetId, roleCode);

        String adminToken = loginHttp(email(adminId));
        String oldTargetToken = loginHttp(email(targetId));
        assertThat(jwtService.parse(oldTargetToken).get("authorities", List.class))
            .containsExactly("ORDER_DOCUMENT_EXPORT");

        UUID missingOrderId = UUID.randomUUID();
        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", missingOrderId)
                .header("Authorization", "Bearer " + oldTargetToken))
            .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/users/{id}/role", targetId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", missingOrderId)
                .header("Authorization", "Bearer " + oldTargetToken))
            .andExpect(status().isUnauthorized());

        String newTargetToken = loginHttp(email(targetId));
        assertThat(jwtService.parse(newTargetToken).get("authorities", List.class)).contains("ADMIN_ALL");
        mockMvc.perform(get("/api/permissions").header("Authorization", "Bearer " + newTargetToken))
            .andExpect(status().isOk());
    }

    @Test
    void httpPermissionChangeRevokesOldJwtAndUpdatesNewLoginAuthorities() throws Exception {
        UUID adminId = createUser("jwt-permission-admin");
        assignRole(adminId, "ADMIN");
        UUID targetId = createUser("jwt-permission-target");
        String roleCode = createRoleWithPermission("ORDER_DOCUMENT_EXPORT");
        assignRole(targetId, roleCode);

        String adminToken = loginHttp(email(adminId));
        String oldTargetToken = loginHttp(email(targetId));
        UUID missingOrderId = UUID.randomUUID();
        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", missingOrderId)
                .header("Authorization", "Bearer " + oldTargetToken))
            .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/roles/{roleCode}/permissions", roleCode)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"permissionCodes\":[\"SALE_DELIVER\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.permissions[0]").value("SALE_DELIVER"));

        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", missingOrderId)
                .header("Authorization", "Bearer " + oldTargetToken))
            .andExpect(status().isUnauthorized());

        String newTargetToken = loginHttp(email(targetId));
        assertThat(jwtService.parse(newTargetToken).get("authorities", List.class))
            .containsExactly("SALE_DELIVER");
        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", missingOrderId)
                .header("Authorization", "Bearer " + newTargetToken))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForList("""
            select p.code from identity.role_permissions rp
            join identity.permissions p on p.id = rp.permission_id
            where rp.role_id = (select id from identity.roles where code = ?)
            """, String.class, roleCode)).containsExactly("SALE_DELIVER");
    }

    @Test
    void roleEditorMetadataAndCheckedUpdatesPreserveSessionsOnNoOpAndConflict() throws Exception {
        UUID actor = createUser("role-editor");
        String roleCode = createRoleWithPermission("ORDER_CREATE");
        assignRole(actor, roleCode);
        authenticateOrderActor(actor, "ADMIN_ALL");
        var listed = roleAdminService.listRoles().stream().filter(role -> role.code().equals(roleCode)).findFirst().orElseThrow();
        assertThat(listed.assignedToCurrentUser()).isTrue();
        var admin = roleAdminService.listRoles().stream().filter(role -> role.code().equals("ADMIN")).findFirst().orElseThrow();
        assertThat(admin.requiredPermissions()).containsExactly("ADMIN_ALL");
        long version = jdbc.queryForObject("select version from identity.users where id = ?", Long.class, actor);
        var noOp = roleAdminService.replacePermissions(roleCode, Set.of("ORDER_CREATE"), Set.of("ORDER_CREATE"));
        assertThat(noOp.currentSessionRevoked()).isFalse();
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, actor)).isEqualTo(version);

        var changed = roleAdminService.replacePermissions(roleCode, Set.of("ORDER_CREATE", "ORDER_PREPARE"), Set.of("ORDER_CREATE"));
        assertThat(changed.currentSessionRevoked()).isTrue();
        assertThat(changed.role().permissions()).containsExactlyInAnyOrder("ORDER_CREATE", "ORDER_PREPARE");
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, actor)).isEqualTo(version + 1);
        assertThatThrownBy(() -> roleAdminService.replacePermissions(roleCode, Set.of("SALE_DELIVER"), Set.of("ORDER_CREATE"))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, actor)).isEqualTo(version + 1);
        assertThat(roleAdminService.listRoles().stream().filter(role -> role.code().equals(roleCode)).findFirst().orElseThrow().permissions()).containsExactlyInAnyOrder("ORDER_CREATE", "ORDER_PREPARE");

        // HTTP clients receive the conflict rather than a successful stale overwrite.
        assignRole(actor, "ADMIN");
        String token = loginHttp(email(actor));
        mockMvc.perform(put("/api/roles/{roleCode}/permissions", roleCode).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"permissionCodes\":[\"SALE_DELIVER\"],\"expectedPermissionCodes\":[\"ORDER_CREATE\"]}"))
            .andExpect(status().isConflict());
    }

    @Test
    void customRoleCreationAndAssignmentPreserveProfilesAndRevokeSessions() throws Exception {
        UUID admin = createUser("custom-admin"); assignRole(admin, "ADMIN");
        String adminToken = loginHttp(email(admin));
        String code = "CUSTOM_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String payload = objectMapper.writeValueAsString(Map.of("code", code, "description", "Rol asignado", "permissionCodes", List.of("ORDER_PREPARE")));
        var created = mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.requiresSellerProfile").value(true))
            .andExpect(jsonPath("$.permissions.length()").value(1)).andReturn();
        roles.add(UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText()));
        auditResources.add(code);
        mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
        for (String permission : List.of("ADMIN_ALL", "USER_MANAGE", "ORDER_REVIEW", "UNKNOWN")) {
            mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("code", code + "_BAD", "description", "Inválido", "permissionCodes", List.of(permission)))))
                .andExpect(status().isBadRequest());
        }
        String address = "custom-" + UUID.randomUUID() + "@example.test";
        var account = mockMvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("email", address, "temporaryPassword", "Passw0rd!", "role", code, "displayName", "Asignado"))))
            .andExpect(status().isCreated()).andReturn();
        UUID user = UUID.fromString(objectMapper.readTree(account.getResponse().getContentAsString()).path("id").asText()); users.add(user);
        UUID profile = jdbc.queryForObject("select id from seller.seller_profiles where user_id = ? and status = 'ACTIVE'", UUID.class, user); sellers.add(profile);
        UUID customer = createCustomer(profile);
        String oldToken = loginHttp(address);
        assertThat(jwtService.parse(oldToken).get("authorities", List.class)).containsExactly("ORDER_PREPARE");
        mockMvc.perform(post("/api/roles").header("Authorization", "Bearer " + oldToken).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/users/{id}/role", user).header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SELLER\"}"))
            .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + oldToken)).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/users/{id}/role", user).header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("role", code)))).andExpect(status().isNoContent());
        assertThat(jwtService.parse(loginHttp(address)).get("authorities", List.class)).containsExactly("ORDER_PREPARE");
        assertThat(jdbc.queryForObject("select seller_id from customer.customers where id = ?", UUID.class, customer)).isEqualTo(profile);
        assertThat(jdbc.queryForObject("select count(*) from identity.user_roles where user_id = ?", Long.class, user)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from identity.refresh_tokens where user_id = ? and revoked_at is not null", Long.class, user)).isEqualTo(1L);
        UUID noProfile = createUser("custom-missing-profile");
        mockMvc.perform(put("/api/users/{id}/role", noProfile).header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("role", code)))).andExpect(status().isConflict());
        jdbc.update("update seller.seller_profiles set status = 'INACTIVE' where id = ?", profile);
        mockMvc.perform(put("/api/users/{id}/role", user).header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"SELLER\"}"))
            .andExpect(status().isConflict());
        // Restoring a legacy custom user's profile must not silently add SELLER permissions.
        assignRole(noProfile, code);
        authenticateOrderActor(admin, "ADMIN_ALL");
        UUID restored = sellerCommandService.create(new SellerDtos.CreateSellerRequest(noProfile, "Restaurado")); sellers.add(restored);
        assertThat(jdbc.queryForObject("select count(*) from identity.user_roles where user_id = ?", Long.class, noProfile)).isEqualTo(1L);
    }

    @Test
    void sellerReassignmentChangesOnlyConfirmedOrders() {
        UUID sourceSeller = createSeller("source");
        UUID targetSeller = createSeller("target");
        UUID customerId = createCustomer(sourceSeller);
        UUID confirmed = createOrder(customerId, sourceSeller, "CONFIRMED");
        UUID delivered = createOrder(customerId, sourceSeller, "DELIVERED");
        UUID cancelled = createOrder(customerId, sourceSeller, "CANCELLED");

        SellerCommandService.ReassignCustomersResult result = sellerCommandService.reassignCustomers(
            new SellerDtos.ReassignCustomersRequest(sourceSeller, targetSeller, List.of(customerId), true));

        assertThat(result.reassignedCustomersCount()).isEqualTo(1);
        assertThat(result.reassignedOrdersCount()).isEqualTo(1);
        assertThat(orderSeller(confirmed)).isEqualTo(targetSeller);
        assertThat(orderSeller(delivered)).isEqualTo(sourceSeller);
        assertThat(orderSeller(cancelled)).isEqualTo(sourceSeller);
        assertThatThrownBy(() -> sellerCommandService.reassignOrders(
            new SellerDtos.ReassignOrdersRequest(targetSeller, List.of(delivered), false)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productCreationRequiresActivePriceOverHttpAndPersistsValidPriceInPostgres() throws Exception {
        UUID adminId = createUser("product-price-http");
        assignRole(adminId, "ADMIN");
        String token = loginHttp(email(adminId));
        String productName = "HTTP-" + UUID.randomUUID().toString().substring(0, 12);
        UUID categoryId = createCategory();
        UUID brandId = createBrand();
        String base = "\"description\":\"" + productName + "\",\"categoryId\":\"" + categoryId
            + "\",\"brandId\":\"" + brandId + "\",\"presentation\":\"Unidad\",\"cost\":10";

        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + base + "}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + base + ",\"prices\":[]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(jdbc.queryForObject("select count(*) from catalog.products where name = ?", Long.class, productName)).isZero();

        MvcResult created = mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{" + base + ",\"prices\":[{\"priceListId\":\"00000000-0000-0000-0000-000000000001\",\"price\":25}]}"))
            .andExpect(status().isCreated())
            .andReturn();
        UUID productId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText());
        products.add(productId);
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_prices where product_id = ? and price_list_id = ? and price = 25",
            Long.class, productId, UUID.fromString("00000000-0000-0000-0000-000000000001"))).isEqualTo(1L);
    }

    @Test
    void commercialHttpFlowConfirmsIdempotentlyAndTracksDeliveryAgainstPostgres() throws Exception {
        UUID adminId = createUser("commercial-e2e-admin");
        assignRole(adminId, "ADMIN");
        String adminToken = loginHttp(email(adminId));

        UUID limitedUserId = createUser("commercial-e2e-limited");
        String orderOnlyRole = createRoleWithPermission("ORDER_CREATE");
        assignRole(limitedUserId, orderOnlyRole);
        String limitedToken = loginHttp(email(limitedUserId));
        mockMvc.perform(get("/api/settings/credit-limit").header("Authorization", "Bearer " + limitedToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit").header("Authorization", "Bearer " + limitedToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/settings/credit-limit")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"creditLimit\":5}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));

        MvcResult customerResponse = mockMvc.perform(post("/api/customers")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"businessName\":\"Commercial E2E " + UUID.randomUUID() + "\",\"cuitId\":null,\"sellerId\":null}"))
            .andExpect(status().isCreated())
            .andReturn();
        UUID customerId = UUID.fromString(objectMapper.readTree(customerResponse.getResponse().getContentAsString()).path("id").asText());
        customers.add(customerId);

        UUID categoryId = createCategory();
        UUID brandId = createBrand();
        MvcResult productResponse = mockMvc.perform(post("/api/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Commercial E2E product\",\"categoryId\":\"" + categoryId + "\",\"brandId\":\"" + brandId + "\",\"presentation\":\"Unidad\",\"cost\":5,\"prices\":[{\"priceListId\":\"00000000-0000-0000-0000-000000000001\",\"price\":25}]}"))
            .andExpect(status().isCreated())
            .andReturn();
        UUID productId = UUID.fromString(objectMapper.readTree(productResponse.getResponse().getContentAsString()).path("id").asText());
        products.add(productId);

        mockMvc.perform(post("/api/inventory/{productId}/adjustments", productId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":10,\"reason\":\"commercial E2E opening stock\"}"))
            .andExpect(status().isNoContent());

        String idempotencyKey = "commercial-e2e-" + UUID.randomUUID();
        String confirmationBody = "{\"idempotencyKey\":\"" + idempotencyKey + "\",\"customerId\":\"" + customerId
            + "\",\"priceListId\":null,\"lines\":[{\"productId\":\"" + productId
            + "\",\"quantity\":2,\"lineDiscountPercent\":0,\"unitPriceOverride\":null}],\"orderDiscountPercent\":0,\"payments\":[],\"depotId\":null}";
        MvcResult firstConfirmation = mockMvc.perform(post("/api/orders/confirm")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmationBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.total").value(50))
            .andExpect(jsonPath("$.creditLimitWarning.creditLimit").value(5))
            .andReturn();
        var firstJson = objectMapper.readTree(firstConfirmation.getResponse().getContentAsString());
        UUID orderId = UUID.fromString(firstJson.path("orderId").asText());
        UUID saleId = UUID.fromString(firstJson.path("saleId").asText());
        orders.add(orderId);
        sales.add(saleId);

        mockMvc.perform(post("/api/orders/confirm")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmationBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value(orderId.toString()))
            .andExpect(jsonPath("$.saleId").value(saleId.toString()));
        assertThat(jdbc.queryForObject("select count(*) from orders.orders where idempotency_key = ?", Long.class, idempotencyKey)).isEqualTo(1L);

        mockMvc.perform(get("/api/orders/{orderId}", orderId).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.order.creditLimitExceeded").value(true))
            .andExpect(jsonPath("$.items[0].productId").value(productId.toString()))
            .andExpect(jsonPath("$.sale.balance").value(50));

        mockMvc.perform(post("/api/orders/{id}/delivery-attempts", orderId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"DELIVERED\",\"observation\":\"E2E delivery\",\"payments\":[{\"method\":\"CASH\",\"amount\":10}],\"transferReference\":null}"))
            .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, productId))
            .isEqualByComparingTo("8.0000");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId))
            .isEqualByComparingTo("40.0000");
        assertThat(jdbc.queryForObject("select count(*) from orders.delivery_attempts where order_id = ?", Long.class, orderId)).isEqualTo(1L);
        mockMvc.perform(get("/api/orders/{orderId}", orderId).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.order.status").value("DELIVERED"))
            .andExpect(jsonPath("$.order.customerBalance").value(40))
            .andExpect(jsonPath("$.sale.paid").value(10))
            .andExpect(jsonPath("$.sale.balance").value(40))
            .andExpect(jsonPath("$.payments[0].amount").value(10))
            .andExpect(jsonPath("$.deliveryAttempts[0].result").value("DELIVERED"))
            .andExpect(jsonPath("$.deliveryAttempts[0].attemptNumber").value(1));
        mockMvc.perform(get("/api/customers/{customerId}/debts?page=0&size=20", customerId)
                .header("Authorization", "Bearer " + limitedToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customers/{customerId}/debts?page=0&size=20", customerId)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].saleId").value(saleId.toString()))
            .andExpect(jsonPath("$.content[0].balance").value(40));
        mockMvc.perform(get("/api/sales/{saleId}", saleId).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sale.id").value(saleId.toString()))
            .andExpect(jsonPath("$.items[0].productId").value(productId.toString()))
            .andExpect(jsonPath("$.deliveryAttempts[0].result").value("DELIVERED"));
        mockMvc.perform(get("/api/audit?page=0&size=100&search=DELIVERY_ATTEMPT")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].operation").value("DELIVERY_ATTEMPT"));
    }

    @Test
    void confirmationPaysThisOrderAndOlderDebtsSeparatelyWithoutDoubleChargingOnRetry() {
        UUID customerId = createCustomer(null);
        AccountDebtSale older = createAccountDebtSale(customerId, new BigDecimal("30"), 7200);
        AccountDebtSale newer = createAccountDebtSale(customerId, new BigDecimal("40"), 3600);
        UUID productId = createProduct("previous-debt-order");
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", productId);
        putGeneralPrice(productId, new BigDecimal("10.0000"));
        UUID actorId = createUser("previous-debt-order");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "previous-debt-order-" + UUID.randomUUID(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("10"))), null, null, null,
            List.of(new OrderConfirmationDtos.PaymentRequest("BANK_TRANSFER", new BigDecimal("35"), "TR-PREV")));

        var result = orderConfirmationService.confirm(request);
        orders.add(result.orderId());
        sales.add(result.saleId());
        assertThat(result.paid()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, older.saleId())).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, newer.saleId())).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId)).isEqualByComparingTo("35");
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where customer_id = ? and transfer_reference = 'TR-PREV'", Long.class, customerId)).isEqualTo(2L);

        var retry = orderConfirmationService.confirm(request);
        assertThat(retry.orderId()).isEqualTo(result.orderId());
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId)).isEqualByComparingTo("35");

        var excessive = new OrderConfirmationDtos.ConfirmationRequest(
            "previous-debt-excess-" + UUID.randomUUID(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), null, null, null,
            List.of(new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("100"))));
        long ordersBefore = jdbc.queryForObject("select count(*) from orders.orders where customer_id = ?", Long.class, customerId);
        assertThatThrownBy(() -> orderConfirmationService.confirm(excessive)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from orders.orders where customer_id = ?", Long.class, customerId)).isEqualTo(ordersBefore);
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId)).isEqualByComparingTo("35");
    }

    @Test
    void administratorSellerSelectionPersistsOnOrderWithoutReassigningCustomer() {
        UUID customerSellerId = createSeller("order-customer");
        UUID selectedSellerId = createSeller("order-selected");
        UUID customerId = createCustomer(customerSellerId);
        UUID productId = createProduct("selected-seller-order");
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", productId);
        putGeneralPrice(productId, new BigDecimal("10.0000"));
        UUID actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test",
                List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));

        var result = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "selected-seller-order-" + UUID.randomUUID(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), selectedSellerId));
        orders.add(result.orderId());
        sales.add(result.saleId());

        assertThat(orderSeller(result.orderId())).isEqualTo(selectedSellerId);
        assertThat(jdbc.queryForObject("select seller_id from customer.customers where id = ?", UUID.class, customerId))
            .isEqualTo(customerSellerId);
    }

    @Test
    void productCategoryAndBrandReferencesPersistAndInactiveReferencesAreRejected() {
        UUID categoryId = createCategory();
        UUID brandId = createBrand();
        UUID productId = productCommandService.create(new ProductCommandService.ProductInput(
            "PG integration product", "legacy", "unit", BigDecimal.ONE,
            List.of(new ProductCommandService.ProductPriceInput(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), BigDecimal.TEN)), categoryId, brandId));
        products.add(productId);

        assertThat(jdbc.queryForObject("select category_id from catalog.products where id = ?", UUID.class, productId)).isEqualTo(categoryId);
        assertThat(jdbc.queryForObject("select brand_id from catalog.products where id = ?", UUID.class, productId)).isEqualTo(brandId);
        assertThat(jdbc.queryForObject("select category from catalog.products where id = ?", String.class, productId)).isEqualTo("PG category");

        productCommandService.update(productId, new ProductCommandService.ProductInput(
            "PG integration product updated", "legacy update", "unit", BigDecimal.ONE, null, categoryId, brandId));
        assertThat(jdbc.queryForObject("select category_id from catalog.products where id = ?", UUID.class, productId)).isEqualTo(categoryId);
        assertThat(jdbc.queryForObject("select brand_id from catalog.products where id = ?", UUID.class, productId)).isEqualTo(brandId);

        jdbc.update("update catalog.categories set status = 'INACTIVE' where id = ?", categoryId);
        assertThatThrownBy(() -> productCommandService.create(new ProductCommandService.ProductInput(
            "Inactive category", "legacy", "unit", BigDecimal.ONE, null, categoryId, brandId)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void priceHistoryResolvesCurrentAndFuturePricesAndCancelsScheduledChange() {
        UUID productId = createProduct("price-history");
        UUID customerId = createCustomer(null);
        UUID generalListId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID actorId = UUID.randomUUID();
        auditResources.add(generalListId + ":" + productId);
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test",
                List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));

        LocalDate today = jdbc.queryForObject(
            "select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class);
        LocalDate effectiveOn = today.plusDays(7);
        pricingCommandService.setProductPrice(generalListId, productId, new BigDecimal("10.0000"));
        assertThatThrownBy(() -> pricingCommandService.setProductPrice(
            generalListId, productId, new BigDecimal("0.5000"), effectiveOn))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("menor al costo");
        pricingCommandService.setProductPrice(generalListId, productId, new BigDecimal("12.5000"), effectiveOn);

        assertThat(pricingQueryService.resolve(customerId, productId, generalListId))
            .containsEntry("unitPrice", new BigDecimal("10.0000"));
        Map<String, Object> futurePrice = pricingQueryService.resolveAsOf(
            customerId, productId, generalListId, effectiveOn);
        assertThat(futurePrice).containsEntry("unitPrice", new BigDecimal("12.5000"));
        assertThat(futurePrice.get("effectiveOn").toString()).isEqualTo(effectiveOn.toString());
        assertThat(pricingQueryService.history(generalListId, productId, 0, 20).content())
            .anySatisfy(entry -> {
                assertThat(entry.get("effectiveOn").toString()).isEqualTo(effectiveOn.toString());
                assertThat(entry.get("scheduled")).isEqualTo(true);
            });

        pricingCommandService.cancelScheduledPrice(generalListId, productId, effectiveOn);

        assertThat(pricingQueryService.resolveAsOf(customerId, productId, generalListId, effectiveOn))
            .containsEntry("unitPrice", new BigDecimal("10.0000"));
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_price_history where price_list_id = ? and product_id = ? and effective_on = ?",
            Long.class, generalListId, productId, effectiveOn)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where resource_id = ? and operation = ?",
            Long.class, generalListId + ":" + productId, "PRODUCT_PRICE_SCHEDULE_CANCEL")).isEqualTo(1L);
    }

    @Test
    void manualDiscountsPersistWithoutCommercialRuleReferences() {
        UUID customerId = createCustomer(null);
        UUID productId = createProduct("manual-discount");
        UUID generalListId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        putGeneralPrice(productId, new BigDecimal("100.0000"));
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(UUID.randomUUID().toString(), "test",
                List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));

        var result = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "manual-discounts-" + UUID.randomUUID(), customerId, generalListId,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, new BigDecimal("10"), null)),
            new BigDecimal("5"), List.of()));
        orders.add(result.orderId());
        sales.add(result.saleId());

        assertThat(result.total()).isEqualByComparingTo("85.5000");
        Map<String, Object> detail = readQueryService.orderDetail(result.orderId());
        Map<String, Object> orderSnapshot = (Map<String, Object>) detail.get("order");
        assertThat(orderSnapshot).containsEntry("orderDiscountPercent", new BigDecimal("5.0000"))
            .doesNotContainKey("orderDiscountRuleId");
        Map<String, Object> itemSnapshot = (Map<String, Object>) ((List<?>) detail.get("items")).getFirst();
        assertThat(itemSnapshot).containsEntry("lineDiscountPercent", new BigDecimal("10.0000"))
            .doesNotContainKey("discountRuleId");
        Map<String, Object> saleSnapshot = (Map<String, Object>) detail.get("sale");
        assertThat(saleSnapshot).containsEntry("orderDiscountPercent", new BigDecimal("5.0000"))
            .doesNotContainKey("orderDiscountRuleId");
        assertThat(jdbc.queryForObject("select to_regclass('catalog.commercial_discount_rules')", String.class)).isNull();

        var edited = orderConfirmationService.editConfirmed(result.orderId(), new OrderEditDtos.EditRequest(
            generalListId, List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO));
        assertThat(edited.total()).isEqualByComparingTo("100.0000");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId))
            .isEqualByComparingTo("100.0000");
    }

    @Test
    void orderLifecycleUsesSingleProductStockForSaleEditReturnAndCancellation() {
        UUID actorId = createUser("single-stock");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test",
                List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customerId = createCustomer(null);
        UUID productId = createProduct("single-stock");
        putGeneralPrice(productId, new BigDecimal("10.0000"));
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", productId);

        var firstSale = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "single-stock-first-" + UUID.randomUUID(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of()));
        orders.add(firstSale.orderId());
        sales.add(firstSale.saleId());
        assertThat(stock(productId)).isEqualByComparingTo("10.0");
        orderFulfillmentService.prepare(firstSale.orderId());
        assertThat(stock(productId)).isEqualByComparingTo("9.0");

        orderConfirmationService.editConfirmed(firstSale.orderId(), new OrderEditDtos.EditRequest(null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2.0"), BigDecimal.ZERO, null)),
            BigDecimal.ZERO));
        assertThat(stock(productId)).isEqualByComparingTo("8.0");

        UUID firstSaleItem = jdbc.queryForObject("select id from sale.sale_items where sale_id = ?", UUID.class, firstSale.saleId());
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("update orders.orders set status = 'DELIVERED', delivered_at = ? where id = ?", now, firstSale.orderId());
        jdbc.update("update sale.sales set status = 'DELIVERED', delivered_at = ? where id = ?", now, firstSale.saleId());
        saleReturnService.create(firstSale.saleId(), new SaleReturnDtos.ReturnRequest("Devolución de stock único",
            List.of(new SaleReturnDtos.ReturnItemRequest(firstSaleItem, new BigDecimal("0.5")))));
        assertThat(stock(productId)).isEqualByComparingTo("8.5");

        var secondSale = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "single-stock-second-" + UUID.randomUUID(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("0.5"), BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of()));
        orders.add(secondSale.orderId());
        sales.add(secondSale.saleId());
        orderFulfillmentService.prepare(secondSale.orderId());
        assertThat(stock(productId)).isEqualByComparingTo("8.0");
        deliveryLifecycleService.cancel(secondSale.orderId());
        assertThat(stock(productId)).isEqualByComparingTo("8.5");

        assertThat(readQueryService.inventory(0, 100, "single-stock").content())
            .anySatisfy(balance -> assertThat(balance.get("stock")).isEqualTo(new BigDecimal("8.5000")));
        assertThat(readQueryService.movements(productId, 0, 100).content())
            .allSatisfy(movement -> assertThat(movement).doesNotContainKeys("depotId", "depotCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> orderProjection = (Map<String, Object>) readQueryService.orderDetail(firstSale.orderId()).get("order");
        assertThat(orderProjection).doesNotContainKey("depotId");
    }

    @Test
    void shrinkageIsAuthorizedAndConcurrentRetriesDeductAndAuditOnlyOnce() throws Exception {
        UUID admin = createUser("shrinkage-admin");
        assignRole(admin, "ADMIN");
        String token = "Bearer " + loginHttp(email(admin));
        UUID assigned = createUser("shrinkage-assigned");
        assignRole(assigned, createRoleWithPermission("STOCK_SHRINKAGE"));
        String assignedToken = "Bearer " + loginHttp(email(assigned));
        UUID adjustmentAdmin = createUser("shrinkage-adjustment-admin");
        assignRole(adjustmentAdmin, createRoleWithPermission("ADMIN_ALL"));
        assignRole(adjustmentAdmin, createRoleWithPermission("STOCK_ADJUST"));
        String adjustmentToken = "Bearer " + loginHttp(email(adjustmentAdmin));
        UUID product = createProduct("shrinkage-write");
        jdbc.update("update inventory.inventory_balances set quantity = 2 where product_id = ?", product);
        UUID key = UUID.randomUUID();
        var request = shrinkageRequest(product, key, "2.5", " Envase roto ");

        mockMvc.perform(request).andExpect(status().isUnauthorized());
        mockMvc.perform(shrinkageRequest(product, key, "2.5", "Envase roto").header("Authorization", assignedToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(shrinkageRequest(product, key, "2.5", "Envase roto").header("Authorization", adjustmentToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/shrinkages").header("Authorization", assignedToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/inventory/shrinkages").header("Authorization", adjustmentToken))
            .andExpect(status().isOk());
        for (String invalidQuantity : List.of("0", "-1", "0.25", "1000000000000000")) {
            mockMvc.perform(shrinkageRequest(product, key, invalidQuantity, "Daño").header("Authorization", token))
                .andExpect(status().isBadRequest());
        }
        mockMvc.perform(shrinkageRequest(product, key, "1", "   ").header("Authorization", token))
            .andExpect(status().isBadRequest());
        mockMvc.perform(shrinkageRequest(UUID.randomUUID(), key, "2.5", "Envase roto").header("Authorization", token))
            .andExpect(status().isNotFound());
        assertThat(stock(product)).isEqualByComparingTo("2");

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            java.util.concurrent.Callable<Integer> submit = () -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("No se coordinó el reintento");
                return mockMvc.perform(shrinkageRequest(product, key, "2.5", " Envase roto ").header("Authorization", token))
                    .andReturn().getResponse().getStatus();
            };
            var first = workers.submit(submit);
            var second = workers.submit(submit);
            boolean bothReady = ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            assertThat(bothReady).isTrue();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(204);
        }
        assertThat(stock(product)).isEqualByComparingTo("-0.5");
        assertThat(jdbc.queryForMap("select movement_type, quantity, reason from inventory.stock_movements where id = ?", key))
            .containsEntry("movement_type", "SHRINKAGE").containsEntry("quantity", new BigDecimal("-2.5000"))
            .containsEntry("reason", "Envase roto");
        mockMvc.perform(shrinkageRequest(product, key, "3", "Envase roto").header("Authorization", token))
            .andExpect(status().isConflict());
        jdbc.update("update catalog.products set status = 'INACTIVE' where id = ?", product);
        mockMvc.perform(shrinkageRequest(product, UUID.randomUUID(), "1", "Envase roto").header("Authorization", token))
            .andExpect(status().isConflict());
        mockMvc.perform(shrinkageRequest(product, key, "2.5", "Envase roto").header("Authorization", token))
            .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("""
            select count(*) from audit.audit_events where operation = 'STOCK_SHRINKAGE' and resource_id = ? and actor_user_id = ?
            """, Long.class, key.toString(), admin)).isEqualTo(1L);

        UUID overflow = createProduct("shrinkage-overflow");
        jdbc.update("update inventory.inventory_balances set quantity = -999999999999999.5 where product_id = ?", overflow);
        UUID failed = UUID.randomUUID();
        mockMvc.perform(shrinkageRequest(overflow, failed, "1", "Daño").header("Authorization", token))
            .andExpect(status().isBadRequest());
        assertThat(stock(overflow)).isEqualByComparingTo("-999999999999999.5");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movements where id = ?", Long.class, failed)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where resource_id = ?", Long.class, failed.toString())).isZero();
    }

    @Test
    void shrinkageReportUsesArgentineDayBoundsAndTotalsOverAllPages() throws Exception {
        UUID admin = createUser("shrinkage-report");
        assignRole(admin, "ADMIN");
        String token = "Bearer " + loginHttp(email(admin));
        UUID product = createProduct("shrinkage-report");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        for (UUID id : List.of(first, second, outside)) {
            String quantity = id.equals(first) ? "1.5" : id.equals(second) ? "2" : "100";
            mockMvc.perform(shrinkageRequest(product, id, quantity, "Rotura").header("Authorization", token))
                .andExpect(status().isNoContent());
        }
        jdbc.update("update inventory.stock_movements set created_at = ? where id = ?", Timestamp.from(Instant.parse("2026-11-12T03:00:00Z")), first);
        jdbc.update("update inventory.stock_movements set created_at = ? where id = ?", Timestamp.from(Instant.parse("2026-11-13T02:59:59Z")), second);
        jdbc.update("update inventory.stock_movements set created_at = ? where id = ?", Timestamp.from(Instant.parse("2026-11-13T03:00:00Z")), outside);
        jdbc.update("""
            insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, created_at)
            values (?, ?, 'MANUAL_ADJUSTMENT', -20, 'Conteo', ?)
            """, UUID.randomUUID(), product, Timestamp.from(Instant.parse("2026-11-12T12:00:00Z")));
        for (int page = 0; page < 2; page++) {
            mockMvc.perform(get("/api/inventory/shrinkages").header("Authorization", token)
                    .param("page", String.valueOf(page)).param("size", "1")
                    .param("dateMin", "2026-11-12").param("dateMax", "2026-11-12"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalQuantity").value(3.5))
                .andExpect(jsonPath("$.movements.totalElements").value(2))
                .andExpect(jsonPath("$.movements.content[0].id").value((page == 0 ? second : first).toString()))
                .andExpect(jsonPath("$.movements.content[0].quantity").value(page == 0 ? 2.0 : 1.5))
                .andExpect(jsonPath("$.movements.content[0].reason").value("Rotura"));
        }
        mockMvc.perform(get("/api/inventory/shrinkages").header("Authorization", token)
                .param("dateMin", "2026-11-13").param("dateMax", "2026-11-12"))
            .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder shrinkageRequest(
        UUID product, UUID key, String quantity, String reason
    ) throws Exception {
        return post("/api/inventory/{productId}/shrinkages", product).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "idempotencyKey", key, "quantity", new BigDecimal(quantity), "reason", reason)));
    }

    private BigDecimal stock(UUID productId) {
        return jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?",
            BigDecimal.class, productId);
    }

    @Test
    void pendingOrdersEditWithoutStockAndPreparedOrdersAdjustExactlyOnceAcrossTheirLifecycle() {
        authenticateOrderActor(createUser("fulfillment-lifecycle"), "ADMIN_ALL");
        EditableSaleFixture fixture = freshPendingOrder();
        assertThat(stock(fixture.productId())).isEqualByComparingTo("10");
        deliveryLifecycleService.recordAttempt(fixture.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest("FAILED", "Ausente"));
        assertThat(stock(fixture.productId())).isEqualByComparingTo("10");

        editQuantity(fixture, "2");
        assertThat(stock(fixture.productId())).isEqualByComparingTo("10");
        orderFulfillmentService.prepare(fixture.orderId());
        orderFulfillmentService.prepare(fixture.orderId());
        assertThat(stock(fixture.productId())).isEqualByComparingTo("8");
        editQuantity(fixture, "3");
        assertThat(stock(fixture.productId())).isEqualByComparingTo("7");
        deliveryLifecycleService.cancel(fixture.orderId());
        assertThat(stock(fixture.productId())).isEqualByComparingTo("10");
        deliveryLifecycleService.reactivate(fixture.orderId());
        assertThat(stock(fixture.productId())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select fulfillment_status from orders.orders where id = ?", String.class, fixture.orderId())).isEqualTo("PENDING");
        // Successful delivery from PENDING performs preparation atomically.
        deliveryLifecycleService.recordAttempt(fixture.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null));
        assertThat(stock(fixture.productId())).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("select prepared_at is not null and prepared_by_user_id is not null from orders.orders where id = ?", Boolean.class, fixture.orderId())).isTrue();
        assertThatThrownBy(() -> orderFulfillmentService.prepare(fixture.orderId())).isInstanceOf(IllegalStateException.class);
        assertThat(stock(fixture.productId())).isEqualByComparingTo("7");
    }

    @Test
    void cancellationWithoutPreparationNeverRestoresStockAndFailedPreparationRollsBackAllProducts() {
        authenticateOrderActor(createUser("fulfillment-rollback"), "ADMIN_ALL");
        EditableSaleFixture cancelled = freshPendingOrder();
        deliveryLifecycleService.cancel(cancelled.orderId());
        assertThat(stock(cancelled.productId())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movements where reference_id = ?", Long.class, cancelled.orderId())).isZero();

        EditableSaleFixture fixture = freshPendingOrder();
        UUID second = createProduct("fulfillment-inactive");
        putGeneralPrice(second, BigDecimal.TEN);
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", second);
        orderConfirmationService.editConfirmed(fixture.orderId(), new OrderEditDtos.EditRequest(null, List.of(
            new OrderConfirmationDtos.LineRequest(fixture.productId(), BigDecimal.ONE, BigDecimal.ZERO, null),
            new OrderConfirmationDtos.LineRequest(second, BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO));
        // Fail on the last product after the first movement, proving transaction rollback.
        UUID inactive = fixture.productId().toString().compareTo(second.toString()) > 0 ? fixture.productId() : second;
        jdbc.update("update catalog.products set status = 'INACTIVE' where id = ?", inactive);
        assertThatThrownBy(() -> orderFulfillmentService.prepare(fixture.orderId())).isInstanceOf(IllegalStateException.class);
        assertThat(stock(fixture.productId())).isEqualByComparingTo("10");
        assertThat(stock(second)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select fulfillment_status from orders.orders where id = ?", String.class, fixture.orderId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movements where reference_id = ?", Long.class, fixture.orderId())).isZero();
    }

    @Test
    void concurrentPreparationAndEditingLeaveOneNetStockDeduction() throws Exception {
        UUID actor = createUser("fulfillment-concurrent");
        authenticateOrderActor(actor, "ADMIN_ALL");
        EditableSaleFixture fixture = freshPendingOrder();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                boolean edit = index == 2;
                jobs.add(executor.submit(() -> {
                    authenticateOrderActor(actor, "ADMIN_ALL");
                    try {
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
                        if (edit) editQuantity(fixture, "2"); else orderFulfillmentService.prepare(fixture.orderId());
                    } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                    finally { SecurityContextHolder.clearContext(); }
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
        }
        assertThat(stock(fixture.productId())).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("select sum(quantity) from inventory.stock_movements where reference_id = ?", BigDecimal.class, fixture.orderId())).isEqualByComparingTo("-2");
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where operation = 'ORDER_PREPARE' and resource_id = ?", Long.class, fixture.orderId().toString())).isEqualTo(1L);
    }

    @Test
    void sellerCanPrepareAndDeliverBeforeAdminReviewAndOnlyReviewRemovesTheMarker() {
        UUID seller = createSeller("fulfillment-review");
        UUID actor = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, seller);
        UUID customer = createCustomer(seller);
        UUID product = createProduct("fulfillment-review");
        putGeneralPrice(product, BigDecimal.TEN);
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", product);
        authenticateOrderActor(actor, "ORDER_CREATE", "ORDER_PREPARE", "SALE_DELIVER");
        var result = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest("review-" + UUID.randomUUID(), customer, null,
            List.of(new OrderConfirmationDtos.LineRequest(product, BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of()));
        orders.add(result.orderId()); sales.add(result.saleId());
        assertThat(orderReviewPending(result.orderId())).isTrue();
        orderFulfillmentService.prepare(result.orderId());
        deliveryLifecycleService.recordAttempt(result.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null));
        assertThat(stock(product)).isEqualByComparingTo("9");
        assertThat(orderReviewPending(result.orderId())).isTrue();
        assertThatThrownBy(() -> orderFulfillmentService.review(result.orderId())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        authenticateOrderActor(actor, "ORDER_REVIEW");
        assertThatThrownBy(() -> orderFulfillmentService.review(result.orderId())).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        UUID admin = createUser("fulfillment-review-admin");
        authenticateOrderActor(admin, "ADMIN_ALL");
        assertThat(readQueryService.orders(0, 100, "", "", null, null, null, false, null, true).content()).anySatisfy(row -> assertThat(row.get("id")).isEqualTo(result.orderId()));
        orderFulfillmentService.review(result.orderId());
        orderFulfillmentService.review(result.orderId());
        assertThat(orderReviewPending(result.orderId())).isFalse();
        assertThat(jdbc.queryForObject("select reviewed_by_user_id from orders.orders where id = ?", UUID.class, result.orderId())).isEqualTo(admin);
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where operation = 'ORDER_REVIEW' and resource_id = ?", Long.class, result.orderId().toString())).isEqualTo(1L);
        assertThat(stock(product)).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, result.orderId())).isEqualTo("DELIVERED");
        EditableSaleFixture foreign = freshPendingOrder();
        authenticateOrderActor(actor, "ORDER_PREPARE");
        assertThatThrownBy(() -> orderFulfillmentService.prepare(foreign.orderId())).isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(stock(foreign.productId())).isEqualByComparingTo("10");
    }

    private boolean orderReviewPending(UUID orderId) {
        return jdbc.queryForObject("select created_by_admin = false and reviewed_at is null from orders.orders where id = ?", Boolean.class, orderId);
    }

    @Test
    void confirmationAndPendingEditsStillRejectInactiveProductsWithoutCreatingStockMovements() {
        authenticateOrderActor(createUser("fulfillment-inactive-confirm"), "ADMIN_ALL");
        EditableSaleFixture fixture = freshPendingOrder();
        jdbc.update("update catalog.products set status = 'INACTIVE' where id = ?", fixture.productId());
        assertThatThrownBy(() -> editQuantity(fixture, "2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "inactive-" + UUID.randomUUID(), fixture.customerId(), null,
            List.of(new OrderConfirmationDtos.LineRequest(fixture.productId(), BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of())))
            .isInstanceOf(IllegalStateException.class);
        assertThat(stock(fixture.productId())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from orders.orders where customer_id = ?", Integer.class, fixture.customerId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total from orders.orders where id = ?", BigDecimal.class, fixture.orderId())).isEqualByComparingTo("10");
    }

    private void authenticateOrderActor(UUID actor, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test",
            java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
    }

    private EditableSaleFixture freshPendingOrder() {
        UUID customer = createCustomer(null);
        UUID product = createProduct("fulfillment-" + UUID.randomUUID());
        putGeneralPrice(product, BigDecimal.TEN);
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", product);
        var result = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest("fulfillment-" + UUID.randomUUID(), customer, null,
            List.of(new OrderConfirmationDtos.LineRequest(product, BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of()));
        orders.add(result.orderId()); sales.add(result.saleId());
        return new EditableSaleFixture(result.orderId(), result.saleId(), customer, product);
    }

    @Test
    void actionPermissionsAuthorizeOnlyAssignedOrdersAndRemainIndependentlyRevocable() throws Exception {
        UUID admin = createUser("action-admin"); assignRole(admin, "ADMIN");
        authenticateOrderActor(admin, "ADMIN_ALL");
        EditableSaleFixture own = freshPendingOrder();
        EditableSaleFixture foreign = freshPendingOrder();
        UUID profile = createSeller("action-owner");
        UUID user = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        jdbc.update("update orders.orders set seller_id = ? where id = ?", profile, own.orderId());
        // Order ownership remains useful even after its customer is reassigned.
        var role = roleAdminService.create("ACTIONS_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "Acciones asignadas",
            Set.of("ORDER_EDIT", "ORDER_CANCEL", "ORDER_REACTIVATE", "SALE_RETURN", "ORDER_PRICE_OVERRIDE", "ORDER_DISCOUNT", "ORDER_PRICE_LIST_CHANGE", "ORDER_DOCUMENT_EXPORT", "ORDER_NOTIFY"));
        roles.add(role.id()); auditResources.add(role.code()); assignRole(user, role.code());
        String token = loginHttp(email(user));
        UUID general = jdbc.queryForObject("select id from catalog.price_lists where code = 'GENERAL'", UUID.class);
        String edit = objectMapper.writeValueAsString(Map.of("priceListId", general, "lines", List.of(Map.of("productId", own.productId(), "quantity", 2, "lineDiscountPercent", 5, "unitPriceOverride", 12)), "orderDiscountPercent", 5));
        mockMvc.perform(put("/api/orders/{id}", own.orderId()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(edit)).andExpect(status().isOk());
        assertThat(stock(own.productId())).isEqualByComparingTo("10");
        mockMvc.perform(put("/api/orders/{id}", foreign.orderId()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(edit)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pricing/resolve-batch").param("customerId", own.customerId().toString()).param("productIds", own.productId().toString()).param("orderId", own.orderId().toString()).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get("/api/pricing/resolve-batch").param("customerId", foreign.customerId().toString()).param("productIds", own.productId().toString()).param("orderId", own.orderId().toString()).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pricing/resolve-batch").param("customerId", own.customerId().toString()).param("productIds", own.productId().toString()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        for (String action : List.of("cancel", "reactivate")) {
            mockMvc.perform(post("/api/orders/{id}/{action}", foreign.orderId(), action).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
            mockMvc.perform(post("/api/orders/{id}/{action}", own.orderId(), action).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        }
        mockMvc.perform(post("/api/orders/{id}/review", own.orderId()).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        authenticateOrderActor(admin, "ADMIN_ALL");
        deliveryLifecycleService.recordAttempt(own.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null));
        UUID item = jdbc.queryForObject("select id from sale.sale_items where sale_id = ?", UUID.class, own.saleId());
        String returns = objectMapper.writeValueAsString(Map.of("reason", "Envase", "items", List.of(Map.of("saleItemId", item, "quantity", 0.5))));
        mockMvc.perform(post("/api/sales/{id}/returns", foreign.saleId()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(returns)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sales/{id}/returns", own.saleId()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(returns)).andExpect(status().isCreated());
        assertThat(stock(own.productId())).isEqualByComparingTo("8.5");
        for (String format : List.of("a4", "ticket")) {
            mockMvc.perform(get("/api/orders/{id}/documents/{format}", own.orderId(), format).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
            mockMvc.perform(get("/api/orders/{id}/documents/{format}", foreign.orderId(), format).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        }
        when(notificationChannelSender.isConfigured("EMAIL")).thenReturn(true);
        String notification = objectMapper.writeValueAsString(Map.of("channel", "EMAIL", "recipient", "assigned@example.test", "format", "TICKET", "idempotencyKey", "actions-" + UUID.randomUUID()));
        mockMvc.perform(post("/api/orders/{id}/notifications", foreign.orderId()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(notification)).andExpect(status().isNotFound());
        var result = mockMvc.perform(post("/api/orders/{id}/notifications", own.orderId()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(notification)).andExpect(status().isOk()).andReturn();
        UUID requestId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path("requestId").asText()); notificationRequests.add(requestId);
        mockMvc.perform(get("/api/orders/{id}/notifications/{request}", own.orderId(), requestId).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        authenticateOrderActor(admin, "ADMIN_ALL"); roleAdminService.replacePermissions(role.code(), Set.of("ORDER_CREATE"));
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        String restricted = loginHttp(email(user));
        for (String action : List.of("cancel", "reactivate", "notifications")) {
            mockMvc.perform(post("/api/orders/{id}/{action}", own.orderId(), action).header("Authorization", "Bearer " + restricted).contentType(MediaType.APPLICATION_JSON).content(notification)).andExpect(status().isForbidden());
        }
        mockMvc.perform(put("/api/orders/{id}", own.orderId()).header("Authorization", "Bearer " + restricted).contentType(MediaType.APPLICATION_JSON).content(edit)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/sales/{id}/returns", own.saleId()).header("Authorization", "Bearer " + restricted).contentType(MediaType.APPLICATION_JSON).content(returns)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/orders/{id}/documents/a4", own.orderId()).header("Authorization", "Bearer " + restricted)).andExpect(status().isForbidden());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void quantityEditorsPreserveSnapshotsWithoutAcquiringPriceOrDiscountPermissions(boolean legacyPriceList) throws Exception {
        UUID admin = createUser("snapshot-admin"); assignRole(admin, "ADMIN"); authenticateOrderActor(admin, "ADMIN_ALL");
        EditableSaleFixture own = freshPendingOrder();
        UUID general = jdbc.queryForObject("select id from catalog.price_lists where code = 'GENERAL'", UUID.class);
        orderConfirmationService.editConfirmed(own.orderId(), new OrderEditDtos.EditRequest(general, List.of(new OrderConfirmationDtos.LineRequest(own.productId(), BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("12"))), new BigDecimal("5")));
        if (legacyPriceList) jdbc.update("update orders.order_items set price_list_id = null where order_id = ?", own.orderId());
        UUID profile = createSeller("snapshot-owner");
        UUID user = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        jdbc.update("update orders.orders set seller_id = ? where id = ?", profile, own.orderId());
        String role = createRoleWithPermission("ORDER_EDIT"); assignRole(user, role);
        String token = loginHttp(email(user));
        for (String values : List.of("12,10,5", "13,10,5", "12,11,5", "12,10,6", "12,0,5")) {
            String[] parts = values.split(",");
            String body = objectMapper.writeValueAsString(Map.of("priceListId", general, "lines", List.of(Map.of("productId", own.productId(), "quantity", 2, "unitPriceOverride", new BigDecimal(parts[0]), "lineDiscountPercent", new BigDecimal(parts[1]))), "orderDiscountPercent", new BigDecimal(parts[2])));
            mockMvc.perform(put("/api/orders/{id}", own.orderId()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(values.equals("12,10,5") ? status().isOk() : status().isForbidden());
        }
        assertThat(jdbc.queryForObject("select total from orders.orders where id = ?", BigDecimal.class, own.orderId())).isEqualByComparingTo("20.52");
        assertThat(stock(own.productId())).isEqualByComparingTo("10");
        authenticateOrderActor(admin, "ADMIN_ALL");
        roleAdminService.replacePermissions(role, Set.of("ORDER_EDIT", "ORDER_PRICE_OVERRIDE"));
        String priceEditor = loginHttp(email(user));
        mockMvc.perform(put("/api/orders/{id}", own.orderId()).header("Authorization", "Bearer " + priceEditor).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("priceListId", general, "lines", List.of(Map.of("productId", own.productId(), "quantity", 2, "unitPriceOverride", 13, "lineDiscountPercent", 10)), "orderDiscountPercent", 5)))).andExpect(status().isOk());
        authenticateOrderActor(admin, "ADMIN_ALL");
        roleAdminService.replacePermissions(role, Set.of("ORDER_EDIT", "ORDER_DISCOUNT"));
        String discountEditor = loginHttp(email(user));
        mockMvc.perform(put("/api/orders/{id}", own.orderId()).header("Authorization", "Bearer " + discountEditor).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("priceListId", general, "lines", List.of(Map.of("productId", own.productId(), "quantity", 2, "unitPriceOverride", 13, "lineDiscountPercent", 11)), "orderDiscountPercent", 6)))).andExpect(status().isOk());
        assertThat(stock(own.productId())).isEqualByComparingTo("10");
    }

    private void editQuantity(EditableSaleFixture fixture, String quantity) {
        orderConfirmationService.editConfirmed(fixture.orderId(), new OrderEditDtos.EditRequest(null,
            List.of(new OrderConfirmationDtos.LineRequest(fixture.productId(), new BigDecimal(quantity), BigDecimal.ZERO, null)), BigDecimal.ZERO));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"ORDER_EDIT", "ORDER_CANCEL", "ORDER_REACTIVATE", "SALE_RETURN", "ORDER_DOCUMENT_EXPORT", "ORDER_NOTIFY", "ORDER_PRICE_OVERRIDE", "ORDER_DISCOUNT"})
    void eachActionPermissionDoesNotAuthorizeTheOtherActions(String permission) throws Exception {
        UUID profile = createSeller("single-action");
        UUID user = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        assignRole(user, createRoleWithPermission(permission));
        String token = loginHttp(email(user)); UUID missing = UUID.randomUUID();
        String edit = objectMapper.writeValueAsString(Map.of("lines", List.of(Map.of("productId", UUID.randomUUID(), "quantity", 1, "lineDiscountPercent", 0)), "orderDiscountPercent", 0));
        mockMvc.perform(put("/api/orders/{id}", missing).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(permission.equals("ORDER_EDIT") ? status().isNotFound() : status().isForbidden());
        for (String action : List.of("cancel", "reactivate")) {
            String required = action.equals("cancel") ? "ORDER_CANCEL" : "ORDER_REACTIVATE";
            mockMvc.perform(post("/api/orders/{id}/{action}", missing, action).header("Authorization", "Bearer " + token))
                .andExpect(permission.equals(required) ? status().isNotFound() : status().isForbidden());
        }
        mockMvc.perform(post("/api/sales/{id}/returns", missing).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("reason", "Envase", "items", List.of(Map.of("saleItemId", UUID.randomUUID(), "quantity", 0.5))))))
            .andExpect(permission.equals("SALE_RETURN") ? status().isNotFound() : status().isForbidden());
        mockMvc.perform(get("/api/orders/{id}/documents/a4", missing).header("Authorization", "Bearer " + token))
            .andExpect(permission.equals("ORDER_DOCUMENT_EXPORT") ? status().isNotFound() : status().isForbidden());
        when(notificationChannelSender.isConfigured("EMAIL")).thenReturn(true);
        mockMvc.perform(post("/api/orders/{id}/notifications", missing).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("channel", "EMAIL", "recipient", "assigned@example.test", "format", "TICKET", "idempotencyKey", UUID.randomUUID().toString()))))
            .andExpect(permission.equals("ORDER_NOTIFY") ? status().isNotFound() : status().isForbidden());
    }

    @Test
    void saleReturnsRespectRemainingQuantityAndRollbackAllInventoryChangesOnFailure() {
        UUID actorId = createUser("sale-return");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        SaleFixture fixture = createDeliveredSale(new BigDecimal("1.0"));

        saleReturnService.create(fixture.saleId(), new SaleReturnDtos.ReturnRequest("First partial return",
            List.of(new SaleReturnDtos.ReturnItemRequest(fixture.saleItemId(), new BigDecimal("0.5")))));
        saleReturnService.create(fixture.saleId(), new SaleReturnDtos.ReturnRequest("Second partial return",
            List.of(new SaleReturnDtos.ReturnItemRequest(fixture.saleItemId(), new BigDecimal("0.5")))));
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, fixture.productId()))
            .isEqualByComparingTo("2.0");
        assertThatThrownBy(() -> saleReturnService.create(fixture.saleId(), new SaleReturnDtos.ReturnRequest("Over return",
            List.of(new SaleReturnDtos.ReturnItemRequest(fixture.saleItemId(), new BigDecimal("0.5"))))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from sale.returns where sale_id = ?", Long.class, fixture.saleId())).isEqualTo(2L);

        UUID healthyProduct = createProduct("return-healthy");
        UUID missingBalanceProduct = createProduct("return-missing-balance");
        jdbc.update("update inventory.inventory_balances set quantity = 2 where product_id = ?", healthyProduct);
        inventoryMovementService.apply(healthyProduct, new BigDecimal("-0.5"), "SALE", fixture.orderId(), "Fixture sale line");
        jdbc.update("delete from inventory.inventory_balances where product_id = ?", missingBalanceProduct);
        UUID healthyLine = createSaleItem(fixture.saleId(), healthyProduct, new BigDecimal("0.5"));
        UUID missingBalanceLine = createSaleItem(fixture.saleId(), missingBalanceProduct, new BigDecimal("0.5"));

        assertThatThrownBy(() -> saleReturnService.create(fixture.saleId(), new SaleReturnDtos.ReturnRequest("Atomic failure",
            List.of(new SaleReturnDtos.ReturnItemRequest(healthyLine, new BigDecimal("0.5")),
                new SaleReturnDtos.ReturnItemRequest(missingBalanceLine, new BigDecimal("0.5"))))))
            .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, healthyProduct))
            .isEqualByComparingTo("1.5");
        assertThat(jdbc.queryForObject("select count(*) from sale.returns where sale_id = ?", Long.class, fixture.saleId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movements where product_id = ? and movement_type = 'RETURN'", Long.class, healthyProduct)).isZero();
    }

    @Test
    void concurrentReturnsCannotExceedTheSoldQuantity() throws Exception {
        UUID actorId = createUser("sale-return-race");
        SaleFixture fixture = createDeliveredSale(new BigDecimal("0.5"));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> submitReturn(actorId, fixture, ready, start));
            var second = executor.submit(() -> submitReturn(actorId, fixture, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Boolean> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("select count(*) from sale.return_items ri join sale.returns r on r.id = ri.return_id where r.sale_id = ?", Long.class, fixture.saleId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, fixture.productId()))
                .isEqualByComparingTo("2.0");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void adminCanEditConfirmedOrderAndReconcilesStockPaymentsAndAccountDebt() {
        UUID actorId = createUser("order-edit");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        EditableSaleFixture fixture = createEditableSale(new BigDecimal("2.0"), new BigDecimal("20.0000"),
            new BigDecimal("10.0000"));
        jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', 5, ?)",
            UUID.randomUUID(), fixture.customerId(), fixture.saleId(), Timestamp.from(Instant.now()));
        jdbc.update("update customer.customers set balance = balance - 5 where id = ?", fixture.customerId());
        UUID secondProduct = createProduct("order-edit-new");
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", secondProduct);
        putGeneralPrice(fixture.productId(), new BigDecimal("20.0000"));
        putGeneralPrice(secondProduct, new BigDecimal("5.0000"));

        OrderConfirmationService.EditResult result = orderConfirmationService.editConfirmed(fixture.orderId(),
            new OrderEditDtos.EditRequest(null, List.of(
                new OrderConfirmationDtos.LineRequest(fixture.productId(), new BigDecimal("1.0"), BigDecimal.ZERO, null),
                new OrderConfirmationDtos.LineRequest(secondProduct, BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO));

        assertThat(result.total()).isEqualByComparingTo("25.0000");
        assertThat(result.paid()).isEqualByComparingTo("10.0000");
        assertThat(result.balance()).isEqualByComparingTo("10.0000");
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where sale_id = ?", Long.class, fixture.saleId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, fixture.saleId())).isEqualByComparingTo("10.0000");
        assertThat(jdbc.queryForObject("select sum(case when entry_type = 'DEBIT' then amount else -amount end) from customer.account_ledger where sale_id = ?", BigDecimal.class, fixture.saleId())).isEqualByComparingTo("10.0000");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, fixture.customerId())).isEqualByComparingTo("10.0000");
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, fixture.productId())).isEqualByComparingTo("9.0");
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, secondProduct)).isEqualByComparingTo("9.0");
        assertThat(jdbc.queryForObject("select count(*) from orders.order_items where order_id = ?", Long.class, fixture.orderId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from sale.sale_items where sale_id = ?", Long.class, fixture.saleId())).isEqualTo(2L);
    }

    @Test
    void editingThenCancellingOrderRestoresOnlyItsNetStockEffect() {
        UUID actorId = createUser("order-edit-cancel");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        EditableSaleFixture fixture = createEditableSale(new BigDecimal("2.0"), new BigDecimal("20.0000"), BigDecimal.ZERO);
        putGeneralPrice(fixture.productId(), new BigDecimal("20.0000"));

        orderConfirmationService.editConfirmed(fixture.orderId(), new OrderEditDtos.EditRequest(null, List.of(
            new OrderConfirmationDtos.LineRequest(fixture.productId(), BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO));
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, fixture.productId()))
            .isEqualByComparingTo("9.0");

        deliveryLifecycleService.cancel(fixture.orderId());

        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, fixture.productId()))
            .isEqualByComparingTo("10.0");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, fixture.customerId()))
            .isEqualByComparingTo("0.0");
        assertThat(jdbc.queryForObject("select status from sale.sales where id = ?", String.class, fixture.saleId())).isEqualTo("CANCELLED");
    }

    @Test
    void editRejectsNewTotalBelowAmountAlreadyPaidWithoutChangingSale() {
        UUID actorId = createUser("order-edit-paid-floor");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        EditableSaleFixture fixture = createEditableSale(new BigDecimal("2.0"), new BigDecimal("20.0000"),
            new BigDecimal("10.0000"));
        putGeneralPrice(fixture.productId(), new BigDecimal("20.0000"));

        assertThatThrownBy(() -> orderConfirmationService.editConfirmed(fixture.orderId(), new OrderEditDtos.EditRequest(
            null, List.of(new OrderConfirmationDtos.LineRequest(fixture.productId(), new BigDecimal("0.5"), BigDecimal.ZERO,
                new BigDecimal("5.0000"))),
            BigDecimal.ZERO))).isInstanceOf(IllegalStateException.class).hasMessageContaining("menor que el importe ya pagado");
        assertThat(jdbc.queryForObject("select total from sale.sales where id = ?", BigDecimal.class, fixture.saleId())).isEqualByComparingTo("40.0000");
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, fixture.productId()))
            .isEqualByComparingTo("8.0");
    }

    @Test
    void deliveredOrderRecordsCashAndTransferAndLeavesRemainderOnAccount() {
        UUID actorId = createUser("delivery-collection");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        EditableSaleFixture fixture = createEditableSale(new BigDecimal("2.0"), new BigDecimal("20.0000"),
            new BigDecimal("10.0000"));

        deliveryLifecycleService.recordAttempt(fixture.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest(
            "DELIVERED", null, List.of(
                new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", new BigDecimal("5.0000")),
                new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("BANK_TRANSFER", new BigDecimal("10.0000"))),
            "TR-DELIVERY-123"));

        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, fixture.saleId())).isEqualByComparingTo("25.0000");
        assertThat(jdbc.queryForObject("select sum(case when entry_type = 'DEBIT' then amount else -amount end) from customer.account_ledger where sale_id = ?", BigDecimal.class, fixture.saleId())).isEqualByComparingTo("15.0000");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, fixture.customerId())).isEqualByComparingTo("15.0000");
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where sale_id = ?", Long.class, fixture.saleId())).isEqualTo(3L);
        assertThat(jdbc.queryForObject("select transfer_reference from payment.payments where sale_id = ? and method = 'BANK_TRANSFER'", String.class, fixture.saleId()))
            .isEqualTo("TR-DELIVERY-123");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> detailPayments = (List<java.util.Map<String, Object>>) readQueryService
            .orderDetail(fixture.orderId()).get("payments");
        assertThat(detailPayments).anySatisfy(payment -> assertThat(payment.get("transferReference")).isEqualTo("TR-DELIVERY-123"));
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where sale_id = ? and method = 'CASH' and transfer_reference is null", Long.class, fixture.saleId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, fixture.orderId())).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select result from orders.delivery_attempts where order_id = ?", String.class, fixture.orderId())).isEqualTo("DELIVERED");
    }

    @Test
    void deliveryCollectionAboveOutstandingDebtRollsBackAllEffects() {
        UUID actorId = createUser("delivery-collection-overflow");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        EditableSaleFixture fixture = createEditableSale(new BigDecimal("2.0"), new BigDecimal("20.0000"),
            new BigDecimal("10.0000"));

        assertThatThrownBy(() -> deliveryLifecycleService.recordAttempt(fixture.orderId(),
            new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null,
                List.of(new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest(
                    "CASH", new BigDecimal("30.5000"))), null)))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, fixture.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, fixture.saleId())).isEqualByComparingTo("10.0000");
        assertThat(jdbc.queryForObject("select count(*) from orders.delivery_attempts where order_id = ?", Long.class, fixture.orderId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where sale_id = ?", Long.class, fixture.saleId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, fixture.customerId())).isEqualByComparingTo("30.0000");
    }

    @Test
    void deliveryPaysCurrentSaleThenSelectedPreviousDebtsAtomicallyWithEachPaymentMethod() {
        UUID actor = createUser("delivery-previous");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customer = createCustomer(null);
        AccountDebtSale oldest = createAccountDebtSale(customer, new BigDecimal("30"), 7200);
        AccountDebtSale older = createAccountDebtSale(customer, new BigDecimal("20"), 3600);
        AccountDebtSale current = createAccountDebtSale(customer, new BigDecimal("100"), 1800);
        AccountDebtSale newer = createAccountDebtSale(customer, new BigDecimal("70"), 900);
        Map<String, Object> sale = (Map<String, Object>) readQueryService.orderDetail(current.orderId()).get("sale");
        assertThat((BigDecimal) sale.get("previousDebtAvailable")).isEqualByComparingTo("50");
        deliveryLifecycleService.recordAttempt(current.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest(
            "DELIVERED", null, List.of(
                new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", new BigDecimal("110")),
                new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("BANK_TRANSFER", new BigDecimal("30"))), "TR-PREVIOUS", new BigDecimal("40")));
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, current.saleId())).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, oldest.saleId())).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, older.saleId())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, newer.saleId())).isZero();
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isEqualByComparingTo("80");
        assertThat(jdbc.queryForObject("select sum(amount) from payment.payments where customer_id = ? and method = 'CASH'", BigDecimal.class, customer)).isEqualByComparingTo("110");
        assertThat(jdbc.queryForObject("select sum(amount) from payment.payments where customer_id = ? and method = 'BANK_TRANSFER' and transfer_reference = 'TR-PREVIOUS'", BigDecimal.class, customer)).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("select sum(amount) from customer.account_ledger where customer_id = ? and entry_type = 'CREDIT'", BigDecimal.class, customer)).isEqualByComparingTo("140");
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, current.orderId())).isEqualTo("DELIVERED");
    }

    @Test
    void deliveryPreviousDebtRejectsForeignSalesAndRollsBackBeforeAcceptingOwnedDebt() {
        UUID seller = createSeller("delivery-debt-scope");
        UUID foreignSeller = createSeller("delivery-debt-other");
        UUID actor = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, seller);
        UUID customer = createCustomer(seller);
        AccountDebtSale foreign = createAccountDebtSale(customer, new BigDecimal("100"), 7200);
        AccountDebtSale own = createAccountDebtSale(customer, new BigDecimal("30"), 3600);
        AccountDebtSale current = createAccountDebtSale(customer, new BigDecimal("40"), 1800);
        jdbc.update("update orders.orders set seller_id = ? where id = ?", foreignSeller, foreign.orderId());
        jdbc.update("update orders.orders set seller_id = ? where id = ?", seller, current.orderId());
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("SALE_DELIVER"))));
        Map<String, Object> sale = (Map<String, Object>) readQueryService.orderDetail(current.orderId()).get("sale");
        assertThat((BigDecimal) sale.get("previousDebtAvailable")).isEqualByComparingTo("30");
        assertThatThrownBy(() -> deliveryLifecycleService.recordAttempt(current.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest(
            "DELIVERED", null, List.of(new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", new BigDecimal("80"))), null, new BigDecimal("40"))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("deuda anterior disponible");
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where customer_id = ?", Long.class, customer)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from orders.delivery_attempts where order_id = ?", Long.class, current.orderId())).isZero();
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isEqualByComparingTo("170");
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, current.orderId())).isEqualTo("CONFIRMED");
        deliveryLifecycleService.recordAttempt(current.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest(
            "DELIVERED", null, List.of(new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", new BigDecimal("70"))), null, new BigDecimal("30")));
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, foreign.saleId())).isZero();
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, own.saleId())).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isEqualByComparingTo("100");
    }

    @Test
    void deliveryRejectsPreviousDebtAlreadyPaidSinceTheDialogOpened() {
        UUID actor = createUser("delivery-stale-debt");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customer = createCustomer(null);
        AccountDebtSale older = createAccountDebtSale(customer, new BigDecimal("30"), 3600);
        AccountDebtSale current = createAccountDebtSale(customer, new BigDecimal("40"), 1800);
        accountPaymentService.apply(customer, new AccountPaymentDtos.PaymentRequest(new BigDecimal("30"), "CASH", null, older.saleId()));
        assertThatThrownBy(() -> deliveryLifecycleService.recordAttempt(current.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest(
            "DELIVERED", null, List.of(new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", new BigDecimal("70"))), null, new BigDecimal("30"))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, current.saleId())).isZero();
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, current.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("select count(*) from orders.delivery_attempts where order_id = ?", Long.class, current.orderId())).isZero();
    }

    @Test
    void partialDeliveryPaymentLeavesSelectedPreviousDebtUntouchedUntilCurrentSaleIsPaid() {
        UUID actor = createUser("delivery-partial-debt");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customer = createCustomer(null);
        AccountDebtSale older = createAccountDebtSale(customer, new BigDecimal("30"), 3600);
        AccountDebtSale current = createAccountDebtSale(customer, new BigDecimal("40"), 1800);
        deliveryLifecycleService.recordAttempt(current.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest(
            "DELIVERED", null, List.of(new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", new BigDecimal("20"))), null, new BigDecimal("30")));
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, older.saleId())).isZero();
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, current.saleId())).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isEqualByComparingTo("50");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void assignedRolesReadOnlyAssignedCustomersAndOwnedSalesAndOrdersAcrossApiRoutes(boolean customRole) throws Exception {
        UUID sellerId = createSeller("read-scope");
        UUID otherSellerId = createSeller("foreign-read-scope");
        UUID sellerUser = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, sellerId);
        UUID admin = createUser("read-scope-admin");
        assignRole(admin, "ADMIN");
        if (customRole) {
            authenticateOrderActor(admin, "ADMIN_ALL");
            var role = roleAdminService.create("SCOPE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "Asignados", Set.of("ORDER_CREATE", "SALE_DELIVER", "SALE_PAYMENT", "ORDER_DOCUMENT_EXPORT"));
            roles.add(role.id()); auditResources.add(role.code());
            assignRole(sellerUser, role.code());
        } else assignRole(sellerUser, "SELLER");
        UUID customer = createCustomer(sellerId);
        UUID secondCustomer = createCustomer(sellerId);
        UUID foreignCustomer = createCustomer(otherSellerId);
        UUID unassignedCustomer = createCustomer(null);
        String prefix = "SCOPE-" + UUID.randomUUID().toString().substring(0, 8);
        for (UUID id : List.of(customer, secondCustomer, foreignCustomer, unassignedCustomer)) {
            jdbc.update("update customer.customers set business_name = ? where id = ?", prefix + "-" + id, id);
        }
        AccountDebtSale own = createAccountDebtSale(customer, BigDecimal.TEN, 3600);
        AccountDebtSale inherited = createAccountDebtSale(customer, BigDecimal.TEN, 1800);
        AccountDebtSale second = createAccountDebtSale(secondCustomer, BigDecimal.TEN, 1800);
        AccountDebtSale foreignOnAssignedCustomer = createAccountDebtSale(customer, BigDecimal.TEN, 7200);
        AccountDebtSale foreign = createAccountDebtSale(foreignCustomer, BigDecimal.TEN, 1800);
        AccountDebtSale foreignInherited = createAccountDebtSale(foreignCustomer, BigDecimal.TEN, 1800);
        AccountDebtSale unassigned = createAccountDebtSale(unassignedCustomer, BigDecimal.TEN, 1800);
        for (AccountDebtSale sale : List.of(own, inherited)) {
            jdbc.update("update orders.orders set status = 'DELIVERED' where id = ?", sale.orderId());
            jdbc.update("update sale.sales set status = 'DELIVERED' where id = ?", sale.saleId());
        }
        for (AccountDebtSale sale : List.of(own, second)) {
            jdbc.update("update orders.orders set seller_id = ? where id = ?", sellerId, sale.orderId());
        }
        for (AccountDebtSale sale : List.of(foreignOnAssignedCustomer, foreign)) {
            jdbc.update("update orders.orders set seller_id = ? where id = ?", otherSellerId, sale.orderId());
        }
        for (AccountDebtSale sale : List.of(own, inherited, second, foreignOnAssignedCustomer, foreign, foreignInherited, unassigned)) {
            jdbc.update("update orders.orders set order_number = ? where id = ?", prefix + "-" + sale.orderId().toString().substring(0, 12), sale.orderId());
            jdbc.update("update sale.sales set sale_number = ? where id = ?", prefix + "-" + sale.saleId().toString().substring(0, 12), sale.saleId());
        }
        SecurityContextHolder.clearContext();
        String token = loginHttp(email(sellerUser));
        mockMvc.perform(get("/api/customers").param("search", prefix).param("size", "1").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/customers").param("search", prefix).param("sellerId", otherSellerId.toString()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/customers/filter-options").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sellers.length()").value(1))
            .andExpect(jsonPath("$.sellers[0].id").value(sellerId.toString()));
        mockMvc.perform(get("/api/customers/{id}", customer).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customers/{id}/orders", customer).param("size", "1").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customers/{id}/last-order", customer).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.orderId").value(inherited.orderId().toString()));
        for (UUID id : List.of(foreignCustomer, unassignedCustomer)) {
            for (String suffix : List.of("", "/orders", "/last-order")) {
                mockMvc.perform(get("/api/customers/" + id + suffix).header("Authorization", "Bearer " + token))
                    .andExpect(suffix.equals("/last-order") ? status().isNotFound() : status().isForbidden());
            }
        }
        for (String route : List.of("/api/orders", "/api/sales")) {
            UUID[] expectedIds = route.equals("/api/orders")
                ? new UUID[] {own.orderId(), inherited.orderId(), second.orderId()}
                : new UUID[] {own.saleId(), inherited.saleId()};
            mockMvc.perform(get(route).param("search", prefix).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[*].id").value(
                    org.hamcrest.Matchers.containsInAnyOrder(java.util.Arrays.stream(expectedIds).map(UUID::toString).toArray(String[]::new))));
            mockMvc.perform(get(route).param("search", prefix).param("size", "1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(expectedIds.length)).andExpect(jsonPath("$.totalPages").value(expectedIds.length));
            mockMvc.perform(get(route).param("search", prefix).param("page", "2").param("size", "1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(route.equals("/api/orders") ? 1 : 0));
        }
        mockMvc.perform(get("/api/sales").param("sellerId", otherSellerId.toString()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/sales").param("customerId", foreignCustomer.toString()).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/sales/filter-options").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.customers.length()").value(1))
            .andExpect(jsonPath("$.customers[0].id").value(customer.toString()))
            .andExpect(jsonPath("$.sellers.length()").value(1)).andExpect(jsonPath("$.sellers[0].id").value(sellerId.toString()));
        for (AccountDebtSale sale : List.of(own, inherited, foreignOnAssignedCustomer, foreign, foreignInherited, unassigned)) {
            boolean accessible = sale.equals(own) || sale.equals(inherited);
            String orderNumber = jdbc.queryForObject("select order_number from orders.orders where id = ?", String.class, sale.orderId());
            for (String route : List.of("/api/orders/" + sale.orderId(), "/api/orders/by-number/" + orderNumber, "/api/sales/" + sale.saleId())) {
                mockMvc.perform(get(route).header("Authorization", "Bearer " + token))
                    .andExpect(accessible ? status().isOk() : status().isNotFound());
            }
            if (!accessible) {
                for (String format : List.of("a4", "ticket")) {
                    mockMvc.perform(get("/api/orders/" + sale.orderId() + "/documents/" + format).header("Authorization", "Bearer " + token))
                        .andExpect(status().isNotFound());
                }
            }
        }
        String adminToken = loginHttp(email(admin));
        mockMvc.perform(get("/api/customers").param("search", prefix).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(4));
        for (String route : List.of("/api/orders", "/api/sales")) {
            mockMvc.perform(get(route).param("search", prefix).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(7));
        }
        jdbc.update("update seller.seller_profiles set status = 'INACTIVE' where id = ?", sellerId);
        mockMvc.perform(get("/api/customers/" + customer).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        for (String route : List.of("/api/customers", "/api/orders", "/api/sales", "/api/sales/filter-options", "/api/orders/" + own.orderId())) {
            mockMvc.perform(get(route).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        }
    }

    @Test
    void sellerPaymentsAndDebtsStayWithinOwnedOrdersEvenForSharedCustomers() throws Exception {
        UUID sellerId = createSeller("payment-scope");
        UUID otherSellerId = createSeller("other-payment-scope");
        UUID userId = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, sellerId);
        assignRole(userId, "SELLER");
        UUID customerId = createCustomer(sellerId);
        AccountDebtSale other = createAccountDebtSale(customerId, new BigDecimal("100.0000"), 7200);
        AccountDebtSale own = createAccountDebtSale(customerId, new BigDecimal("30.0000"), 3600);
        AccountDebtSale legacy = createAccountDebtSale(customerId, new BigDecimal("20.0000"), 1800);
        jdbc.update("update orders.orders set seller_id = ? where id = ?", otherSellerId, other.orderId());
        jdbc.update("update orders.orders set seller_id = ? where id = ?", sellerId, own.orderId());
        UUID foreignCustomer = createCustomer(otherSellerId);
        createAccountDebtSale(foreignCustomer, new BigDecimal("80.0000"), 3600);

        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(userId.toString(), "test", List.of(new SimpleGrantedAuthority("SALE_PAYMENT"))));
        var debts = readQueryService.customerDebts(customerId, 0, 1);
        assertThat(debts.totalElements()).isEqualTo(2);
        assertThat(debts.totalPages()).isEqualTo(2);
        assertThat(debts.content()).extracting(row -> row.get("saleId")).containsExactly(own.saleId());
        assertThat(readQueryService.customerDebts(customerId, 1, 1).content())
            .extracting(row -> row.get("saleId")).containsExactly(legacy.saleId());
        assertThatThrownBy(() -> readQueryService.customerDebts(foreignCustomer, 0, 20))
            .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThatThrownBy(() -> accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("5.0000"), "CASH", null, other.saleId())))
            .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThatThrownBy(() -> accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("51.0000"), "CASH", null, null)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where customer_id = ?", Long.class, customerId)).isZero();

        var fifo = accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("35.0000"), "CASH", null, null));
        assertThat(fifo.allocations()).extracting(AccountPaymentService.AllocationResult::saleId)
            .containsExactly(own.saleId(), legacy.saleId());
        assertThat(fifo.balanceBefore()).isEqualByComparingTo("50");
        assertThat(fifo.balanceAfter()).isEqualByComparingTo("15");
        var specific = accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("5.0000"), "CASH", null, legacy.saleId()));
        assertThat(specific.balanceBefore()).isEqualByComparingTo("15");
        assertThat(specific.balanceAfter()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, other.saleId())).isEqualByComparingTo("0");

        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(userId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("10.0000"), "CASH", null, other.saleId()));
        assertThat(readQueryService.customerDebts(customerId, 0, 20).totalElements()).isEqualTo(2);
        String search = jdbc.queryForObject("select tax_id from customer.customers where id = ?", String.class, customerId);
        jdbc.update("update customer.customers set business_name = ? where id = ?", search, customerId);
        assertThat(readQueryService.payments(0, 20, search).totalElements()).isEqualTo(4);

        SecurityContextHolder.clearContext();
        String accessToken = loginHttp(email(userId));
        mockMvc.perform(get("/api/payments").param("search", search).param("size", "1")
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mockMvc.perform(get("/api/payments").param("search", jdbc.queryForObject("select sale_number from sale.sales where id = ?", String.class, other.saleId()))
                .header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/customers/{id}/debts", customerId).header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].saleId").value(legacy.saleId().toString()));
        mockMvc.perform(get("/api/customers/{id}/debts", foreignCustomer).header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/customers/{id}/account-payments", customerId).header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                    new AccountPaymentDtos.PaymentRequest(new BigDecimal("1.0000"), "CASH", null, other.saleId()))))
            .andExpect(status().isNotFound());
    }

    @Test
    void accountPaymentSupportsFifoAndSpecificSaleAllocation() {
        UUID actorId = createUser("account-payment");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customerId = createCustomer(null);
        AccountDebtSale older = createAccountDebtSale(customerId, new BigDecimal("30.0000"), 3600);
        AccountDebtSale newer = createAccountDebtSale(customerId, new BigDecimal("40.0000"), 1800);

        AccountPaymentService.PaymentResult fifo = accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("35.0000"), "CASH", null, null));
        assertThat(fifo.allocationMode()).isEqualTo("FIFO");
        assertThat(fifo.allocations()).extracting(AccountPaymentService.AllocationResult::saleId)
            .containsExactly(older.saleId(), newer.saleId());
        assertThat(fifo.allocations()).extracting(AccountPaymentService.AllocationResult::amount)
            .containsExactly(new BigDecimal("30.0000"), new BigDecimal("5.0000"));

        AccountPaymentService.PaymentResult specific = accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("10.0000"), "BANK_TRANSFER", "TR-ACCOUNT-123", newer.saleId()));
        assertThat(specific.allocationMode()).isEqualTo("SPECIFIC");
        assertThat(specific.allocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.saleId()).isEqualTo(newer.saleId());
            assertThat(allocation.amount()).isEqualByComparingTo("10.0000");
        });

        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, older.saleId())).isEqualByComparingTo("30.0000");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, newer.saleId())).isEqualByComparingTo("15.0000");
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId)).isEqualByComparingTo("25.0000");
        assertThat(jdbc.queryForObject("select sum(case when entry_type = 'DEBIT' then amount else -amount end) from customer.account_ledger where sale_id = ?", BigDecimal.class, older.saleId())).isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject("select sum(case when entry_type = 'DEBIT' then amount else -amount end) from customer.account_ledger where sale_id = ?", BigDecimal.class, newer.saleId())).isEqualByComparingTo("25.0000");
        assertThat(jdbc.queryForObject("select transfer_reference from payment.payments where sale_id = ? and method = 'BANK_TRANSFER'", String.class, newer.saleId()))
            .isEqualTo("TR-ACCOUNT-123");
        assertThat(jdbc.queryForObject("select count(*) from identity.role_permissions rp join identity.roles r on r.id=rp.role_id join identity.permissions p on p.id=rp.permission_id where r.code='SELLER' and p.code='SALE_PAYMENT'", Long.class)).isEqualTo(1L);
    }

    @Test
    void specificAccountPaymentCannotExceedSaleOrCustomerDebt() {
        UUID actorId = createUser("account-payment-overflow");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customerId = createCustomer(null);
        AccountDebtSale sale = createAccountDebtSale(customerId, new BigDecimal("10.0000"), 1800);

        assertThatThrownBy(() -> accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("10.5000"), "CASH", null, sale.saleId())))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, sale.saleId())).isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where sale_id = ?", Long.class, sale.saleId())).isZero();
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId)).isEqualByComparingTo("10.0000");
    }

    @Test
    void accountSettlementCombinesMethodsAndClearsAllDebtsByFifo() {
        UUID actor = createUser("account-settlement");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customer = createCustomer(null);
        AccountDebtSale older = createAccountDebtSale(customer, new BigDecimal("30"), 3600);
        AccountDebtSale newer = createAccountDebtSale(customer, new BigDecimal("40"), 1800);
        var result = accountPaymentService.settle(customer, List.of(
            new AccountPaymentDtos.SettlementPayment(new BigDecimal("35"), "CASH", null),
            new AccountPaymentDtos.SettlementPayment(new BigDecimal("35"), "BANK_TRANSFER", "TR-SETTLE")));
        assertThat(result.balanceBefore()).isEqualByComparingTo("70");
        assertThat(result.balanceAfter()).isEqualByComparingTo("0");
        assertThat(result.allocations()).extracting(AccountPaymentService.AllocationResult::saleId)
            .containsExactly(older.saleId(), newer.saleId(), newer.saleId());
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isZero();
        assertThat(jdbc.queryForObject("select sum(total - paid) from sale.sales where customer_id = ?", BigDecimal.class, customer)).isZero();
        assertThat(jdbc.queryForObject("select transfer_reference from payment.payments where customer_id = ? and method = 'BANK_TRANSFER'", String.class, customer)).isEqualTo("TR-SETTLE");
        assertThatThrownBy(() -> accountPaymentService.settle(customer, List.of(
            new AccountPaymentDtos.SettlementPayment(new BigDecimal("70"), "CASH", null))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where customer_id = ?", Long.class, customer)).isEqualTo(3L);
    }

    @Test
    void accountSettlementRollsBackEarlierPaymentsWhenLaterAllocationFails() {
        UUID actor = createUser("account-settlement-rollback");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customer = createCustomer(null);
        AccountDebtSale sale = createAccountDebtSale(customer, BigDecimal.TEN, 1800);
        jdbc.update("update sale.sales set paid = 5 where id = ?", sale.saleId());
        assertThatThrownBy(() -> accountPaymentService.settle(customer, List.of(
            new AccountPaymentDtos.SettlementPayment(new BigDecimal("4"), "CASH", null),
            new AccountPaymentDtos.SettlementPayment(new BigDecimal("6"), "BANK_TRANSFER", "TR-ROLLBACK"))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where customer_id = ?", Long.class, customer)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer.account_ledger where customer_id = ? and entry_type = 'CREDIT'", Long.class, customer)).isZero();
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, sale.saleId())).isEqualByComparingTo("5");
    }

    @Test
    void accountSettlementRejectsPartialStaleAndMissingTransferReferenceWithoutWrites() {
        UUID actor = createUser("account-settlement-validation");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customer = createCustomer(null);
        createAccountDebtSale(customer, BigDecimal.TEN, 1800);
        for (String amount : List.of("9", "11")) {
            assertThatThrownBy(() -> accountPaymentService.settle(customer, List.of(
                new AccountPaymentDtos.SettlementPayment(new BigDecimal(amount), "CASH", null))))
                .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> accountPaymentService.settle(customer, List.of(
            new AccountPaymentDtos.SettlementPayment(BigDecimal.TEN, "BANK_TRANSFER", "  "))))
            .isInstanceOf(IllegalArgumentException.class);
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("SALE_PAYMENT"))));
        assertThatThrownBy(() -> accountPaymentService.settle(customer, List.of(
            new AccountPaymentDtos.SettlementPayment(BigDecimal.TEN, "CASH", null))))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where customer_id = ?", Long.class, customer)).isZero();
    }

    @Test
    void accountPartialSettlementCombinesMethodsByFifoAndRejectsExcess() {
        UUID actor = createUser("account-settlement-partial");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actor.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customer = createCustomer(null);
        AccountDebtSale older = createAccountDebtSale(customer, new BigDecimal("30"), 3600);
        AccountDebtSale newer = createAccountDebtSale(customer, new BigDecimal("40"), 1800);
        assertThatThrownBy(() -> accountPaymentService.settle(customer, List.of(
            new AccountPaymentDtos.SettlementPayment(new BigDecimal("71"), "CASH", null)), true))
            .isInstanceOf(IllegalStateException.class);
        var result = accountPaymentService.settle(customer, List.of(
            new AccountPaymentDtos.SettlementPayment(new BigDecimal("20"), "CASH", null),
            new AccountPaymentDtos.SettlementPayment(new BigDecimal("15"), "BANK_TRANSFER", "TR-PARTIAL")), true);
        assertThat(result.balanceBefore()).isEqualByComparingTo("70");
        assertThat(result.balanceAfter()).isEqualByComparingTo("35");
        assertThat(result.allocations()).extracting(AccountPaymentService.AllocationResult::saleId)
            .containsExactly(older.saleId(), older.saleId(), newer.saleId());
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isEqualByComparingTo("35");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, older.saleId())).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, newer.saleId())).isEqualByComparingTo("5");
    }

    @Test
    void accountSettlementHttpRequiresAdminAndLeavesDeliveryStateUnchanged() throws Exception {
        UUID actor = createUser("account-settlement-http");
        assignRole(actor, "SELLER");
        UUID customer = createCustomer(null);
        AccountDebtSale sale = createAccountDebtSale(customer, BigDecimal.TEN, 1800);
        String token = loginHttp(email(actor));
        String body = "{\"payments\":[{\"amount\":10,\"method\":\"CASH\"}]}";
        mockMvc.perform(post("/api/customers/{id}/account-payments/settlement", customer)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        assignRole(actor, "ADMIN");
        token = loginHttp(email(actor));
        mockMvc.perform(post("/api/customers/{id}/account-payments/settlement", customer)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceAfter").value(0));
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, sale.orderId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("select status from sale.sales where id = ?", String.class, sale.saleId())).isEqualTo("CONFIRMED");
    }

    @Test
    void concurrentFifoPaymentsCannotAllocateTheSameDebtTwice() throws Exception {
        UUID firstActor = createUser("account-payment-race-1");
        UUID secondActor = createUser("account-payment-race-2");
        UUID customerId = createCustomer(null);
        AccountDebtSale sale = createAccountDebtSale(customerId, new BigDecimal("10.0000"), 1800);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> submitAccountPayment(firstActor, customerId, ready, start));
            Future<Boolean> second = executor.submit(() -> submitAccountPayment(secondActor, customerId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, sale.saleId())).isEqualByComparingTo("10.0000");
            assertThat(jdbc.queryForObject("select count(*) from payment.payments where sale_id = ?", Long.class, sale.saleId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId)).isEqualByComparingTo("0.0000");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void globalCreditLimitWarnsAndAuditsWithoutBlockingAndPersistsIdempotentWarning() {
        UUID actorId = createUser("credit-limit-warning");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customerId = createCustomer(null);
        UUID productId = createProduct("credit-limit");
        putGeneralPrice(productId, new BigDecimal("20.0000"));
        jdbc.update("update inventory.inventory_balances set quantity = 20 where product_id = ?", productId);
        jdbc.update("update app.business_settings set credit_limit = 15 where id = 1");
        var request = new OrderConfirmationDtos.ConfirmationRequest("credit-limit-" + UUID.randomUUID(), customerId,
            null, List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2.0"), BigDecimal.ZERO, null)),
            BigDecimal.ZERO, null);

        var first = orderConfirmationService.confirm(request);
        orders.add(first.orderId());
        sales.add(first.saleId());

        assertThat(first.total()).isEqualByComparingTo("40.0000");
        assertThat(first.creditLimitWarning()).isNotNull();
        assertThat(first.creditLimitWarning().creditLimit()).isEqualByComparingTo("15.0000");
        assertThat(first.creditLimitWarning().projectedBalance()).isEqualByComparingTo("40.0000");
        assertThat(first.creditLimitWarning().exceededBy()).isEqualByComparingTo("25.0000");
        assertThat(jdbc.queryForObject("select credit_limit_exceeded from orders.orders where id = ?", Boolean.class, first.orderId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where operation = 'CREDIT_LIMIT_WARNING' and resource_id = ?", Long.class, customerId.toString())).isEqualTo(1L);

        var replay = orderConfirmationService.confirm(request);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where operation = 'CREDIT_LIMIT_WARNING' and resource_id = ?", Long.class, customerId.toString())).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> detailOrder = (java.util.Map<String, Object>) readQueryService.orderDetail(first.orderId()).get("order");
        assertThat(detailOrder.get("creditLimitExceeded")).isEqualTo(true);

        var disabled = businessSettingsService.updateCreditLimit(new BusinessSettingsDtos.CreditLimitRequest(null));
        assertThat(disabled.enabled()).isFalse();
        UUID secondCustomer = createCustomer(null);
        UUID secondProduct = createProduct("credit-limit-disabled");
        putGeneralPrice(secondProduct, new BigDecimal("20.0000"));
        jdbc.update("update inventory.inventory_balances set quantity = 20 where product_id = ?", secondProduct);
        var noLimit = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "credit-limit-disabled-" + UUID.randomUUID(), secondCustomer, null,
            List.of(new OrderConfirmationDtos.LineRequest(secondProduct, new BigDecimal("2.0"), BigDecimal.ZERO, null)),
            BigDecimal.ZERO, null));
        orders.add(noLimit.orderId());
        sales.add(noLimit.saleId());
        assertThat(noLimit.creditLimitWarning()).isNull();
        assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, noLimit.orderId())).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmedOrderIsWrittenToOutboxAndWorkerRetriesWithStableIdempotencyKey() {
        UUID actorId = createUser("outbox-confirm");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customerId = createCustomer(null);
        UUID productId = createProduct("outbox-confirm");
        putGeneralPrice(productId, new BigDecimal("12.5000"));
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", productId);
        var request = new OrderConfirmationDtos.ConfirmationRequest("outbox-" + UUID.randomUUID(), customerId,
            null, List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, null);

        var confirmation = orderConfirmationService.confirm(request);
        orders.add(confirmation.orderId());
        sales.add(confirmation.saleId());
        var retry = orderConfirmationService.confirm(request);
        assertThat(retry.orderId()).isEqualTo(confirmation.orderId());

        UUID eventId = jdbc.queryForObject("select id from notification.outbox_events where aggregate_id = ? and event_type = 'ORDER_CONFIRMED'",
            UUID.class, confirmation.orderId());
        assertThat(jdbc.queryForObject("select count(*) from notification.outbox_events where aggregate_id = ?", Long.class,
            confirmation.orderId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status from notification.outbox_events where id = ?", String.class, eventId)).isEqualTo("PENDING");

        AtomicInteger dispatchAttempts = new AtomicInteger();
        AtomicInteger eventIdSeen = new AtomicInteger();
        ApplicationListener<PayloadApplicationEvent<?>> listener = event -> {
            if (event.getPayload() instanceof OutboxDispatchEvent dispatch && dispatch.event().id().equals(eventId)) {
                eventIdSeen.set(dispatch.event().id().equals(eventId) ? 1 : 0);
                if (dispatchAttempts.getAndIncrement() == 0) throw new IllegalStateException("temporary consumer failure");
            }
        };
        eventMulticaster.addApplicationListener(listener);
        try {
            assertThat(outboxWorker.processBatch(10)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from notification.outbox_events where id = ?", String.class, eventId)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select attempt_count from notification.outbox_events where id = ?", Integer.class, eventId)).isEqualTo(1);
            jdbc.update("update notification.outbox_events set available_at = now() where id = ?", eventId);

            assertThat(outboxWorker.processBatch(10)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from notification.outbox_events where id = ?", String.class, eventId)).isEqualTo("PROCESSED");
            assertThat(jdbc.queryForObject("select attempt_count from notification.outbox_events where id = ?", Integer.class, eventId)).isEqualTo(2);
            assertThat(dispatchAttempts.get()).isEqualTo(2);
            assertThat(eventIdSeen.get()).isEqualTo(1);
            assertThat(meterRegistry.get("app.outbox.events.failed").counter().count()).isGreaterThanOrEqualTo(1.0);
            assertThat(meterRegistry.get("app.outbox.events.processed").counter().count()).isGreaterThanOrEqualTo(1.0);
            assertThat(jdbc.queryForObject("select idempotency_key from notification.outbox_events where id = ?", String.class, eventId))
                .isEqualTo("ORDER_CONFIRMED:" + confirmation.orderId());
        } finally {
            eventMulticaster.removeApplicationListener(listener);
        }
    }

    @Test
    void notificationRequestIsIdempotentAuditedAndDeliveredFromOutbox() {
        jdbc.update("delete from notification.outbox_events e where e.event_type = 'NOTIFICATION_DELIVERY_REQUESTED' "
            + "and not exists (select 1 from notification.delivery_requests d where d.id = e.aggregate_id)");
        UUID actorId = createUser("notification-request");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customerId = createCustomer(null);
        UUID productId = createProduct("notification-request");
        putGeneralPrice(productId, new BigDecimal("9.0000"));
        jdbc.update("update inventory.inventory_balances set quantity = 5 where product_id = ?", productId);
        var confirmation = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "notification-sale-" + UUID.randomUUID(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, null));
        orders.add(confirmation.orderId());
        sales.add(confirmation.saleId());

        when(notificationChannelSender.isConfigured("EMAIL")).thenReturn(true);
        var request = new NotificationDtos.CreateRequest("EMAIL", "mateo@example.test", "TICKET",
            "notification-idempotency-" + UUID.randomUUID());
        var created = notificationRequestService.request(confirmation.orderId(), request);
        notificationRequests.add(created.requestId());
        var duplicate = notificationRequestService.request(confirmation.orderId(), request);
        assertThat(duplicate).isEqualTo(created);
        assertThat(notificationRequestService.status(confirmation.orderId(), created.requestId()).status()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from notification.delivery_requests where id = ?", Long.class,
            created.requestId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from notification.outbox_events where aggregate_id = ? "
            + "and event_type = 'NOTIFICATION_DELIVERY_REQUESTED'", Long.class, created.requestId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where operation = 'NOTIFICATION_REQUEST' "
            + "and resource_id = ?", Long.class, created.requestId().toString())).isEqualTo(1L);

        SecurityContextHolder.clearContext();
        assertThat(outboxWorker.processBatch(20)).isEqualTo(2);
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        assertThat(jdbc.queryForObject("select status from notification.delivery_requests where id = ?", String.class,
            created.requestId())).isEqualTo("SENT");
        assertThat(notificationRequestService.status(confirmation.orderId(), created.requestId()).status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("select attempt_count from notification.delivery_requests where id = ?", Integer.class,
            created.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where resource_id = ? and operation in "
            + "('NOTIFICATION_SEND_ATTEMPT', 'NOTIFICATION_SENT')", Long.class, created.requestId().toString())).isEqualTo(2L);
        org.mockito.Mockito.verify(notificationChannelSender).send(any(), any(), any(), any(), any(), any());

        int purged = notificationRequestStateService.purgeTerminalBefore(Instant.now().plusSeconds(1));
        assertThat(purged).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification.delivery_requests where id = ?", Long.class,
            created.requestId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where resource_id = ? and operation = 'NOTIFICATION_REQUEST'",
            Long.class, created.requestId().toString())).isEqualTo(1L);
        int purgedEvents = outboxRepository.purgeTerminalBefore(Instant.now().plusSeconds(1));
        assertThat(purgedEvents).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notification.outbox_events where aggregate_id in (?, ?)", Long.class,
            created.requestId(), confirmation.orderId())).isZero();
    }

    @Test
    void resellerAccountOwesItsCustomersOrdersWhileTheyKeepTheirOrders() {
        UUID admin = createUser("reseller-admin"); assignRole(admin, "ADMIN");
        authenticateOrderActor(admin, "ADMIN_ALL", "RESELLER_MANAGE");
        UUID reseller = createCustomer(null);
        UUID first = createCustomer(null);
        UUID second = createCustomer(null);
        customerCommandService.changeKind(reseller, "RESELLER");
        customerCommandService.assignReseller(first, reseller);
        customerCommandService.assignReseller(second, reseller);
        UUID product = createProduct("reseller-" + UUID.randomUUID());
        putGeneralPrice(product, BigDecimal.TEN);
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", product);

        var older = confirmFor(first, product, "2");
        var newer = confirmFor(second, product, "1");
        jdbc.update("update sale.sales set created_at = created_at - interval '1 minute' where id = ?", older.saleId());

        for (var result : List.of(older, newer)) {
            assertThat(jdbc.queryForObject("select account_customer_id from sale.sales where id = ?", UUID.class, result.saleId())).isEqualTo(reseller);
            assertThat(jdbc.queryForObject("select count(*) from customer.account_ledger where sale_id = ? and customer_id = ?",
                Integer.class, result.saleId(), reseller)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select customer_id from sale.sales where id = ?", UUID.class, older.saleId())).isEqualTo(first);
        assertThat(older.orderNumber()).startsWith(jdbc.queryForObject(
            "select lpad(customer_number::text, 4, '0') from customer.customers where id = ?", String.class, first));
        assertThat(customerBalance(reseller)).isEqualByComparingTo("30");
        assertThat(customerBalance(first)).isEqualByComparingTo("0");
        assertThat(customerBalance(second)).isEqualByComparingTo("0");

        // FIFO starts with the reseller's oldest sale, whichever customer received it.
        var payment = accountPaymentService.apply(reseller, new AccountPaymentDtos.PaymentRequest(new BigDecimal("25"), "CASH", null, null));
        assertThat(payment.allocations()).extracting(AccountPaymentService.AllocationResult::saleId).containsExactly(older.saleId(), newer.saleId());
        assertThat(payment.allocations()).extracting(AccountPaymentService.AllocationResult::amount)
            .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("20"), new BigDecimal("5"));
        assertThat(customerBalance(reseller)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where sale_id in (?, ?) and customer_id <> ?",
            Integer.class, older.saleId(), newer.saleId(), reseller)).isZero();

        var debts = readQueryService.customerDebts(reseller, 0, 20).content();
        assertThat(debts).hasSize(1);
        assertThat(debts.getFirst()).containsEntry("saleId", newer.saleId()).containsEntry("customerId", second);
        var statement = accountStatementDocumentService.load(reseller);
        assertThat(statement.showCustomers()).isTrue();
        assertThat(statement.lines()).extracting(com.distribuidora.document.rendering.AccountStatementModel.Line::customer)
            .containsExactly("PG customer");
        assertThat(readQueryService.sales(0, 20, "", null, reseller, null, true, null, null).content())
            .extracting(row -> row.get("id")).containsExactly(newer.saleId());
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) readQueryService.orderDetail(older.orderId()).get("order");
        assertThat(detail).containsEntry("accountCustomerId", reseller).containsEntry("accountKind", "RESELLER");
        assertThat(readQueryService.customers(0, 20, "", null, false, "", "", reseller).content())
            .extracting(row -> row.get("id")).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void resellerCollectionsStayWithAdministratorsAndReassignmentKeepsHistory() {
        UUID admin = createUser("reseller-rules-admin"); assignRole(admin, "ADMIN");
        authenticateOrderActor(admin, "ADMIN_ALL", "RESELLER_MANAGE");
        UUID profile = createSeller("reseller-rules");
        UUID sellerUser = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        UUID reseller = createCustomer(profile);
        UUID client = createCustomer(profile);
        UUID regular = createCustomer(null);
        customerCommandService.changeKind(reseller, "RESELLER");
        customerCommandService.assignReseller(client, reseller);
        UUID product = createProduct("reseller-rules-" + UUID.randomUUID());
        putGeneralPrice(product, BigDecimal.TEN);
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", product);

        assertThatThrownBy(() -> customerCommandService.changeKind(reseller, "REGULAR")).isInstanceOf(ResellerConflictException.class);
        assertThatThrownBy(() -> customerCommandService.changeKind(client, "RESELLER")).isInstanceOf(ResellerConflictException.class);
        assertThatThrownBy(() -> customerCommandService.assignReseller(client, client)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> customerCommandService.assignReseller(regular, client)).isInstanceOf(ResellerConflictException.class);
        assertThatThrownBy(() -> customerCommandService.assignReseller(reseller, reseller)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> confirmFor(client, product, "1", List.of(), new BigDecimal("5")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("revendedor");

        // A seller assigned to the customer takes and delivers its orders but cannot collect on the reseller account.
        authenticateOrderActor(sellerUser, "ORDER_CREATE", "ORDER_PREPARE", "SALE_DELIVER", "ACCOUNT_SETTLE", "SALE_PAYMENT", "SALE_RETURN");
        assertThatThrownBy(() -> confirmFor(client, product, "1", List.of(new OrderConfirmationDtos.PaymentRequest("CASH", BigDecimal.TEN)), null))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var delivered = confirmFor(client, product, "1");
        assertThat(jdbc.queryForObject("select account_customer_id from sale.sales where id = ?", UUID.class, delivered.saleId())).isEqualTo(reseller);
        assertThatThrownBy(() -> deliveryLifecycleService.recordAttempt(delivered.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest(
            "DELIVERED", null, List.of(new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", BigDecimal.TEN)), null)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> accountPaymentService.apply(reseller, new AccountPaymentDtos.PaymentRequest(BigDecimal.ONE, "CASH", null, null)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        deliveryLifecycleService.recordAttempt(delivered.orderId(), new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null));
        UUID saleItem = jdbc.queryForObject("select id from sale.sale_items where sale_id = ?", UUID.class, delivered.saleId());
        assertThatThrownBy(() -> saleReturnService.create(delivered.saleId(), new SaleReturnDtos.ReturnRequest("Dañado",
            List.of(new SaleReturnDtos.ReturnItemRequest(saleItem, BigDecimal.ONE)))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("revendedor");

        // Administrators collect, and a reassignment only changes the account of new orders.
        authenticateOrderActor(admin, "ADMIN_ALL", "RESELLER_MANAGE");
        accountPaymentService.apply(reseller, new AccountPaymentDtos.PaymentRequest(new BigDecimal("4"), "CASH", null, null));
        var pending = confirmFor(client, product, "1");
        assertThat(customerCommandService.assignReseller(client, null).pendingOrdersKept()).isEqualTo(1);
        var own = confirmFor(client, product, "1");
        assertThat(jdbc.queryForObject("select account_customer_id from sale.sales where id = ?", UUID.class, pending.saleId())).isEqualTo(reseller);
        assertThat(jdbc.queryForObject("select account_customer_id from sale.sales where id = ?", UUID.class, own.saleId())).isEqualTo(client);
        assertThat(customerBalance(reseller)).isEqualByComparingTo("16");
        assertThat(customerBalance(client)).isEqualByComparingTo("10");

        customerCommandService.changeKind(reseller, "REGULAR");
        customerCommandService.changeKind(reseller, "RESELLER");
        customerCommandService.assignReseller(client, reseller);
        jdbc.update("update customer.customers set status = 'INACTIVE' where id = ?", reseller);
        assertThatThrownBy(() -> confirmFor(client, product, "1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resellerCommissionAccruesBesideTheSellerCommissionAndIsSettledApart() {
        UUID admin = createUser("reseller-commission-admin"); assignRole(admin, "ADMIN");
        authenticateOrderActor(admin, "ADMIN_ALL", "RESELLER_MANAGE");
        UUID profile = createSeller("reseller-commission");
        UUID reseller = createCustomer(null);
        UUID client = createCustomer(profile);
        UUID regular = createCustomer(null);
        customerCommandService.changeKind(reseller, "RESELLER");
        customerCommandService.assignReseller(client, reseller);
        UUID product = createProduct("reseller-commission-" + UUID.randomUUID());
        putGeneralPrice(product, BigDecimal.TEN);
        jdbc.update("update inventory.inventory_balances set quantity = 20 where product_id = ?", product);
        UUID sellerPlan = commissionPlan("1");
        UUID resellerPlan = commissionPlan("3");
        jdbc.update("insert into app.seller_commission_plans(seller_id, plan_id) values (?, ?)", profile, sellerPlan);

        // Only resellers get a plan, and a reseller with a plan keeps its mark.
        assertThatThrownBy(() -> commissionPlansService.assignReseller(regular, resellerPlan, null))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> commissionPlansService.assignReseller(reseller, resellerPlan, sellerPlan))
            .isInstanceOf(IllegalStateException.class);
        commissionPlansService.assignReseller(reseller, resellerPlan, null);
        assertThat(commissionPlansService.list()).filteredOn(plan -> plan.id().equals(resellerPlan))
            .extracting(com.distribuidora.settings.application.CommissionPlansService.Overview::resellerCount).containsExactly(1L);
        assertThatThrownBy(() -> customerCommandService.changeKind(reseller, "REGULAR")).isInstanceOf(ResellerConflictException.class);

        // The customer's order pays the seller and the reseller that holds its account, once each.
        var clientOrder = confirmFor(client, product, "2");
        deliver(clientOrder.orderId());
        UUID clientItem = jdbc.queryForObject("select id from sale.sale_items where sale_id = ?", UUID.class, clientOrder.saleId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            commissionAccrualService.delivered(clientOrder.saleId(), Timestamp.from(Instant.now())));
        assertThat(jdbc.queryForList("""
            select beneficiary || ':' || amount::numeric(19,2) from sale.commission_entries where sale_item_id = ? order by beneficiary
            """, String.class, clientItem)).containsExactly("RESELLER:6.00", "SELLER:2.00");
        assertThat(jdbc.queryForObject("select reseller_id from sale.commission_entries where sale_item_id = ? and beneficiary = 'RESELLER'",
            UUID.class, clientItem)).isEqualTo(reseller);

        // The reseller's own purchases also earn, and the plan must be assigned when the order is delivered.
        var ownOrder = confirmFor(reseller, product, "1");
        deliver(ownOrder.orderId());
        var lateOrder = confirmFor(client, product, "1");
        commissionPlansService.assignReseller(reseller, null, resellerPlan);
        deliver(lateOrder.orderId());
        assertThat(jdbc.queryForObject("""
            select count(*) from sale.commission_entries e join sale.sale_items i on i.id = e.sale_item_id
            where i.sale_id = ? and e.reseller_id is not null
            """, Long.class, lateOrder.saleId())).isZero();

        var beneficiary = com.distribuidora.sale.application.CommissionSettlementService.Beneficiary.RESELLER;
        var statement = commissionSettlementService.statement(beneficiary, reseller, null, null, 0, 20);
        assertThat(statement.pending().entryCount()).isEqualTo(2);
        assertThat(new BigDecimal(statement.pending().amount())).isEqualByComparingTo("9");
        assertThat(statement.movements().content())
            .extracting(com.distribuidora.sale.application.CommissionSettlementService.Movement::orderId)
            .containsExactlyInAnyOrder(clientOrder.orderId(), ownOrder.orderId());
        var sellerPending = commissionSettlementService.statement(
            com.distribuidora.sale.application.CommissionSettlementService.Beneficiary.SELLER, profile, null, null, 0, 20).pending();
        assertThat(new BigDecimal(sellerPending.amount())).isEqualByComparingTo("3");

        var settlement = commissionSettlementService.settle(beneficiary, reseller, statement.pending().token());
        assertThat(new BigDecimal(settlement.amount())).isEqualByComparingTo("9");
        assertThat(settlement.entryCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reseller_id from sale.commission_settlements where id = ?", UUID.class, settlement.id()))
            .isEqualTo(reseller);
        assertThat(commissionSettlementService.statement(
            com.distribuidora.sale.application.CommissionSettlementService.Beneficiary.SELLER, profile, null, null, 0, 20).pending())
            .isEqualTo(sellerPending);
        // A seller entry cannot be tied to a reseller settlement.
        assertThatThrownBy(() -> jdbc.update("""
            update sale.commission_entries set settlement_id = ? where sale_item_id = ? and beneficiary = 'SELLER'
            """, settlement.id(), clientItem)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
            select count(*) from audit.audit_events where resource_id = ? and operation in ('COMMISSION_SETTLEMENT', 'RESELLER_COMMISSION_ASSIGNMENT')
            """, Long.class, reseller.toString())).isEqualTo(3L);
    }

    private UUID commissionPlan(String fixedPerUnit) {
        UUID plan = UUID.randomUUID();
        commissionPlans.add(plan);
        jdbc.update("insert into app.commission_plans(id, name) values (?, ?)", plan, "Plan " + plan);
        jdbc.update("insert into app.commission_rules(plan_id, kind, value) values (?, 'FIXED', ?)", plan, new BigDecimal(fixedPerUnit));
        return plan;
    }

    private void deliver(UUID orderId) {
        deliveryLifecycleService.recordAttempt(orderId, new com.distribuidora.order.api.DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null));
    }

    private OrderConfirmationService.ConfirmationResult confirmFor(UUID customer, UUID product, String quantity) {
        return confirmFor(customer, product, quantity, List.of(), null);
    }

    private OrderConfirmationService.ConfirmationResult confirmFor(UUID customer, UUID product, String quantity,
            List<OrderConfirmationDtos.PaymentRequest> payments, BigDecimal previousBalance) {
        var result = orderConfirmationService.confirm(new OrderConfirmationDtos.ConfirmationRequest("reseller-" + UUID.randomUUID(),
            customer, null, List.of(new OrderConfirmationDtos.LineRequest(product, new BigDecimal(quantity), BigDecimal.ZERO, null)),
            BigDecimal.ZERO, payments, null, previousBalance));
        orders.add(result.orderId()); sales.add(result.saleId());
        return result;
    }

    private BigDecimal customerBalance(UUID customerId) {
        return jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customerId);
    }

    private UUID createUser(String suffix) {
        UUID id = UUID.randomUUID();
        users.add(id);
        Instant now = Instant.now();
        jdbc.update("""
            insert into identity.users(id, email, password_hash, status, failed_login_attempts, created_at, updated_at, version)
            values (?, ?, ?, 'ACTIVE', 0, ?, ?, 0)
            """, id, email(id), passwordEncoder.encode("Passw0rd!"), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return id;
    }

    private String createRoleWithPermission(String permissionCode) {
        UUID roleId = UUID.randomUUID();
        roles.add(roleId);
        String roleCode = "PG_JWT_" + roleId.toString().substring(0, 8).toUpperCase();
        auditResources.add(roleCode);
        jdbc.update("insert into identity.roles(id, code, description) values (?, ?, ?)",
            roleId, roleCode, "JWT authorization integration test");
        jdbc.update("""
            insert into identity.role_permissions(role_id, permission_id)
            select ?, id from identity.permissions where code = ?
            """, roleId, permissionCode);
        return roleCode;
    }

    private void assignRole(UUID userId, String roleCode) {
        UUID roleId = jdbc.queryForObject("select id from identity.roles where code = ?", UUID.class, roleCode);
        jdbc.update("insert into identity.user_roles(user_id, role_id) values (?, ?)", userId, roleId);
    }

    private String loginHttp(String email) throws Exception {
        SecurityContextHolder.clearContext();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "email", email,
                    "password", "Passw0rd!"))))
            .andExpect(status().isOk())
            .andReturn();
        SecurityContextHolder.clearContext();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }

    @Test
    void customerActionsAndAccountCollectionStayInsideAssignedData() throws Exception {
        UUID admin = createUser("customer-actions-admin"); assignRole(admin, "ADMIN"); authenticateOrderActor(admin, "ADMIN_ALL");
        UUID profile = createSeller("customer-actions"); UUID otherProfile = createSeller("customer-actions-other");
        UUID actor = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        UUID customer = createCustomer(profile); UUID onlyForeignDebt = createCustomer(profile); UUID foreignCustomer = createCustomer(otherProfile);
        String prefix = "CUSTOMER-SCOPE-" + UUID.randomUUID().toString().substring(0, 8);
        for (UUID id : List.of(customer, onlyForeignDebt, foreignCustomer)) jdbc.update("update customer.customers set business_name = ? where id = ?", prefix + id, id);
        AccountDebtSale own = createAccountDebtSale(customer, new BigDecimal("100"), 7200);
        AccountDebtSale inherited = createAccountDebtSale(customer, new BigDecimal("50"), 3600);
        AccountDebtSale other = createAccountDebtSale(customer, new BigDecimal("250"), 10800);
        AccountDebtSale hidden = createAccountDebtSale(onlyForeignDebt, new BigDecimal("25"), 3600);
        jdbc.update("update orders.orders set seller_id = ? where id = ?", profile, own.orderId());
        for (UUID id : List.of(other.orderId(), hidden.orderId())) jdbc.update("update orders.orders set seller_id = ? where id = ?", otherProfile, id);
        var role = roleAdminService.create("CUSTOMER_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), "Clientes asignados", Set.of(
            "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "CUSTOMER_STATUS_CHANGE", "CUSTOMER_PRICE_LIST_CHANGE", "ACCOUNT_SETTLE", "CUSTOMER_STATEMENT_EXPORT"));
        roles.add(role.id()); auditResources.add(role.code()); assignRole(actor, role.code());
        String token = loginHttp(email(actor));
        mockMvc.perform(get("/api/customers").param("search", prefix).param("hasBalance", "true").param("size", "1").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].balance").value(150));
        mockMvc.perform(get("/api/customers/{id}", customer).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(150));
        mockMvc.perform(get("/api/customers/{id}", onlyForeignDebt).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(0));
        mockMvc.perform(get("/api/customers/{id}/orders", customer).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/customers/assignment-context").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(profile.toString()));
        mockMvc.perform(get("/api/sellers").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/zones").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        var created = mockMvc.perform(post("/api/customers").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("businessName", prefix + "-new")))).andExpect(status().isCreated()).andReturn();
        UUID createdId = UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asText()); customers.add(createdId);
        assertThat(jdbc.queryForObject("select seller_id from customer.customers where id = ?", UUID.class, createdId)).isEqualTo(profile);
        mockMvc.perform(post("/api/customers").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("businessName", "Rechazado", "sellerId", otherProfile)))).andExpect(status().isForbidden());
        String contact = objectMapper.writeValueAsString(Map.of("businessName", prefix + "-edited", "sellerId", profile));
        mockMvc.perform(put("/api/customers/{id}", customer).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(contact)).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/customers/{id}", foreignCustomer).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(contact)).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/customers/{id}", customer).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("businessName", "Rechazado", "sellerId", otherProfile)))).andExpect(status().isForbidden());
        UUID general = jdbc.queryForObject("select id from catalog.price_lists where code = 'GENERAL'", UUID.class);
        for (UUID id : List.of(customer, foreignCustomer)) {
            boolean accessible = id.equals(customer);
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/customers/{id}/price-list", id).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("priceListId", general)))).andExpect(accessible ? status().isNoContent() : status().isNotFound());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/customers/{id}/status", id).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}")).andExpect(accessible ? status().isNoContent() : status().isNotFound());
            for (String path : List.of("/api/customers/" + id, "/api/customers/" + id + "/orders", "/api/customers/" + id + "/account-balance", "/api/documents/customers/" + id + "/statement")) {
                mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(accessible ? status().isOk() : status().isNotFound());
            }
        }
        authenticateOrderActor(admin, "ADMIN_ALL"); roleAdminService.replacePermissions(role.code(), Set.of("ACCOUNT_SETTLE"));
        mockMvc.perform(get("/api/customers").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        String collector = loginHttp(email(actor));
        mockMvc.perform(get("/api/customers/{id}", customer).header("Authorization", "Bearer " + collector)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customers/{id}/account-balance", customer).header("Authorization", "Bearer " + collector)).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(150)).andExpect(jsonPath("$.email").doesNotExist());
        mockMvc.perform(post("/api/customers/{id}/account-payments", customer).header("Authorization", "Bearer " + collector).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"method\":\"CASH\"}")).andExpect(status().isForbidden());
        String excess = "{\"partial\":true,\"payments\":[{\"amount\":151,\"method\":\"CASH\"}]}";
        mockMvc.perform(post("/api/customers/{id}/account-payments/settlement", customer).header("Authorization", "Bearer " + collector).contentType(MediaType.APPLICATION_JSON).content(excess)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from payment.payments where customer_id = ?", Long.class, customer)).isZero();
        String partial = "{\"partial\":true,\"payments\":[{\"amount\":20,\"method\":\"CASH\"}]}";
        mockMvc.perform(post("/api/customers/{id}/account-payments/settlement", foreignCustomer).header("Authorization", "Bearer " + collector).contentType(MediaType.APPLICATION_JSON).content(partial)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/customers/{id}/account-payments/settlement", customer).header("Authorization", "Bearer " + collector).contentType(MediaType.APPLICATION_JSON).content(partial))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.balanceBefore").value(150)).andExpect(jsonPath("$.balanceAfter").value(130));
        String complete = "{\"payments\":[{\"amount\":80,\"method\":\"CASH\"},{\"amount\":50,\"method\":\"BANK_TRANSFER\",\"transferReference\":\"TEST-TRANSFER\"}]}";
        mockMvc.perform(post("/api/customers/{id}/account-payments/settlement", customer).header("Authorization", "Bearer " + collector).contentType(MediaType.APPLICATION_JSON).content(complete)).andExpect(status().isCreated()).andExpect(jsonPath("$.balanceAfter").value(0));
        assertThat(jdbc.queryForObject("select balance from customer.customers where id = ?", BigDecimal.class, customer)).isEqualByComparingTo("250");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, other.saleId())).isZero();
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, own.saleId())).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, inherited.saleId())).isEqualByComparingTo("50");
        jdbc.update("update seller.seller_profiles set status = 'INACTIVE' where id = ?", profile);
        mockMvc.perform(get("/api/customers/{id}/account-balance", customer).header("Authorization", "Bearer " + collector)).andExpect(status().isNotFound());
    }

    @Test
    void customerContactPermissionPreservesTheDefaultListButCannotChangeOrClearIt() throws Exception {
        UUID profile = createSeller("customer-contact-permission");
        UUID actor = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        assignRole(actor, createRoleWithPermission("CUSTOMER_EDIT"));
        assignRole(actor, createRoleWithPermission("CUSTOMER_CREATE"));
        UUID customer = createCustomer(profile);
        UUID general = jdbc.queryForObject("select id from catalog.price_lists where code = 'GENERAL'", UUID.class);
        UUID other = jdbc.queryForObject("select id from catalog.price_lists where code = 'LISTA_2'", UUID.class);
        jdbc.update("update customer.customers set price_list_id = ? where id = ?", general, customer);
        String token = loginHttp(email(actor));
        String contact = objectMapper.writeValueAsString(Map.of("businessName", "Contact updated", "email", "contact@example.test", "priceListId", general));
        mockMvc.perform(put("/api/customers/{id}", customer).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(contact))
            .andExpect(status().isNoContent());
        for (String body : List.of(
            objectMapper.writeValueAsString(Map.of("businessName", "Unauthorized change", "priceListId", other)),
            "{\"businessName\":\"Unauthorized clear\",\"priceListId\":null}")) {
            mockMvc.perform(put("/api/customers/{id}", customer).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForMap("select business_name, email, seller_id, price_list_id from customer.customers where id = ?", customer))
            .containsEntry("business_name", "Contact updated").containsEntry("email", "contact@example.test")
            .containsEntry("seller_id", profile).containsEntry("price_list_id", general);
        assertThat(jdbc.queryForObject("select count(*) from audit.audit_events where resource_id = ? and operation = 'CUSTOMER_UPDATE'", Long.class, customer.toString())).isEqualTo(1);
        String creationName = "Unauthorized default " + UUID.randomUUID();
        mockMvc.perform(post("/api/customers").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("businessName", creationName, "priceListId", general))))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from customer.customers where business_name = ?", Long.class, creationName)).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "CUSTOMER_STATUS_CHANGE", "CUSTOMER_PRICE_LIST_CHANGE", "ACCOUNT_SETTLE", "CUSTOMER_STATEMENT_EXPORT"})
    void customerPermissionsDoNotGrantOtherActions(String permission) throws Exception {
        UUID profile = createSeller("customer-permission");
        UUID actor = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        assignRole(actor, createRoleWithPermission(permission)); String token = loginHttp(email(actor)); UUID missing = UUID.randomUUID();
        var creation = mockMvc.perform(post("/api/customers").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"businessName\":\"Permission fixture\"}"))
            .andExpect(permission.equals("CUSTOMER_CREATE") ? status().isCreated() : status().isForbidden()).andReturn();
        if (permission.equals("CUSTOMER_CREATE")) customers.add(UUID.fromString(objectMapper.readTree(creation.getResponse().getContentAsString()).path("id").asText()));
        mockMvc.perform(get("/api/customers/{id}", missing).header("Authorization", "Bearer " + token)).andExpect(permission.equals("CUSTOMER_VIEW") ? status().isNotFound() : status().isForbidden());
        mockMvc.perform(put("/api/customers/{id}", missing).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"businessName\":\"Fixture\"}"))
            .andExpect(permission.equals("CUSTOMER_EDIT") ? status().isNotFound() : status().isForbidden());
        for (String action : List.of("status", "price-list")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/customers/{id}/{action}", missing, action).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(action.equals("status") ? "{\"status\":\"INACTIVE\"}" : "{\"priceListId\":null}"))
                .andExpect(permission.equals(action.equals("status") ? "CUSTOMER_STATUS_CHANGE" : "CUSTOMER_PRICE_LIST_CHANGE") ? status().isNotFound() : status().isForbidden());
        }
        mockMvc.perform(post("/api/customers/{id}/account-payments/settlement", missing).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"payments\":[{\"amount\":1,\"method\":\"CASH\"}]}"))
            .andExpect(permission.equals("ACCOUNT_SETTLE") ? status().isNotFound() : status().isForbidden());
        mockMvc.perform(get("/api/documents/customers/{id}/statement", missing).header("Authorization", "Bearer " + token))
            .andExpect(permission.equals("CUSTOMER_STATEMENT_EXPORT") ? status().isNotFound() : status().isForbidden());
    }

    @Test
    void catalogAvailabilityAndPricesRespectOrderPermissionsAndAssignedCustomers() throws Exception {
        UUID profile = createSeller("catalog-scope");
        UUID otherProfile = createSeller("catalog-scope-other");
        UUID actor = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        UUID own = createCustomer(profile);
        UUID foreign = createCustomer(otherProfile);
        UUID product = createProduct("Catalog scope " + UUID.randomUUID());
        UUID listId = jdbc.queryForObject("select id from catalog.price_lists where code = 'GENERAL'", UUID.class);
        authenticateOrderActor(createUser("catalog-scope-admin"), "ADMIN_ALL");
        pricingCommandService.setProductPrice(listId, product, BigDecimal.TEN);
        assignRole(actor, createRoleWithPermission("CUSTOMER_VIEW"));
        String reader = "Bearer " + loginHttp(email(actor));
        String productName = jdbc.queryForObject("select name from catalog.products where id = ?", String.class, product);
        mockMvc.perform(get("/api/products").param("search", productName).header("Authorization", reader))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(product.toString()))
            .andExpect(jsonPath("$.content[0].stock").doesNotExist())
            .andExpect(jsonPath("$.content[0].cost").doesNotExist());
        mockMvc.perform(get("/api/pricing/resolve").param("customerId", own.toString())
                .param("productId", product.toString()).header("Authorization", reader))
            .andExpect(status().isForbidden());

        assignRole(actor, createRoleWithPermission("ORDER_CREATE"));
        String seller = "Bearer " + loginHttp(email(actor));
        mockMvc.perform(get("/api/products").param("search", productName).header("Authorization", seller))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].stock").isNumber())
            .andExpect(jsonPath("$.content[0].cost").doesNotExist())
            .andExpect(jsonPath("$.content[0].suppliers").doesNotExist());
        mockMvc.perform(get("/api/products").param("includeStock", "false").param("stock", "NEGATIVE").header("Authorization", seller))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pricing/lists").header("Authorization", seller))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].pendingReviewCount").doesNotExist());
        mockMvc.perform(get("/api/pricing/lists/{id}/prices", listId).param("search", productName).header("Authorization", seller))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].price").value(10))
            .andExpect(jsonPath("$.content[0].cost").doesNotExist());
        mockMvc.perform(get("/api/pricing/lists/{id}/products/{product}/history", listId, product).header("Authorization", seller))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pricing/resolve").param("customerId", own.toString())
                .param("productId", product.toString()).header("Authorization", seller))
            .andExpect(status().isOk()).andExpect(jsonPath("$.unitPrice").value(10));
        mockMvc.perform(get("/api/pricing/resolve").param("customerId", foreign.toString())
                .param("productId", product.toString()).param("priceListId", listId.toString()).header("Authorization", seller))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/pricing/resolve").param("customerId", own.toString()).param("asOf", "2026-11-17")
                .param("productId", product.toString()).header("Authorization", seller))
            .andExpect(status().isForbidden());

        for (String path : List.of("/api/suppliers", "/api/supplier-orders",
                "/api/supplier-orders/" + foreign, "/api/supplier-orders/supplier/" + foreign + "/last-order",
                "/api/inventory", "/api/inventory/" + product + "/movements",
                "/api/users", "/api/audit", "/api/roles")) {
            mockMvc.perform(get(path).header("Authorization", seller)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/suppliers").header("Authorization", seller).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Proveedor de prueba\"}"))
            .andExpect(status().isForbidden());
        for (String path : List.of("/api/supplier-orders",
                "/api/supplier-orders/" + foreign + "/confirm", "/api/supplier-orders/" + foreign + "/cancel")) {
            mockMvc.perform(post(path).header("Authorization", seller).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        }
        for (String path : List.of("/api/suppliers/" + foreign, "/api/supplier-orders/" + foreign)) {
            mockMvc.perform(put(path).header("Authorization", seller).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Proveedor de prueba\"}"))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void soldProductsReportFiltersBeforePagingAndKeepsReturnsSeparateFromRevenue() throws Exception {
        UUID admin = createUser("sold-products-admin");
        assignRole(admin, "ADMIN");
        String adminToken = "Bearer " + loginHttp(email(admin));
        UUID profile = createSeller("sold-products-owner");
        UUID otherProfile = createSeller("sold-products-other");
        UUID viewer = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, profile);
        String sellerToken = "Bearer " + loginHttp(email(viewer));
        String reportPath = "/api/reports/sold-products";
        mockMvc.perform(get(reportPath)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(reportPath).header("Authorization", sellerToken)).andExpect(status().isForbidden());
        assignRole(viewer, createRoleWithPermission("REPORTS_VIEW"));
        String assigned = "Bearer " + loginHttp(email(viewer));

        UUID ownCustomer = createCustomer(profile);
        UUID otherCustomer = createCustomer(otherProfile);
        String search = "Report-" + UUID.randomUUID().toString().substring(0, 8);
        UUID flour = createProduct(search + " A Harina");
        UUID rice = createProduct(search + " B Arroz");
        UUID hidden = createProduct(search + " C Exclusivo");
        UUID first = reportSale(otherCustomer, profile, "DELIVERED", "2026-10-02T03:00:00Z", "42.30");
        UUID line = createSaleItem(first, flour, new BigDecimal("3"));
        jdbc.update("update sale.sale_items set line_total = 27, line_discount_percent = 10 where id = ?", line);
        createSaleItem(first, rice, new BigDecimal("2"));
        jdbc.update("update sale.sales set order_discount_percent = 10 where id = ?", first);

        // Two returns on the same line, both recorded after the selected sale day.
        for (String quantity : List.of("0.5", "1")) {
            UUID returned = UUID.randomUUID();
            jdbc.update("insert into sale.returns(id, sale_id, returned_by, reason, created_at) values (?, ?, ?, 'Merchandise return', ?)",
                returned, first, admin, Timestamp.from(Instant.parse("2026-10-06T15:00:00Z")));
            jdbc.update("insert into sale.return_items(id, return_id, sale_id, sale_item_id, product_id, quantity) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), returned, first, line, flour, new BigDecimal(quantity));
        }
        UUID last = reportSale(ownCustomer, null, "DELIVERED", "2026-10-03T02:59:59Z", "17.50");
        createSaleItem(last, flour, new BigDecimal("2"));
        UUID foreign = reportSale(ownCustomer, otherProfile, "DELIVERED", "2026-10-02T15:00:00Z", "100");
        createSaleItem(foreign, flour, BigDecimal.TEN);
        createSaleItem(foreign, hidden, BigDecimal.ONE);
        UUID confirmed = reportSale(ownCustomer, profile, "CONFIRMED", "2026-10-02T16:00:00Z", "40");
        createSaleItem(confirmed, flour, new BigDecimal("4"));
        createSaleItem(reportSale(ownCustomer, profile, "CANCELLED", "2026-10-02T17:00:00Z", "70"), flour, new BigDecimal("7"));
        for (String timestamp : List.of("2026-10-02T02:59:59Z", "2026-10-03T03:00:00Z")) {
            createSaleItem(reportSale(ownCustomer, profile, "DELIVERED", timestamp, "90"), flour, new BigDecimal("9"));
        }

        mockMvc.perform(get(reportPath).header("Authorization", assigned).param("search", search.toLowerCase())
                .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].productId").value(flour.toString()))
            .andExpect(jsonPath("$.content[0].soldUnits").value(5))
            .andExpect(jsonPath("$.content[0].returnedUnits").value(1.5))
            .andExpect(jsonPath("$.content[0].netUnits").value(3.5))
            .andExpect(jsonPath("$.content[0].amount").value(41.8));
        mockMvc.perform(get(reportPath).header("Authorization", assigned).param("productId", rice.toString())
                .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].amount").value(18));
        for (int page = 0; page < 2; page++) {
            mockMvc.perform(get(reportPath + "/{id}/sales", flour).header("Authorization", assigned)
                    .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02")
                    .param("page", String.valueOf(page)).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sales.totalElements").value(2))
                .andExpect(jsonPath("$.product.amount").value(41.8))
                .andExpect(jsonPath("$.sales.content[0].id").value((page == 0 ? last : first).toString()))
                .andExpect(jsonPath("$.sales.content[0].amount").value(page == 0 ? 17.5 : 24.3))
                .andExpect(jsonPath("$.sales.content[0].returnedUnits").value(page == 0 ? 0 : 1.5));
        }
        mockMvc.perform(get(reportPath + "/{id}/sales", hidden).header("Authorization", assigned)
                .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.product").doesNotExist())
            .andExpect(jsonPath("$.sales.totalElements").value(0));
        mockMvc.perform(get(reportPath + "/{id}/sales", flour).header("Authorization", adminToken)
                .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sales.totalElements").value(4))
            .andExpect(jsonPath("$.product.soldUnits").value(19))
            .andExpect(jsonPath("$.product.returnedUnits").value(1.5));
        mockMvc.perform(get(reportPath).header("Authorization", adminToken)
                .param("dateMin", "2026-10-03").param("dateMax", "2026-10-02"))
            .andExpect(status().isBadRequest());
        jdbc.update("update seller.seller_profiles set status = 'INACTIVE' where id = ?", profile);
        mockMvc.perform(get(reportPath).header("Authorization", assigned)).andExpect(status().isNotFound());
    }

    @Test
    void soldProductAllocationsReconcileRoundingAcrossAllItemsInHistoricalSale() throws Exception {
        UUID admin = createUser("sold-products-rounding");
        assignRole(admin, "ADMIN");
        String token = "Bearer " + loginHttp(email(admin));
        String search = "Allocation-" + UUID.randomUUID().toString().substring(0, 8);
        UUID sale = reportSale(createCustomer(null), null, "DELIVERED", "2026-10-10T15:00:00Z", "0.0002");
        for (String name : List.of("A", "B", "C")) {
            createSaleItem(sale, createProduct(search + " " + name), BigDecimal.ONE);
        }
        var result = mockMvc.perform(get("/api/reports/sold-products").header("Authorization", token)
                .param("dateMin", "2026-10-10").param("dateMax", "2026-10-10").param("search", search))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3)).andReturn();
        var products = objectMapper.readTree(result.getResponse().getContentAsString()).path("content");
        BigDecimal amount = BigDecimal.ZERO;
        for (var product : products) {
            amount = amount.add(product.path("amount").decimalValue());
            mockMvc.perform(get("/api/reports/sold-products/{id}/sales", product.path("productId").asText()).header("Authorization", token)
                    .param("dateMin", "2026-10-10").param("dateMax", "2026-10-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sales.content[0].amount").value(product.path("amount").doubleValue()));
        }
        assertThat(amount).isEqualByComparingTo("0.0002");
    }

    @Test
    void customerPurchasesKeepPeriodTotalsAndExportEveryAuthorizedOrder() throws Exception {
        UUID admin = createUser("customer-purchases-admin");
        assignRole(admin, "ADMIN");
        String adminToken = "Bearer " + loginHttp(email(admin));
        UUID seller = createSeller("customer-purchases-owner");
        UUID other = createSeller("customer-purchases-other");
        UUID viewer = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, seller);
        assignRole(viewer, createRoleWithPermission("CUSTOMER_VIEW"));
        String customerOnly = "Bearer " + loginHttp(email(viewer));
        assignRole(viewer, createRoleWithPermission("REPORTS_VIEW"));
        String assigned = "Bearer " + loginHttp(email(viewer));
        UUID customer = createCustomer(seller);
        UUID foreignCustomer = createCustomer(other);
        jdbc.update("update customer.customers set business_name = 'Almacén del Centro' where id = ?", customer);
        String report = "/api/reports/customers/{id}/purchases";
        String document = "/api/documents/customers/{id}/purchases";
        mockMvc.perform(get(report, customer)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(report, customer).header("Authorization", customerOnly)).andExpect(status().isForbidden());
        mockMvc.perform(get(document, customer).header("Authorization", customerOnly)).andExpect(status().isForbidden());

        UUID flour = createProduct("purchases-harina-actual");
        UUID rice = createProduct("purchases-arroz");
        UUID first = reportSale(customer, seller, "DELIVERED", "2026-10-02T03:00:00Z", "90");
        UUID flourItem = createSaleItem(first, flour, new BigDecimal("6"));
        jdbc.update("update sale.sale_items set product_name = 'Harina histórica' where id = ?", flourItem);
        createSaleItem(first, rice, new BigDecimal("4"));
        UUID returned = UUID.randomUUID();
        jdbc.update("insert into sale.returns(id, sale_id, returned_by, reason, created_at) values (?, ?, ?, 'Merchandise return', ?)",
            returned, first, admin, Timestamp.from(Instant.parse("2026-10-06T15:00:00Z")));
        jdbc.update("insert into sale.return_items(id, return_id, sale_id, sale_item_id, product_id, quantity) values (?, ?, ?, ?, ?, 1.5)",
            UUID.randomUUID(), returned, first, flourItem, flour);
        UUID last = reportSale(customer, null, "DELIVERED", "2026-10-03T02:59:59Z", "30");
        UUID lastItem = createSaleItem(last, rice, new BigDecimal("3"));
        jdbc.update("update sale.sale_items set product_name = 'Arroz último pedido' where id = ?", lastItem);
        UUID foreignOrder = reportSale(customer, other, "DELIVERED", "2026-10-02T14:00:00Z", "70");
        UUID pending = reportSale(customer, seller, "CONFIRMED", "2026-10-02T16:00:00Z", "40");
        reportSale(customer, seller, "CANCELLED", "2026-10-02T18:00:00Z", "80");
        reportSale(customer, seller, "DELIVERED", "2026-10-03T03:00:00Z", "600");
        reportSale(foreignCustomer, seller, "DELIVERED", "2026-10-02T19:00:00Z", "500");

        mockMvc.perform(get(report, customer).header("Authorization", assigned)
                .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.customer").value("Almacén del Centro"))
            .andExpect(jsonPath("$.summary.totalAmount").value(120))
            .andExpect(jsonPath("$.summary.salesCount").value(2)).andExpect(jsonPath("$.purchases.totalPages").value(2))
            .andExpect(jsonPath("$.purchases.content[0].id").value(last.toString()));
        mockMvc.perform(get(report, customer).header("Authorization", assigned)
                .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02").param("page", "1").param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.totalAmount").value(120))
            .andExpect(jsonPath("$.purchases.content[0].id").value(first.toString()))
            .andExpect(jsonPath("$.purchases.content[0].items[0].product").value("Harina histórica"))
            .andExpect(jsonPath("$.purchases.content[0].items[0].amount").value(54))
            .andExpect(jsonPath("$.purchases.content[0].items[0].returnedUnits").value(1.5))
            .andExpect(jsonPath("$.purchases.content[0].items[0].netUnits").value(4.5))
            .andExpect(jsonPath("$.purchases.content[0].items[1].amount").value(36));
        mockMvc.perform(get(report, customer).header("Authorization", adminToken)
                .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.totalAmount").value(230))
            .andExpect(jsonPath("$.summary.salesCount").value(4));

        byte[] pdf = mockMvc.perform(get(document, customer).header("Authorization", assigned)
                .param("dateMin", "2026-10-02").param("dateMax", "2026-10-02"))
            .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .contentType(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();
        try (var reader = new org.openpdf.text.pdf.PdfReader(pdf)) {
            var text = new org.openpdf.text.pdf.parser.PdfTextExtractor(reader);
            var allPages = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) allPages.append(text.getTextFromPage(page));
            assertThat(allPages.toString()).contains("Almacén del Centro", "Total comprado: $ 120,00", "02/10/2026",
                "Harina histórica", "Arroz último pedido", reportSaleNumber(first), reportSaleNumber(last))
                .doesNotContain(reportSaleNumber(foreignOrder), reportSaleNumber(pending));
        }
        mockMvc.perform(get(report, foreignCustomer).header("Authorization", assigned)).andExpect(status().isNotFound());
        mockMvc.perform(get(document, foreignCustomer).header("Authorization", assigned)).andExpect(status().isNotFound());
        mockMvc.perform(get(document, customer).header("Authorization", assigned)
                .param("dateMin", "2026-10-03").param("dateMax", "2026-10-02")).andExpect(status().isBadRequest());
        mockMvc.perform(get(report, customer).header("Authorization", assigned)
                .param("dateMin", "2026-10-01").param("dateMax", "2026-10-01"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.totalAmount").value(0))
            .andExpect(jsonPath("$.purchases.totalElements").value(0));
    }

    @Test
    void commissionPlansAreReusableAndSellersAreOptedInByAnAdministrator() throws Exception {
        UUID admin = createUser("commission-admin");
        assignRole(admin, "ADMIN");
        String token = "Bearer " + loginHttp(email(admin));
        UUID first = createSeller("commission-first");
        UUID second = createSeller("commission-second");
        auditResources.add(first.toString());
        auditResources.add(second.toString());
        UUID sellerUser = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, first);
        String sellerToken = "Bearer " + loginHttp(email(sellerUser));
        String path = "/api/settings/commission-plans";
        UUID brand = UUID.randomUUID();
        brands.add(brand);
        jdbc.update("insert into catalog.brands(id, name, code, created_at) values (?, ?, ?, now())",
            brand, "Marca comisión " + brand, "COMM_" + brand);
        String name = "Plan comercial " + UUID.randomUUID();
        String rule = """
            {"scope":"DEFAULT","kind":"PERCENT","value":"2.1250"}
            """;
        String differentiated = """
            {"scope":"BRAND","targetId":"%s","kind":"FIXED","value":"150.0050"}
            """.formatted(brand);
        String payload = """
            {"name":"%s","rules":[%s,%s]}
            """.formatted(name, rule, differentiated);
        mockMvc.perform(get(path).header("Authorization", sellerToken)).andExpect(status().isForbidden());
        mockMvc.perform(post(path).header("Authorization", sellerToken)
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        String created = mockMvc.perform(post(path).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.rules[?(@.scope=='BRAND')].value").value(org.hamcrest.Matchers.contains("150.0050")))
            .andExpect(jsonPath("$.rules[?(@.scope=='DEFAULT')].value").value(org.hamcrest.Matchers.contains("2.1250")))
            .andReturn().getResponse().getContentAsString();
        UUID plan = UUID.fromString(objectMapper.readTree(created).get("id").asText());
        commissionPlans.add(plan);
        auditResources.add(plan.toString());
        String assignment = path + "/assignments/{sellerId}";
        String assign = """
            {"planId":"%s","expectedPlanId":null}
            """.formatted(plan);
        mockMvc.perform(get(assignment, first).header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.planId").isEmpty());
        mockMvc.perform(put(assignment, first).header("Authorization", sellerToken)
            .contentType(MediaType.APPLICATION_JSON).content(assign)).andExpect(status().isForbidden());
        for (UUID seller : List.of(first, second)) {
            mockMvc.perform(put(assignment, seller).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(assign))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planId").value(plan.toString()));
        }
        mockMvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + plan + "')].sellerCount").value(org.hamcrest.Matchers.contains(2)));

        String edit = """
            {"version":1,"name":"%s ajustado","rules":[%s]}
            """.formatted(name, differentiated);
        mockMvc.perform(put(path + "/{id}", plan).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.rules.length()").value(1));
        mockMvc.perform(put(path + "/{id}", plan).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(edit)).andExpect(status().isConflict());
        mockMvc.perform(put(assignment, second).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(assign)).andExpect(status().isConflict());

        String clear = """
            {"planId":null,"expectedPlanId":"%s"}
            """.formatted(plan);
        mockMvc.perform(put(assignment, first).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(clear))
            .andExpect(status().isOk()).andExpect(jsonPath("$.planId").isEmpty());
        mockMvc.perform(get(assignment, second).header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.planId").value(plan.toString()));
        assertThat(jdbc.queryForObject("""
            select count(*) from audit.audit_events where resource_id = ? and operation like 'COMMISSION_PLAN_%'
            """, Long.class, plan.toString())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
            select count(*) from audit.audit_events where resource_id = ? and operation = 'SELLER_COMMISSION_ASSIGNMENT'
            """, Long.class, first.toString())).isEqualTo(2L);

        String badRule = differentiated.replace(brand.toString(), UUID.randomUUID().toString());
        String invalid = """
            {"version":2,"name":"%s inválido","rules":[%s]}
            """.formatted(name, badRule);
        mockMvc.perform(put(path + "/{id}", plan).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        mockMvc.perform(get(path + "/{id}", plan).header("Authorization", token)).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value(name + " ajustado")).andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.rules[0].targetId").value(brand.toString()));
    }

    @Test
    void commissionsAccrueOnUnpaidDeliveryAndOnlyAdminRecordsTheReviewedPayout() throws Exception {
        UUID admin = createUser("commission-settlement-admin");
        assignRole(admin, "ADMIN");
        String token = "Bearer " + loginHttp(email(admin));
        UUID seller = createSeller("commission-settlement-seller");
        auditResources.add(seller.toString());
        UUID sellerUser = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, seller);
        String sellerToken = "Bearer " + loginHttp(email(sellerUser));
        var percentSale = createEditableSale(new BigDecimal("2"), new BigDecimal("100"), BigDecimal.ZERO);
        var fixedSale = createEditableSale(new BigDecimal("2"), new BigDecimal("100"), BigDecimal.ZERO);
        UUID plan = UUID.randomUUID();
        commissionPlans.add(plan);
        jdbc.update("insert into app.commission_plans(id, name) values (?, ?)", plan, "Liquidación " + plan);
        jdbc.update("insert into app.seller_commission_plans(seller_id, plan_id) values (?, ?)", seller, plan);
        jdbc.update("insert into app.commission_rules(plan_id, kind, value) values (?, 'PERCENT', 10)", plan);
        jdbc.update("insert into app.commission_rules(plan_id, product_id, kind, value) values (?, ?, 'FIXED', 4)",
            plan, fixedSale.productId());
        jdbc.update("update orders.orders set seller_id = ? where id in (?, ?)", seller, percentSale.orderId(), fixedSale.orderId());
        jdbc.update("update catalog.products set cost = 30 where id = ?", percentSale.productId());
        jdbc.update("update sale.sales set total = 180 where id = ?", percentSale.saleId());

        String statement = "/api/commissions/sellers/{sellerId}";
        String settle = statement + "/settlements";
        String attempt = "/api/orders/{orderId}/delivery-attempts";
        mockMvc.perform(get(statement, seller).header("Authorization", sellerToken)).andExpect(status().isForbidden());
        mockMvc.perform(post(attempt, percentSale.orderId()).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"result\":\"FAILED\",\"observation\":\"Ausente\"}"))
            .andExpect(status().isNoContent());
        mockMvc.perform(get(statement, seller).header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pending.entryCount").value(0));

        String delivered = "{\"result\":\"DELIVERED\"}";
        mockMvc.perform(post(attempt, percentSale.orderId()).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(delivered)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select paid from sale.sales where id = ?", BigDecimal.class, percentSale.saleId()))
            .isEqualByComparingTo(BigDecimal.ZERO);
        String preview = mockMvc.perform(get(statement, seller).header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pending.amount").value("12.0000"))
            .andExpect(jsonPath("$.movements.content[0].paidAt").isEmpty()).andReturn().getResponse().getContentAsString();
        String stale = objectMapper.writeValueAsString(Map.of("token", objectMapper.readTree(preview).at("/pending/token").asText()));

        jdbc.update("update catalog.products set cost = 90 where id = ?", percentSale.productId());
        jdbc.update("update app.commission_rules set value = 35 where plan_id = ? and kind = 'PERCENT'", plan);
        mockMvc.perform(post(attempt, fixedSale.orderId()).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(delivered)).andExpect(status().isNoContent());
        mockMvc.perform(post(attempt, fixedSale.orderId()).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(delivered)).andExpect(status().isConflict());
        mockMvc.perform(post(settle, seller).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(stale)).andExpect(status().isConflict());
        String ready = mockMvc.perform(get(statement, seller).header("Authorization", token).param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pending.amount").value("20.0000"))
            .andExpect(jsonPath("$.pending.entryCount").value(2)).andExpect(jsonPath("$.movements.totalPages").value(2))
            .andReturn().getResponse().getContentAsString();
        String payload = objectMapper.writeValueAsString(Map.of("token", objectMapper.readTree(ready).at("/pending/token").asText()));
        mockMvc.perform(post(settle, seller).header("Authorization", sellerToken)
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        mockMvc.perform(post(settle, seller).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.amount").value("20.0000")).andExpect(jsonPath("$.entryCount").value(2));
        mockMvc.perform(post(settle, seller).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isConflict());
        mockMvc.perform(get(statement, seller).header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pending.entryCount").value(0))
            .andExpect(jsonPath("$.movements.content[0].paidAt").isNotEmpty());

        UUID saleItem = jdbc.queryForObject("select id from sale.sale_items where sale_id = ?", UUID.class, percentSale.saleId());
        String returned = """
            {"reason":"Devuelto al vendedor","items":[{"saleItemId":"%s","quantity":0.5}]}
            """.formatted(saleItem);
        mockMvc.perform(post("/api/sales/{saleId}/returns", percentSale.saleId()).header("Authorization", token)
            .contentType(MediaType.APPLICATION_JSON).content(returned)).andExpect(status().isCreated());
        mockMvc.perform(get(statement, seller).header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pending.amount").value("-3.0000"))
            .andExpect(jsonPath("$.movements.content[0].returned").value(true))
            .andExpect(jsonPath("$.periodAmount").value("17.0000"));
        assertThat(jdbc.queryForObject("select amount from sale.commission_settlements where seller_id = ?", BigDecimal.class, seller))
            .isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("""
            select count(*) from audit.audit_events where resource_id = ? and operation = 'COMMISSION_SETTLEMENT'
            """, Long.class, seller.toString())).isEqualTo(1L);
    }

    private String reportSaleNumber(UUID saleId) {
        return "PG-" + saleId.toString().substring(0, 24);
    }

    private UUID reportSale(UUID customer, UUID seller, String status, String soldAt, String amount) {
        UUID order = createOrder(customer, seller, status);
        UUID sale = UUID.randomUUID();
        sales.add(sale);
        Timestamp date = Timestamp.from(Instant.parse(soldAt));
        jdbc.update("""
            insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, delivered_at, account_customer_id)
            values (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            """, sale, reportSaleNumber(sale), order, customer, status, new BigDecimal(amount), date,
            "DELIVERED".equals(status) ? date : null, customer);
        return sale;
    }

    private UUID createSeller(String suffix) {
        UUID userId = createUser("seller-" + suffix);
        UUID sellerId = UUID.randomUUID();
        sellers.add(sellerId);
        jdbc.update("insert into seller.seller_profiles(id, user_id, display_name, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
            sellerId, userId, "PG seller " + suffix, java.sql.Timestamp.from(Instant.now()));
        return sellerId;
    }

    private UUID createCustomer(UUID sellerId) {
        UUID id = UUID.randomUUID();
        customers.add(id);
        jdbc.update("insert into customer.customers(id, business_name, tax_id, seller_id, balance, status, created_at) values (?, ?, ?, ?, 0, 'ACTIVE', ?)",
            id, "PG customer", "PG-" + id, sellerId, java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private UUID createOrder(UUID customerId, UUID sellerId, String status) {
        UUID id = UUID.randomUUID();
        orders.add(id);
        jdbc.update("insert into orders.orders(id, order_number, customer_id, seller_id, status, subtotal, discount, total, created_at) values (?, ?, ?, ?, ?, 10, 0, 10, ?)",
            id, "PG-" + id.toString().substring(0, 24), customerId, sellerId, status, java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private UUID createCategory() {
        UUID id = UUID.randomUUID();
        categories.add(id);
        jdbc.update("insert into catalog.categories(id, name, code, status, created_at) values (?, 'PG category', ?, 'ACTIVE', ?)",
            id, "TEST_" + id, java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private UUID createBrand() {
        UUID id = UUID.randomUUID();
        brands.add(id);
        jdbc.update("insert into catalog.brands(id, name, code, status, created_at) values (?, 'PG brand', ?, 'ACTIVE', ?)",
            id, "TEST_" + id, java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private UUID createProduct(String suffix) {
        List<UUID> availableCategories = jdbc.queryForList("select id from catalog.categories where name = 'PG category' and status = 'ACTIVE'", UUID.class);
        List<UUID> availableBrands = jdbc.queryForList("select id from catalog.brands where name = 'PG brand' and status = 'ACTIVE'", UUID.class);
        UUID categoryId = availableCategories.isEmpty() ? createCategory() : availableCategories.getFirst();
        UUID brandId = availableBrands.isEmpty() ? createBrand() : availableBrands.getFirst();
        UUID id = productCommandService.create(new ProductCommandService.ProductInput(
            "PG " + suffix,
            "PG category", "unit", BigDecimal.ONE, List.of(new ProductCommandService.ProductPriceInput(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), BigDecimal.ONE)), categoryId, brandId));
        products.add(id);
        return id;
    }

    private SaleFixture createDeliveredSale(BigDecimal quantity) {
        UUID customerId = createCustomer(null);
        UUID orderId = createOrder(customerId, null, "DELIVERED");
        jdbc.update("update orders.orders set fulfillment_status = 'PREPARED', legacy_fulfillment = true where id = ?", orderId);
        UUID productId = createProduct("return-sale");
        jdbc.update("update inventory.inventory_balances set quantity = 2 where product_id = ?", productId);
        inventoryMovementService.apply(productId, quantity.negate(), "SALE", orderId, "Fixture sale");
        UUID saleId = UUID.randomUUID();
        sales.add(saleId);
        Timestamp now = Timestamp.from(Instant.now());
        String saleNumber = "PG-" + saleId.toString().substring(0, 24);
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, delivered_at, account_customer_id) values (?, ?, ?, ?, 'DELIVERED', 10, 0, ?, ?, ?)",
            saleId, saleNumber, orderId, customerId, now, now, customerId);
        UUID saleItemId = createSaleItem(saleId, productId, quantity);
        return new SaleFixture(saleId, orderId, productId, saleItemId);
    }

    private EditableSaleFixture createEditableSale(BigDecimal quantity, BigDecimal unitPrice, BigDecimal paid) {
        UUID customerId = createCustomer(null);
        UUID orderId = createOrder(customerId, null, "CONFIRMED");
        UUID productId = createProduct("editable-sale");
        BigDecimal total = quantity.multiply(unitPrice).setScale(4);
        BigDecimal debt = total.subtract(paid).setScale(4);
        jdbc.update("update orders.orders set subtotal = ?, total = ?, fulfillment_status = 'PREPARED', legacy_fulfillment = true where id = ?", total, total, orderId);
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", productId);
        inventoryMovementService.apply(productId, quantity.negate(), "SALE", orderId, "Fixture confirmed sale");
        UUID saleId = UUID.randomUUID();
        sales.add(saleId);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, account_customer_id) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?)",
            saleId, "PG-" + saleId.toString().substring(0, 24), orderId, customerId, total, paid, now, customerId);
        UUID saleItemId = createSaleItem(saleId, productId, quantity);
        jdbc.update("update sale.sale_items set unit_price = ?, line_total = ? where id = ?", unitPrice, total, saleItemId);
        UUID orderItemId = UUID.randomUUID();
        jdbc.update("insert into orders.order_items(id, order_id, product_id, product_name, quantity, unit_price, line_total, price_list_code, line_discount_percent) values (?, ?, ?, 'PG editable item', ?, ?, ?, 'GENERAL', 0)",
            orderItemId, orderId, productId, quantity, unitPrice, total);
        if (paid.signum() > 0) {
            jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, created_at) values (?, ?, ?, ?, 'CASH', ?)",
                UUID.randomUUID(), saleId, customerId, paid, now);
        }
        if (debt.signum() > 0) {
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)",
                UUID.randomUUID(), customerId, saleId, debt, now);
            jdbc.update("update customer.customers set balance = ? where id = ?", debt, customerId);
        }
        orders.add(orderId);
        return new EditableSaleFixture(orderId, saleId, customerId, productId);
    }

    private AccountDebtSale createAccountDebtSale(UUID customerId, BigDecimal debt, int secondsOlder) {
        UUID orderId = createOrder(customerId, null, "CONFIRMED");
        Timestamp createdAt = Timestamp.from(Instant.now().minusSeconds(secondsOlder));
        // Financial fixtures represent historical sales, already fulfilled before this workflow.
        jdbc.update("update orders.orders set subtotal = ?, total = ?, created_at = ?, fulfillment_status = 'PREPARED', legacy_fulfillment = true where id = ?", debt, debt, createdAt, orderId);
        UUID saleId = UUID.randomUUID();
        sales.add(saleId);
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, account_customer_id) values (?, ?, ?, ?, 'CONFIRMED', ?, 0, ?, ?)",
            saleId, "PG-" + saleId.toString().substring(0, 24), orderId, customerId, debt, createdAt, customerId);
        jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)",
            UUID.randomUUID(), customerId, saleId, debt, createdAt);
        jdbc.update("update customer.customers set balance = balance + ? where id = ?", debt, customerId);
        return new AccountDebtSale(orderId, saleId);
    }

    private void putGeneralPrice(UUID productId, BigDecimal price) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into catalog.product_prices(price_list_id, product_id, price, created_at, updated_at) values (?, ?, ?, ?, ?) on conflict (price_list_id, product_id) do update set price = excluded.price, updated_at = excluded.updated_at",
            UUID.fromString("00000000-0000-0000-0000-000000000001"), productId, price, now, now);
        jdbc.update("""
            insert into catalog.product_price_history
                (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
            values (?, ?, ?, ?, (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date, ?, ?)
            """, UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"),
            productId, price, now, now);
    }

    private UUID createSaleItem(UUID saleId, UUID productId, BigDecimal quantity) {
        UUID id = UUID.randomUUID();
        saleItems.add(id);
        jdbc.update("""
            insert into sale.sale_items(id, sale_id, product_id, product_name, quantity, unit_price, line_total,
                price_list_code, line_discount_percent)
            values (?, ?, ?, 'PG sale item', ?, 10, ?, 'GENERAL', 0)
            """, id, saleId, productId, quantity, quantity.multiply(BigDecimal.TEN));
        return id;
    }

    private boolean submitReturn(UUID actorId, SaleFixture fixture, CountDownLatch ready, CountDownLatch start) {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout waiting to start concurrent return");
            saleReturnService.create(fixture.saleId(), new SaleReturnDtos.ReturnRequest("Concurrent return",
                List.of(new SaleReturnDtos.ReturnItemRequest(fixture.saleItemId(), new BigDecimal("0.5")))));
            return true;
        } catch (IllegalStateException conflict) {
            if (conflict.getMessage() == null || !conflict.getMessage().contains("supera la cantidad")) throw conflict;
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean submitAccountPayment(UUID actorId, UUID customerId, CountDownLatch ready, CountDownLatch start) {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout waiting to start payment allocation");
            accountPaymentService.apply(customerId, new AccountPaymentDtos.PaymentRequest(
                new BigDecimal("10.0000"), "CASH", null, null));
            return true;
        } catch (IllegalStateException conflict) {
            if (conflict.getMessage() == null || !conflict.getMessage().contains("deuda")) throw conflict;
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UUID orderSeller(UUID orderId) {
        return jdbc.queryForObject("select seller_id from orders.orders where id = ?", UUID.class, orderId);
    }

    private String email(UUID id) {
        return "pg-" + id + "@integration.invalid";
    }

    private record SaleFixture(UUID saleId, UUID orderId, UUID productId, UUID saleItemId) { }
    private record EditableSaleFixture(UUID orderId, UUID saleId, UUID customerId, UUID productId) { }
    private record AccountDebtSale(UUID orderId, UUID saleId) { }

    private void deleteIds(String sqlTemplate, Set<UUID> ids) {
        if (ids.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.update(sqlTemplate.formatted(placeholders), ids.toArray());
    }

    private void deleteStringIds(String sqlTemplate, Set<String> ids) {
        if (ids.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.update(sqlTemplate.formatted(placeholders), ids.toArray());
    }
}
