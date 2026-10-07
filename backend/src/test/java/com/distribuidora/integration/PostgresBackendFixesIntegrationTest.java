package com.distribuidora.integration;

import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.pricing.application.PricingCommandService;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.customer.api.AccountPaymentDtos;
import com.distribuidora.customer.application.AccountPaymentService;
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
    @Autowired OrderConfirmationService orderConfirmationService;
    @Autowired DeliveryLifecycleService deliveryLifecycleService;
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
    private final Set<UUID> notificationRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> auditResources = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanFixtures() {
        SecurityContextHolder.clearContext();
        jdbc.update("update app.business_settings set credit_limit = null, updated_by = null, updated_at = null where id = 1");
        deleteIds("delete from customer.account_ledger where sale_id in (%s)", sales);
        deleteIds("delete from payment.payments where sale_id in (%s)", sales);
        deleteIds("delete from sale.return_items where sale_id in (%s)", sales);
        deleteIds("delete from sale.returns where sale_id in (%s)", sales);
        deleteIds("delete from sale.sale_items where sale_id in (%s)", sales);
        deleteIds("delete from sale.sales where id in (%s)", sales);
        deleteIds("delete from orders.order_items where order_id in (%s)", orders);
        deleteIds("delete from orders.delivery_attempts where order_id in (%s)", orders);
        deleteIds("delete from notification.outbox_events where aggregate_id in (%s)", notificationRequests);
        deleteIds("delete from notification.outbox_events where aggregate_id in (%s)", orders);
        deleteIds("delete from orders.orders where id in (%s)", orders);
        deleteIds("delete from customer.customers where id in (%s)", customers);
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

        userAdminService.changeRole(targetId, com.distribuidora.identity.api.UserAdminDtos.Role.ADMIN.name());
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
        String roleCode = createRoleWithPermission("ORDER_CREATE");
        assignRole(targetId, roleCode);

        String adminToken = loginHttp(email(adminId));
        String oldTargetToken = loginHttp(email(targetId));
        assertThat(jwtService.parse(oldTargetToken).get("authorities", List.class))
            .containsExactly("ORDER_CREATE");

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
        String roleCode = createRoleWithPermission("ORDER_CREATE");
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

    private BigDecimal stock(UUID productId) {
        return jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?",
            BigDecimal.class, productId);
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

    @Test
    void sellerReadsOnlyAssignedCustomersAndOwnedSalesAndOrdersAcrossApiRoutes() throws Exception {
        UUID sellerId = createSeller("read-scope");
        UUID otherSellerId = createSeller("foreign-read-scope");
        UUID sellerUser = jdbc.queryForObject("select user_id from seller.seller_profiles where id = ?", UUID.class, sellerId);
        assignRole(sellerUser, "SELLER");
        UUID admin = createUser("read-scope-admin");
        assignRole(admin, "ADMIN");
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
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                    "email", email,
                    "password", "Passw0rd!"))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
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
        UUID productId = createProduct("return-sale");
        jdbc.update("update inventory.inventory_balances set quantity = 2 where product_id = ?", productId);
        inventoryMovementService.apply(productId, quantity.negate(), "SALE", orderId, "Fixture sale");
        UUID saleId = UUID.randomUUID();
        sales.add(saleId);
        Timestamp now = Timestamp.from(Instant.now());
        String saleNumber = "PG-" + saleId.toString().substring(0, 24);
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at, delivered_at) values (?, ?, ?, ?, 'DELIVERED', 10, 0, ?, ?)",
            saleId, saleNumber, orderId, customerId, now, now);
        UUID saleItemId = createSaleItem(saleId, productId, quantity);
        return new SaleFixture(saleId, orderId, productId, saleItemId);
    }

    private EditableSaleFixture createEditableSale(BigDecimal quantity, BigDecimal unitPrice, BigDecimal paid) {
        UUID customerId = createCustomer(null);
        UUID orderId = createOrder(customerId, null, "CONFIRMED");
        UUID productId = createProduct("editable-sale");
        BigDecimal total = quantity.multiply(unitPrice).setScale(4);
        BigDecimal debt = total.subtract(paid).setScale(4);
        jdbc.update("update orders.orders set subtotal = ?, total = ? where id = ?", total, total, orderId);
        jdbc.update("update inventory.inventory_balances set quantity = 10 where product_id = ?", productId);
        inventoryMovementService.apply(productId, quantity.negate(), "SALE", orderId, "Fixture confirmed sale");
        UUID saleId = UUID.randomUUID();
        sales.add(saleId);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)",
            saleId, "PG-" + saleId.toString().substring(0, 24), orderId, customerId, total, paid, now);
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
        jdbc.update("update orders.orders set subtotal = ?, total = ?, created_at = ? where id = ?", debt, debt, createdAt, orderId);
        UUID saleId = UUID.randomUUID();
        sales.add(saleId);
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, ?, ?, ?, 'CONFIRMED', ?, 0, ?)",
            saleId, "PG-" + saleId.toString().substring(0, 24), orderId, customerId, debt, createdAt);
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
