package com.distribuidora.demo;

import com.distribuidora.DistribuidoraApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class DemoDataSeederPostgresTest {
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void refreshesSeedDatesWithoutDuplicatesOrChangesToRealActivity() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode("secret")).thenReturn("hash");
        var originalClock = Clock.fixed(Instant.parse("2026-10-02T01:00:00Z"), ZoneOffset.UTC);
        var seeder = new DemoDataSeeder(jdbc, passwordEncoder, true, "secret", originalClock);
        transaction.executeWithoutResult(status -> seeder.run(new DefaultApplicationArguments()));
        assertDateWindow(jdbc, LocalDate.of(2026, 10, 1));
        assertReferenceData(jdbc);
        assertPurchasingData(jdbc, LocalDate.of(2026, 10, 1));

        UUID realSupplierId = UUID.randomUUID();
        UUID realPurchaseId = UUID.randomUUID();
        Timestamp realPurchaseDate = Timestamp.from(Instant.parse("2026-10-10T16:00:00Z"));
        jdbc.update("""
            insert into supplier.suppliers(id, name, email, created_at, updated_at)
            values (?, 'Real supplier', 'real@example.test', ?, ?)
            """, realSupplierId, realPurchaseDate, realPurchaseDate);
        jdbc.update("""
            insert into purchasing.supplier_orders(id, order_number, supplier_id, supplier_name, order_date,
                total, idempotency_key, request_fingerprint, created_at)
            values (?, 'REAL-PURCHASE', ?, 'Real supplier', '2026-10-10', 10, 'real-purchase', repeat('a', 64), ?)
            """, realPurchaseId, realSupplierId, realPurchaseDate);
        jdbc.update("""
            insert into purchasing.supplier_order_items(id, order_id, product_id, product_name, quantity, unit_cost, line_total)
            select ?, ?, id, name, 1, 10, 10 from catalog.products order by id limit 1
            """, UUID.randomUUID(), realPurchaseId);
        var realSupplierBefore = jdbc.queryForMap("select * from supplier.suppliers where id = ?", realSupplierId);
        var realPurchaseBefore = jdbc.queryForMap("select * from purchasing.supplier_orders where id = ?", realPurchaseId);
        var purchasingBefore = jdbc.queryForList("""
            select id, order_number, supplier_id, supplier_name, total, idempotency_key, created_by
            from purchasing.supplier_orders order by id
            """);
        var purchaseItemsBefore = jdbc.queryForList("select * from purchasing.supplier_order_items order by id");
        var associationsBefore = jdbc.queryForList("select * from catalog.product_suppliers order by product_id, supplier_id");
        var usersBefore = jdbc.queryForList("select id, password_hash, status, failed_login_attempts from identity.users order by id");

        // Emulate products and customers from the old seed without normalized assignments.
        jdbc.update("update catalog.products set category_id = null, brand_id = null");
        jdbc.update("update customer.customers set zone = null");

        UUID seedSaleId = jdbc.queryForObject("select id from sale.sales where sale_number = 'V-000003'", UUID.class);
        UUID customerId = jdbc.queryForObject("select customer_id from sale.sales where id = ?", UUID.class, seedSaleId);
        UUID realOrderId = UUID.randomUUID();
        UUID realSaleId = UUID.randomUUID();
        UUID extraPaymentId = UUID.randomUUID();
        UUID extraLedgerId = UUID.randomUUID();
        Timestamp realDate = Timestamp.from(Instant.parse("2026-10-10T16:00:00Z"));
        jdbc.update("""
            insert into orders.orders(id, order_number, customer_id, seller_id, status, subtotal, discount, total, created_at)
            select ?, 'ORD-REAL', customer_id, seller_id, 'CONFIRMED', 10, 0, 10, ?
            from orders.orders where legacy_order_number = 'PED-00003'
            """, realOrderId, realDate);
        jdbc.update("""
            insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at)
            values (?, 'SAL-REAL', ?, ?, 'CONFIRMED', 10, 10, ?)
            """, realSaleId, realOrderId, customerId, realDate);
        jdbc.update("""
            insert into payment.payments(id, sale_id, customer_id, amount, method, created_at)
            values (?, ?, ?, 1, 'CASH', ?)
            """, extraPaymentId, seedSaleId, customerId, realDate);
        jdbc.update("""
            insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at)
            values (?, ?, ?, 'CREDIT', 1, ?)
            """, extraLedgerId, customerId, seedSaleId, realDate);
        jdbc.update("update customer.customers set balance = balance - 1 where id = ?", customerId);
        BigDecimal totalBefore = jdbc.queryForObject("select sum(total) from sale.sales", BigDecimal.class);
        BigDecimal balanceBefore = jdbc.queryForObject("select sum(balance) from customer.customers", BigDecimal.class);
        var idsBefore = jdbc.queryForList("select id from sale.sales order by id", UUID.class);
        int paymentsBefore = jdbc.queryForObject("select count(*) from payment.payments", Integer.class);
        int ledgerBefore = jdbc.queryForObject("select count(*) from customer.account_ledger", Integer.class);
        int movementsBefore = jdbc.queryForObject("select count(*) from inventory.stock_movements", Integer.class);
        var stockBefore = jdbc.queryForList("select product_id, quantity from inventory.inventory_balances order by product_id");

        // Simulate the old seed's historical dates before using the explicit refresh command.
        jdbc.update("update orders.orders set created_at = created_at - interval '90 days' where legacy_order_number like 'PED-%'");
        jdbc.update("update sale.sales set created_at = created_at - interval '90 days' where sale_number like 'V-%'");
        jdbc.update("update payment.payments set created_at = created_at - interval '90 days' where id <> ?", extraPaymentId);
        jdbc.update("update customer.account_ledger set created_at = created_at - interval '90 days' where id <> ?", extraLedgerId);
        jdbc.update("update inventory.stock_movements set created_at = created_at - interval '90 days' where movement_type = 'SALE'");
        var newClock = Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), ZoneOffset.UTC);
        var refresher = new DemoDataSeeder(jdbc, passwordEncoder, true, "secret", newClock);
        var refreshArgs = new DefaultApplicationArguments("--refresh-demo-dates");
        transaction.executeWithoutResult(status -> refresher.run(refreshArgs));
        transaction.executeWithoutResult(status -> refresher.run(refreshArgs));

        assertDateWindow(jdbc, LocalDate.of(2026, 11, 2));
        assertReferenceData(jdbc);
        assertPurchasingData(jdbc, LocalDate.of(2026, 11, 2));
        assertThat(jdbc.queryForMap("select * from supplier.suppliers where id = ?", realSupplierId)).isEqualTo(realSupplierBefore);
        assertThat(jdbc.queryForMap("select * from purchasing.supplier_orders where id = ?", realPurchaseId)).isEqualTo(realPurchaseBefore);
        assertThat(jdbc.queryForList("""
            select id, order_number, supplier_id, supplier_name, total, idempotency_key, created_by
            from purchasing.supplier_orders order by id
            """)).isEqualTo(purchasingBefore);
        assertThat(jdbc.queryForList("select * from purchasing.supplier_order_items order by id")).isEqualTo(purchaseItemsBefore);
        assertThat(jdbc.queryForList("select * from catalog.product_suppliers order by product_id, supplier_id")).isEqualTo(associationsBefore);
        assertThat(jdbc.queryForList("select id, password_hash, status, failed_login_attempts from identity.users order by id")).isEqualTo(usersBefore);
        assertThat(jdbc.queryForList("select id from sale.sales order by id", UUID.class)).isEqualTo(idsBefore);
        assertThat(jdbc.queryForObject("select count(*) from orders.orders", Integer.class)).isEqualTo(1001);
        assertThat(jdbc.queryForObject("select count(*) from demo.seed_runs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from payment.payments", Integer.class)).isEqualTo(paymentsBefore);
        assertThat(jdbc.queryForObject("select count(*) from customer.account_ledger", Integer.class)).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movements", Integer.class)).isEqualTo(movementsBefore);
        assertThat(jdbc.queryForList("select product_id, quantity from inventory.inventory_balances order by product_id"))
            .isEqualTo(stockBefore);
        assertThat(jdbc.queryForObject("select sum(total) from sale.sales", BigDecimal.class)).isEqualByComparingTo(totalBefore);
        assertThat(jdbc.queryForObject("select sum(balance) from customer.customers", BigDecimal.class)).isEqualByComparingTo(balanceBefore);
        assertThat(jdbc.queryForObject("select created_at from orders.orders where id = ?", Timestamp.class, realOrderId)).isEqualTo(realDate);
        assertThat(jdbc.queryForObject("select created_at from sale.sales where id = ?", Timestamp.class, realSaleId)).isEqualTo(realDate);
        assertThat(jdbc.queryForObject("select created_at from payment.payments where id = ?", Timestamp.class, extraPaymentId)).isEqualTo(realDate);
        assertThat(jdbc.queryForObject("select created_at from customer.account_ledger where id = ?", Timestamp.class, extraLedgerId)).isEqualTo(realDate);
        assertThat(jdbc.queryForObject("""
            select count(*) from payment.payments p join sale.sales s on s.id = p.sale_id
            where p.id <> ? and p.created_at <> s.created_at
            """, Integer.class, extraPaymentId)).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from customer.account_ledger l join sale.sales s on s.id = l.sale_id
            where l.id <> ? and l.created_at <> s.created_at
            """, Integer.class, extraLedgerId)).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from inventory.stock_movements m join sale.sales s on s.id = m.reference_id
            where m.movement_type = 'SALE' and m.created_at <> s.created_at
            """, Integer.class)).isZero();

        // Exercise the command's full startup, transactional runner and automatic shutdown.
        DistribuidoraApplication.main(new String[] {
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.profiles.active=local", "--app.seed-demo=true", "--refresh-demo-dates",
            "--app.seed-only=true", "--server.port=0", "--app.outbox.worker.enabled=false",
            "--app.notifications.retention.enabled=false", "--app.security.bootstrap-admin-email=",
            "--app.security.bootstrap-admin-password=", "--logging.level.root=WARN"
        });
        assertDateWindow(jdbc, LocalDate.now(ZONE));
        assertReferenceData(jdbc);
        assertPurchasingData(jdbc, LocalDate.now(ZONE));
    }

    private void assertPurchasingData(JdbcTemplate jdbc, LocalDate today) {
        assertThat(jdbc.queryForObject("select count(*) from supplier.suppliers where name like 'Proveedor Demo %'", Integer.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select count(*) from catalog.product_suppliers", Integer.class)).isEqualTo(533);
        assertThat(jdbc.queryForObject("select count(distinct product_id) from catalog.product_suppliers", Integer.class)).isEqualTo(400);
        assertThat(jdbc.queryForList("""
            select count(*) from purchasing.supplier_orders where idempotency_key like 'demo-supplier-orders-v1:%'
            group by supplier_id
            """, Integer.class)).hasSize(12).containsOnly(10);
        assertThat(jdbc.queryForObject("""
            select min(order_date) from purchasing.supplier_orders where idempotency_key like 'demo-supplier-orders-v1:%'
            """, LocalDate.class)).isEqualTo(today.minusDays(29));
        assertThat(jdbc.queryForObject("""
            select max(order_date) from purchasing.supplier_orders where idempotency_key like 'demo-supplier-orders-v1:%'
            """, LocalDate.class)).isEqualTo(today);
        assertThat(jdbc.queryForObject("""
            select count(*) from purchasing.supplier_orders o
            where o.idempotency_key like 'demo-supplier-orders-v1:%'
              and (o.total <> (select sum(line_total) from purchasing.supplier_order_items where order_id = o.id)
                or o.created_by <> (select id from identity.users where email = 'admin1@distribuidora.local')
                or o.created_by is null or length(o.request_fingerprint) <> 64)
            """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from purchasing.supplier_order_items i
            join purchasing.supplier_orders o on o.id = i.order_id
            join catalog.products p on p.id = i.product_id
            where o.idempotency_key like 'demo-supplier-orders-v1:%'
              and (i.unit_cost >= p.cost or i.line_total <> i.quantity * i.unit_cost
                or not exists (select 1 from catalog.product_suppliers ps
                               where ps.product_id = i.product_id and ps.supplier_id = o.supplier_id))
            """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from purchasing.supplier_order_items i join purchasing.supplier_orders o on o.id = i.order_id
            where o.idempotency_key like 'demo-supplier-orders-v1:%'
            """, Integer.class)).isEqualTo(420);
        assertThat(jdbc.queryForObject("""
            select count(*) from identity.role_permissions rp join identity.roles r on r.id = rp.role_id
            join identity.permissions p on p.id = rp.permission_id
            where r.code = 'SELLER' and p.code = 'ADMIN_ALL'
            """, Integer.class)).isZero();
    }

    private void assertReferenceData(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("select count(*) from catalog.categories", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from catalog.brands", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from customer.zones", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(distinct category_id) from catalog.products", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(distinct brand_id) from catalog.products", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from catalog.products where category_id is null or brand_id is null", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(distinct zone) from customer.customers", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("""
            select count(*) from customer.customers c
            where not exists (select 1 from customer.zones z where z.name = c.zone)
            """, Integer.class)).isZero();
    }

    private void assertDateWindow(JdbcTemplate jdbc, LocalDate today) {
        var dates = jdbc.queryForList("select created_at from sale.sales where sale_number like 'V-%'", Timestamp.class)
            .stream().map(date -> date.toInstant().atZone(ZONE).toLocalDate()).distinct().sorted().toList();
        assertThat(dates).containsExactlyElementsOf(today.minusDays(30).datesUntil(today.plusDays(31)).toList());
        assertThat(jdbc.queryForObject("""
            select count(*) from orders.orders o join sale.sales s on s.order_id = o.id
            where o.created_at <> s.created_at
            """, Integer.class)).isZero();
    }
}
