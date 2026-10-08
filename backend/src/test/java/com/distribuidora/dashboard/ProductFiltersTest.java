package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.ReadQueryController;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.dashboard.application.ReadQueryService.StockFilter;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ProductFiltersTest {
    private JdbcTemplate jdbc;
    private ReadQueryService service;
    private final UUID brand = UUID.randomUUID();
    private final UUID category = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create schema catalog");
        jdbc.execute("create schema inventory");
        jdbc.execute("create schema supplier");
        jdbc.execute("create table supplier.suppliers(id uuid primary key, name varchar)");
        jdbc.execute("create table catalog.product_suppliers(product_id uuid, supplier_id uuid)");
        jdbc.execute("create table catalog.categories(id uuid primary key, name varchar)");
        jdbc.execute("create table catalog.products(id uuid primary key, name varchar, category varchar, category_id uuid, brand_id uuid, presentation varchar, status varchar, cost decimal, description varchar)");
        jdbc.execute("create table inventory.inventory_balances(product_id uuid primary key, quantity decimal(12, 2))");
        jdbc.update("insert into catalog.categories values (?, 'Grocery')", category);
        addProduct("Flour A", brand, category, 3.5);
        addProduct("Flour B", brand, category, 1.0);
        addProduct("Flour Negative", brand, category, -0.5);
        addProduct("Flour Zero", brand, category, 0.0);
        addProduct("Flour Missing", brand, category, null);
        addProduct("Flour Other Brand", UUID.randomUUID(), category, 10.0);
        addProduct("Flour Other Category", brand, null, 10.0);
        addProduct("Rice", brand, category, 10.0);
        service = new ReadQueryService(jdbc);
    }

    private void addProduct(String name, UUID brandId, UUID categoryId, Double stock) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products values (?, ?, 'Legacy', ?, ?, 'Bag', 'ACTIVE', 10, ?)", id, name, categoryId, brandId, name);
        if (stock != null) jdbc.update("insert into inventory.inventory_balances values (?, ?)", id, stock);
    }

    @Test
    void combinesExactCatalogFiltersWithNameAndPaginatesFilteredTotals() {
        var first = service.products(0, 1, " flour ", true, brand, category, StockFilter.POSITIVE);
        var second = service.products(1, 1, "flour", true, brand, category, StockFilter.POSITIVE);
        assertThat(first.content()).extracting(row -> row.get("name")).containsExactly("Flour A");
        assertThat(second.content()).extracting(row -> row.get("name")).containsExactly("Flour B");
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(2);
    }

    @Test
    void bothIncludesZeroAndMissingBalancesWhileNegativeIsStrictlyBelowZero() {
        var all = service.products(0, 20, "flour", true, brand, category, StockFilter.ALL);
        var negative = service.products(0, 20, "flour", true, brand, category, StockFilter.NEGATIVE);
        assertThat(all.totalElements()).isEqualTo(5);
        assertThat(all.content()).extracting(row -> row.get("name")).contains("Flour Zero", "Flour Missing");
        assertThat(negative.content()).extracting(row -> row.get("name")).containsExactly("Flour Negative");
        assertThat(negative.totalElements()).isEqualTo(1);
        assertThat(service.products(0, 20, "", true, null, null, StockFilter.ALL).totalElements()).isEqualTo(8);
    }

    @Test
    void includesOptionalSupplierNamesAndIdsForOnlyTheVisibleProductPage() {
        UUID supplier = UUID.randomUUID();
        UUID product = jdbc.queryForObject("select id from catalog.products where name='Flour A'", UUID.class);
        jdbc.update("insert into supplier.suppliers values (?, 'North supplier')", supplier);
        jdbc.update("insert into catalog.product_suppliers values (?, ?)", product, supplier);
        var first = service.products(0,1,"flour",true,brand,category,StockFilter.POSITIVE);
        var second = service.products(1,1,"flour",true,brand,category,StockFilter.POSITIVE);
        assertThat(first.content().getFirst().get("suppliers")).isEqualTo(List.of(java.util.Map.of("id",supplier,"name","North supplier")));
        assertThat(second.content().getFirst().get("suppliers")).isEqualTo(List.of());
    }

    @Test
    void filtersStockWithoutProjectingItAndPreservesSellerCostRestrictions() {
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.isAdmin()).thenReturn(false);
        var sellerService = new ReadQueryService(jdbc, access);
        var result = sellerService.products(0, 20, "flour", false, brand, category, StockFilter.NEGATIVE);
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().getFirst()).doesNotContainKeys("stock", "cost");
    }

    @Test
    void bindsFiltersAndRejectsInvalidStockAndCatalogIds() throws Exception {
        var queries = mock(ReadQueryService.class);
        when(queries.products(2, 10, "flour", true, brand, category, StockFilter.NEGATIVE))
            .thenReturn(new PageResponse<>(List.of(), 2, 10, 0, 0));
        var mvc = standaloneSetup(new ReadQueryController(queries)).build();
        mvc.perform(get("/api/products").param("page", "2").param("size", "10").param("search", "flour")
                .param("brandId", brand.toString()).param("categoryId", category.toString()).param("stock", "NEGATIVE"))
            .andExpect(status().isOk());
        verify(queries).products(2, 10, "flour", true, brand, category, StockFilter.NEGATIVE);
        mvc.perform(get("/api/products").param("stock", "invalid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/products").param("brandId", "invalid")).andExpect(status().isBadRequest());
    }
}
