package com.distribuidora.purchasing;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.purchasing.application.SupplierOrderService;
import com.distribuidora.purchasing.application.SupplierOrderService.LineInput;
import com.distribuidora.purchasing.application.SupplierOrderService.OrderInput;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class SupplierOrderPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    SupplierOrderService service;
    AuditService audit;
    UUID supplier;
    UUID otherSupplier;
    UUID product;

    @BeforeAll static void migrate() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @BeforeEach void fixtures() {
        audit = mock(AuditService.class);
        service = new SupplierOrderService(jdbc, audit, mock(CurrentUserAccess.class));
        supplier = UUID.randomUUID(); otherSupplier = UUID.randomUUID(); product = UUID.randomUUID();
        for (UUID id : List.of(supplier, otherSupplier)) jdbc.update("insert into supplier.suppliers(id,name,created_at,updated_at) values (?, 'Supplier', now(), now())", id);
        jdbc.update("insert into catalog.products(id,name,description,cost,created_at) values (?, 'Product', 'Product', 12.3456, now())", product);
        jdbc.update("insert into inventory.inventory_balances(product_id,quantity,updated_at) values (?, 7, now())", product);
    }
    OrderInput input(String key, UUID supplierId, LocalDate date, BigDecimal quantity, BigDecimal cost) {
        return new OrderInput(supplierId, date, List.of(new LineInput(product, quantity, cost)), key);
    }
    SupplierOrderService.OrderResult create(OrderInput input) { return transactions.execute(status -> service.create(input)); }

    @Test void recordsSnapshotsWithoutChangingStockAndReplaysIdenticalRequests() {
        var request = input(UUID.randomUUID().toString(), supplier, LocalDate.of(2026,9,20), new BigDecimal("2.5"), null);
        var first = create(request);
        jdbc.update("update catalog.products set name='Renamed', cost=20 where id=?", product);
        assertThat(create(request)).isEqualTo(first);
        assertThat(first.total()).isEqualByComparingTo("30.8640");
        var detail = service.detail(first.id());
        var items = (List<java.util.Map<String,Object>>) detail.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("productName", "Product");
        assertThat((BigDecimal) items.getFirst().get("unitCost")).isEqualByComparingTo("12.3456");
        assertThat((BigDecimal) items.getFirst().get("currentCost")).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id=?", BigDecimal.class, product)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movements where product_id=?", Integer.class, product)).isZero();
        assertThatThrownBy(() -> create(input(request.idempotencyKey(), supplier, request.orderDate(), BigDecimal.ONE, null))).isInstanceOf(IllegalStateException.class);
        verify(audit, times(1)).record(any(), eq("SUPPLIER_ORDER_CREATE"), anyString(), anyString(), anyString(), anyMap());
    }
    @Test void filtersBeforePaginationAndLoadsOnlyTheLatestOrderOfTheExactSupplier() {
        create(input(UUID.randomUUID().toString(), supplier, LocalDate.of(2026,9,1), BigDecimal.ONE, BigDecimal.TEN));
        var last = create(input(UUID.randomUUID().toString(), supplier, LocalDate.of(2026,9,30), BigDecimal.ONE, BigDecimal.TEN));
        create(input(UUID.randomUUID().toString(), otherSupplier, LocalDate.of(2026,10,1), BigDecimal.ONE, BigDecimal.TEN));
        var page = service.list(0, 1, supplier, LocalDate.of(2026,9,1), LocalDate.of(2026,9,30));
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content().getFirst()).containsEntry("id", last.id()).containsEntry("date", "2026-09-30");
        assertThat(((java.util.Map<?,?>) service.lastOrder(supplier).get("order")).get("id")).isEqualTo(last.id());
        assertThat(service.list(0, 20, supplier, LocalDate.of(2026,9,30), LocalDate.of(2026,9,30)).content()).hasSize(1);
        assertThatThrownBy(() -> service.list(0,20,supplier,LocalDate.of(2026,10,1),LocalDate.of(2026,9,1))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsDuplicateProductsNegativeCostsAndInactiveProductsWithoutWritingOrders() {
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> create(new OrderInput(supplier,LocalDate.now(),List.of(new LineInput(product,BigDecimal.ONE,null),new LineInput(product,BigDecimal.ONE,null)),key))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(input(key,supplier,LocalDate.now(),BigDecimal.ZERO,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(input(key,supplier,LocalDate.now(),BigDecimal.ONE,new BigDecimal("-1")))).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("update catalog.products set status='INACTIVE' where id=?",product);
        assertThatThrownBy(() -> create(input(key,supplier,LocalDate.now(),BigDecimal.ONE,null))).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from purchasing.supplier_orders where idempotency_key=?",Integer.class,key)).isZero();
    }
    @Test void productSuppliersAreOptionalReplaceableAndPreservedWhenOmitted() {
        UUID category = UUID.randomUUID(), brand = UUID.randomUUID();
        jdbc.update("insert into catalog.categories(id,code,name,created_at) values (?, ?, 'Category', now())", category, category.toString());
        jdbc.update("insert into catalog.brands(id,code,name,created_at) values (?, ?, 'Brand', now())", brand, brand.toString());
        var products = new ProductCommandService(jdbc,audit);
        var associated = new ProductCommandService.ProductInput("Product",null,null,new BigDecimal("12.3456"),List.of(),category,brand,List.of(supplier,otherSupplier));
        transactions.executeWithoutResult(status -> products.update(product,associated));
        assertThat(jdbc.queryForList("select supplier_id from catalog.product_suppliers where product_id=?",UUID.class,product)).containsExactlyInAnyOrder(supplier,otherSupplier);
        transactions.executeWithoutResult(status -> products.update(product,new ProductCommandService.ProductInput("Product",null,null,new BigDecimal("12.3456"),List.of(),category,brand)));
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_suppliers where product_id=?",Integer.class,product)).isEqualTo(2);
        transactions.executeWithoutResult(status -> products.update(product,new ProductCommandService.ProductInput("Product",null,null,new BigDecimal("12.3456"),List.of(),category,brand,List.of())));
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_suppliers where product_id=?",Integer.class,product)).isZero();
    }
}
