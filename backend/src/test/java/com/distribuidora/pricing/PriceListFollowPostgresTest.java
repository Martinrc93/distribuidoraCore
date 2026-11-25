package com.distribuidora.pricing;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.catalog.application.ProductPriceValidationException;
import com.distribuidora.pricing.application.PriceListFollowService;
import com.distribuidora.pricing.application.PricingCommandService;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.pricing.infrastructure.InitialPriceListMigrationCallback;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class PriceListFollowPostgresTest {
    private static final UUID GENERAL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.randomUUID();
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private PricingCommandService pricing;
    private PricingQueryService queries;
    private ProductCommandService products;
    private PriceListFollowService follows;

    @BeforeAll
    static void migrateDisposableDatabase() {
        var datasource = new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),
            System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora"),
            System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora"));
        Flyway.configure().dataSource(datasource).callbacks(new InitialPriceListMigrationCallback()).load().migrate();
        jdbc = new JdbcTemplate(datasource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        jdbc.update("""
            insert into identity.users(id, email, password_hash, status, created_at, updated_at)
            values (?, ?, 'test', 'ACTIVE', now(), now()) on conflict (id) do nothing
            """, ACTOR, "pricelist-follow-" + ACTOR + "@example.test");
    }

    @BeforeEach
    void setUp() {
        follows = new PriceListFollowService(jdbc);
        pricing = new PricingCommandService(jdbc, mock(AuditService.class), follows);
        queries = new PricingQueryService(jdbc);
        products = new ProductCommandService(jdbc, mock(AuditService.class), follows);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ACTOR.toString(), null));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void newListCopiesGeneralPricesFollowsChangesUntilModifiedAndRemembersAcknowledgedWarnings() {
        rollback(() -> {
            UUID followed = product("100");
            UUID inactive = product("50");
            jdbc.update("update catalog.products set status = 'INACTIVE' where id = ?", inactive);
            LocalDate future = today().plusDays(5);
            pricing.setProductPrice(GENERAL, followed, new BigDecimal("120"), future);

            assertThat(pricing.creationPreview()).containsEntry("defaultListId", GENERAL)
                .containsEntry("defaultListName", "Lista general");
            UUID promo = pricing.createList("PROMO", "Promoción");

            assertThat(currentPrice(promo, followed)).isEqualByComparingTo("100");
            assertThat(scheduledPrice(promo, followed, future)).isEqualByComparingTo("120");
            assertThat(status(promo, followed)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("select count(*) from catalog.product_price_history where price_list_id = ? and product_id = ?",
                Integer.class, promo, inactive)).isZero();
            var pending = queries.prices(promo, 0, 100, "PENDING").content();
            assertThat(pending).anySatisfy(row -> assertThat(row).containsEntry("productId", followed).containsEntry("followStatus", "PENDING"));
            assertThat(queries.prices(promo, 0, 100, "OWN").content()).noneMatch(row -> row.get("productId").equals(followed));
            Map<String, Object> promoRow = queries.lists(0, 20).content().stream()
                .filter(row -> row.get("id").equals(promo)).findFirst().orElseThrow();
            assertThat(((Number) promoRow.get("pendingReviewCount")).intValue()).isGreaterThanOrEqualTo(1);

            assertThat(follows.unacknowledgedFollowers(followed)).extracting(PriceListFollowService.Follower::priceListId).contains(promo);
            pricing.setProductPrice(GENERAL, followed, new BigDecimal("110"), null, false);
            assertThat(currentPrice(promo, followed)).isEqualByComparingTo("110");
            assertThat(follows.unacknowledgedFollowers(followed)).isNotEmpty();
            pricing.setProductPrice(GENERAL, followed, new BigDecimal("115"), null, true);
            assertThat(currentPrice(promo, followed)).isEqualByComparingTo("115");
            assertThat(follows.unacknowledgedFollowers(followed)).isEmpty();

            assertThat(pricing.confirmFollowingPrices(promo, List.of(followed))).isEqualTo(1);
            assertThat(status(promo, followed)).isEqualTo("CONFIRMED");
            pricing.cancelScheduledPrice(GENERAL, followed, future);
            assertThat(scheduledPrice(promo, followed, future)).isNull();

            pricing.setProductPrice(promo, followed, new BigDecimal("130"), null);
            assertThat(status(promo, followed)).isNull();
            pricing.setProductPrice(GENERAL, followed, new BigDecimal("140"), null);
            assertThat(currentPrice(promo, followed)).isEqualByComparingTo("130");
            assertThat(currentPrice(GENERAL, followed)).isEqualByComparingTo("140");
        });
    }

    @Test
    void activatingAnInactiveListCopiesOnlyMissingPricesAndProductsFollowAfterCostChanges() {
        rollback(() -> {
            UUID inactiveList = inactiveList();
            UUID followed = product("100");
            UUID ownPrice = product("80");
            jdbc.update("""
                insert into catalog.product_price_history(id, price_list_id, product_id, price, effective_on, created_at, updated_at)
                values (?, ?, ?, 95, (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date, current_timestamp, current_timestamp)
                """, UUID.randomUUID(), inactiveList, ownPrice);

            pricing.setListStatus(inactiveList, "ACTIVE");
            assertThat(currentPrice(inactiveList, followed)).isEqualByComparingTo("100");
            assertThat(status(inactiveList, followed)).isEqualTo("PENDING");
            assertThat(currentPrice(inactiveList, ownPrice)).isEqualByComparingTo("95");
            assertThat(status(inactiveList, ownPrice)).isNull();
            assertThat(pricing.confirmFollowingPrices(inactiveList, null)).isGreaterThanOrEqualTo(1);
            assertThat(status(inactiveList, followed)).isEqualTo("CONFIRMED");

            UUID[] catalog = brandAndCategory();
            UUID created = products.create(new ProductCommandService.ProductInput("Created", null, null, BigDecimal.TEN,
                List.of(new ProductCommandService.ProductPriceInput(GENERAL, new BigDecimal("50"))), catalog[1], catalog[0]));
            assertThat(currentPrice(inactiveList, created)).isEqualByComparingTo("50");
            assertThat(status(inactiveList, created)).isEqualTo("PENDING");

            // Followers are not asked for replacement prices when the cost exceeds them: they take the General price.
            assertThatCode(() -> products.update(created, new ProductCommandService.ProductInput("Created", null, null,
                new BigDecimal("60"), List.of(new ProductCommandService.ProductPriceInput(GENERAL, new BigDecimal("70"))),
                catalog[1], catalog[0]), true)).doesNotThrowAnyException();
            assertThat(currentPrice(inactiveList, created)).isEqualByComparingTo("70");
            assertThat(follows.unacknowledgedFollowers(created)).isEmpty();
            assertThat(status(inactiveList, created)).isEqualTo("PENDING");

            products.setStatus(created, "INACTIVE");
            jdbc.update("delete from catalog.price_list_follows where product_id = ? and price_list_id = ?", created, inactiveList);
            jdbc.update("delete from catalog.product_price_history where product_id = ? and price_list_id = ?", created, inactiveList);
            products.setStatus(created, "ACTIVE");
            assertThat(currentPrice(inactiveList, created)).isEqualByComparingTo("70");
            assertThat(status(inactiveList, created)).isEqualTo("PENDING");
        });
    }

    @Test
    void costIncreaseStillRequiresPricesForListsWithTheirOwnPrice() {
        rollback(() -> {
            UUID[] catalog = brandAndCategory();
            UUID ownList = pricing.createList("OWN", "Own price");
            UUID followingList = pricing.createList("FOLLOWING", "Following General");
            UUID created = products.create(new ProductCommandService.ProductInput("Own price", null, null, BigDecimal.TEN,
                List.of(new ProductCommandService.ProductPriceInput(GENERAL, new BigDecimal("50"))), catalog[1], catalog[0]));
            pricing.setProductPrice(ownList, created, new BigDecimal("55"), null);
            var exception = org.assertj.core.api.Assertions.catchThrowableOfType(ProductPriceValidationException.class,
                () -> products.update(created, new ProductCommandService.ProductInput("Own price", null, null,
                    new BigDecimal("80"), List.of(), catalog[1], catalog[0])));
            assertThat(exception).isNotNull();
            assertThat(exception.getAffectedPriceLists()).extracting(ProductPriceValidationException.AffectedPriceList::priceListId)
                .contains(GENERAL, ownList)
                .doesNotContain(followingList);
        });
    }

    @Test
    void filtersListPricesByCategoryBrandAndName() {
        rollback(() -> {
            UUID[] first = brandAndCategory();
            UUID[] second = brandAndCategory();
            UUID flour = product("100");
            UUID rice = product("200");
            jdbc.update("update catalog.products set name = 'Harina 000', brand_id = ?, category_id = ? where id = ?", first[0], first[1], flour);
            jdbc.update("update catalog.products set name = 'Arroz largo', brand_id = ?, category_id = ? where id = ?", second[0], second[1], rice);

            var byCategory = queries.prices(GENERAL, 0, 100, new PricingQueryService.PriceFilter(null, first[1], null, null));
            assertThat(byCategory.content()).extracting(row -> row.get("productId")).containsExactly(flour);
            assertThat(byCategory.totalElements()).isEqualTo(1);
            assertThat(byCategory.content().getFirst()).containsEntry("categoryId", first[1]).containsEntry("brandId", first[0])
                .doesNotContainKey("cost");
            assertThat(queries.prices(GENERAL, 0, 100, new PricingQueryService.PriceFilter(null, first[1], null, null, true)).content().getFirst())
                .containsKey("cost");
            assertThat(queries.prices(GENERAL, 0, 100, new PricingQueryService.PriceFilter(null, null, second[0], null)).content())
                .extracting(row -> row.get("productId")).containsExactly(rice);
            assertThat(queries.prices(GENERAL, 0, 100, new PricingQueryService.PriceFilter(null, null, null, "ARROZ")).content())
                .extracting(row -> row.get("productId")).contains(rice).doesNotContain(flour);
            assertThat(queries.prices(GENERAL, 0, 100, new PricingQueryService.PriceFilter(null, first[1], second[0], null)).totalElements()).isZero();
        });
    }

    private UUID inactiveList() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into catalog.price_lists (id, code, name, status, created_at, updated_at)
            values (?, 'INACTIVE_TEST', 'Inactive list', 'INACTIVE', current_timestamp, current_timestamp)
            """, id);
        return id;
    }

    private UUID[] brandAndCategory() {
        UUID brand = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        jdbc.update("insert into catalog.brands(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', current_timestamp)", brand, "Follow brand " + brand, "TEST_" + brand);
        jdbc.update("insert into catalog.categories(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', current_timestamp)", category, "Follow category " + category, "TEST_" + category);
        return new UUID[]{brand, category};
    }

    private void rollback(Runnable body) {
        transactions.executeWithoutResult(transaction -> {
            try {
                body.run();
            } finally {
                transaction.setRollbackOnly();
            }
        });
    }

    private UUID product(String generalPrice) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products(id, name, cost, status, created_at) values (?, 'Follow test', 10, 'ACTIVE', current_timestamp)", id);
        jdbc.update("""
            insert into catalog.product_price_history(id, price_list_id, product_id, price, effective_on, created_at, updated_at)
            values (?, ?, ?, ?, (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date, current_timestamp - interval '1 minute', current_timestamp)
            """, UUID.randomUUID(), GENERAL, id, new BigDecimal(generalPrice));
        return id;
    }

    private LocalDate today() {
        return jdbc.queryForObject("select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class);
    }

    private BigDecimal currentPrice(UUID listId, UUID productId) {
        return jdbc.queryForList("""
            select price from catalog.product_price_history
            where price_list_id = ? and product_id = ? and effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
            order by effective_on desc, created_at desc, id desc limit 1
            """, BigDecimal.class, listId, productId).stream().findFirst().orElse(null);
    }

    private BigDecimal scheduledPrice(UUID listId, UUID productId, LocalDate date) {
        return jdbc.queryForList("select price from catalog.product_price_history where price_list_id = ? and product_id = ? and effective_on = ?",
            BigDecimal.class, listId, productId, date).stream().findFirst().orElse(null);
    }

    private String status(UUID listId, UUID productId) {
        return jdbc.queryForList("select review_status from catalog.price_list_follows where price_list_id = ? and product_id = ?",
            String.class, listId, productId).stream().findFirst().orElse(null);
    }
}
