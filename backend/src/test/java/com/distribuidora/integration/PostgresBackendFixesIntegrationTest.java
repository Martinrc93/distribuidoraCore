package com.distribuidora.integration;

import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.customer.api.AccountPaymentDtos;
import com.distribuidora.customer.application.AccountPaymentService;
import com.distribuidora.identity.api.AuthDtos;
import com.distribuidora.identity.application.AuthService;
import com.distribuidora.identity.application.InvalidRefreshTokenException;
import com.distribuidora.identity.application.UserAdminService;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

/** Opt-in verification of the recent backend fixes against a disposable PostgreSQL database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
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
    @Autowired UserAdminService userAdminService;
    @Autowired SellerCommandService sellerCommandService;
    @Autowired ProductCommandService productCommandService;
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
    private final Set<UUID> sellers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> customers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> orders = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> sales = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> saleItems = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> products = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> brands = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> categories = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> notificationRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();

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
        deleteIds("delete from catalog.product_prices where product_id in (%s)", products);
        deleteIds("delete from catalog.products where id in (%s)", products);
        deleteIds("delete from seller.seller_profiles where id in (%s)", sellers);
        deleteIds("delete from identity.refresh_tokens where user_id in (%s)", users);
        deleteIds("delete from identity.user_activation_tokens where user_id in (%s)", users);
        deleteIds("delete from identity.user_roles where user_id in (%s)", users);
        deleteIds("delete from identity.users where id in (%s)", users);
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
        sellers.clear();
        customers.clear();
        orders.clear();
        sales.clear();
        saleItems.clear();
        products.clear();
        brands.clear();
        categories.clear();
        notificationRequests.clear();
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
        AuthDtos.LoginResponse login = authService.login(new AuthDtos.LoginRequest(email(replayUser), "Passw0rd!"));
        AuthDtos.LoginResponse rotated = authService.refresh(new AuthDtos.RefreshRequest(login.refreshToken()));
        assertThat(rotated.refreshToken()).isNotBlank().isNotEqualTo(login.refreshToken());

        assertThatThrownBy(() -> authService.refresh(new AuthDtos.RefreshRequest(login.refreshToken())))
            .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, replayUser)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from identity.refresh_tokens where user_id = ? and revoked_at is null", Long.class, replayUser)).isZero();

        UUID logoutUser = createUser("logout");
        AuthDtos.LoginResponse logoutLogin = authService.login(new AuthDtos.LoginRequest(email(logoutUser), "Passw0rd!"));
        AuthDtos.LoginResponse anotherSession = authService.login(new AuthDtos.LoginRequest(email(logoutUser), "Passw0rd!"));
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
    void sellerReassignmentChangesOnlyConfirmedOrders() {
        UUID sourceSeller = createSeller("source");
        UUID targetSeller = createSeller("target");
        UUID customerId = createCustomer(sourceSeller);
        UUID confirmed = createOrder(customerId, sourceSeller, "CONFIRMED");
        UUID delivered = createOrder(customerId, sourceSeller, "DELIVERED");
        UUID cancelled = createOrder(customerId, sourceSeller, "CANCELLED");

        SellerDtos.ReassignCustomersResponse result = sellerCommandService.reassignCustomers(
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
    void productCategoryAndBrandReferencesPersistAndInactiveReferencesAreRejected() {
        UUID categoryId = createCategory();
        UUID brandId = createBrand();
        UUID productId = productCommandService.create(new ProductCommandService.ProductInput(
            "PG-" + UUID.randomUUID(), "PG integration product", "legacy", "unit", BigDecimal.ONE,
            null, categoryId, brandId));
        products.add(productId);

        assertThat(jdbc.queryForObject("select category_id from catalog.products where id = ?", UUID.class, productId)).isEqualTo(categoryId);
        assertThat(jdbc.queryForObject("select brand_id from catalog.products where id = ?", UUID.class, productId)).isEqualTo(brandId);
        assertThat(jdbc.queryForObject("select category from catalog.products where id = ?", String.class, productId)).isEqualTo("PG category");

        productCommandService.update(productId, new ProductCommandService.ProductInput(
            "PG-" + UUID.randomUUID(), "PG integration product updated", "legacy update", "unit", BigDecimal.ONE, null));
        assertThat(jdbc.queryForObject("select category_id from catalog.products where id = ?", UUID.class, productId)).isEqualTo(categoryId);
        assertThat(jdbc.queryForObject("select brand_id from catalog.products where id = ?", UUID.class, productId)).isEqualTo(brandId);

        jdbc.update("update catalog.categories set status = 'INACTIVE' where id = ?", categoryId);
        assertThatThrownBy(() -> productCommandService.create(new ProductCommandService.ProductInput(
            "PG-" + UUID.randomUUID(), "Inactive category", "legacy", "unit", BigDecimal.ONE, null, categoryId, null)))
            .isInstanceOf(IllegalArgumentException.class);
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

        OrderEditDtos.EditResponse result = orderConfirmationService.editConfirmed(fixture.orderId(),
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
    void accountPaymentSupportsFifoAndSpecificSaleAllocation() {
        UUID actorId = createUser("account-payment");
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(actorId.toString(), "test", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
        UUID customerId = createCustomer(null);
        AccountDebtSale older = createAccountDebtSale(customerId, new BigDecimal("30.0000"), 3600);
        AccountDebtSale newer = createAccountDebtSale(customerId, new BigDecimal("40.0000"), 1800);

        AccountPaymentDtos.PaymentResponse fifo = accountPaymentService.apply(customerId,
            new AccountPaymentDtos.PaymentRequest(new BigDecimal("35.0000"), "CASH", null, null));
        assertThat(fifo.allocationMode()).isEqualTo("FIFO");
        assertThat(fifo.allocations()).extracting(AccountPaymentDtos.Allocation::saleId)
            .containsExactly(older.saleId(), newer.saleId());
        assertThat(fifo.allocations()).extracting(AccountPaymentDtos.Allocation::amount)
            .containsExactly(new BigDecimal("30.0000"), new BigDecimal("5.0000"));

        AccountPaymentDtos.PaymentResponse specific = accountPaymentService.apply(customerId,
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
            id, "PG-CAT-" + id, java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private UUID createBrand() {
        UUID id = UUID.randomUUID();
        brands.add(id);
        jdbc.update("insert into catalog.brands(id, name, code, status, created_at) values (?, 'PG brand', ?, 'ACTIVE', ?)",
            id, "PG-BRAND-" + id, java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    private UUID createProduct(String suffix) {
        UUID id = productCommandService.create(new ProductCommandService.ProductInput(
            "PG-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 8), "PG " + suffix,
            "PG category", "unit", BigDecimal.ONE, null));
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
}
