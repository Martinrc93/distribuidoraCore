package com.distribuidora.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Component
@Profile("!test")
public class DemoDataSeeder implements ApplicationRunner {
    private static final String SEED_NAME = "demo-v1";
    private static final int PRODUCT_COUNT = 500;
    private static final int CUSTOMER_COUNT = 300;
    private static final int SALE_COUNT = 1_000;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String password;

    public DemoDataSeeder(
        JdbcTemplate jdbc,
        PasswordEncoder passwordEncoder,
        @Value("${app.seed-demo:false}") boolean enabled,
        @Value("${app.demo-password:ChangeMe123!}") String password
    ) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        ensureProductPrices();
        if (alreadySeeded()) {
            repairSeedRoles();
            repairSeedLedger();
            return;
        }

        String hash = passwordEncoder.encode(password);
        insertUsers(hash, "admin", 2);
        List<SeedUser> sellers = insertUsers(hash, "vendedor", 3);
        repairSeedRoles();
        List<UUID> sellerProfiles = insertSellerProfiles(sellers);
        List<UUID> products = insertProducts();
        ensureProductPrices();
        List<UUID> customers = insertCustomers(sellerProfiles);
        insertSales(products, customers, sellerProfiles);
        jdbc.update("insert into demo.seed_runs(name, created_at) values (?, ?)", SEED_NAME, timestamp(Instant.now()));
    }

    private boolean alreadySeeded() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from demo.seed_runs where name = ?)", Boolean.class, SEED_NAME
        ));
    }

    private void ensureProductPrices() {
        jdbc.update("""
            INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
            SELECT price_lists.id, products.id, products.price, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM catalog.price_lists
            CROSS JOIN catalog.products
            WHERE price_lists.code IN ('GENERAL', 'LISTA_2', 'LISTA_3')
              AND price_lists.status = 'ACTIVE'
            ON CONFLICT (price_list_id, product_id) DO NOTHING
            """);
    }

    private List<SeedUser> insertUsers(String hash, String prefix, int count) {
        List<SeedUser> users = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            UUID id = UUID.randomUUID();
            String email = prefix + index + "@distribuidora.local";
            jdbc.update("""
                insert into identity.users(id, email, password_hash, status, failed_login_attempts, created_at, updated_at, version)
                values (?, ?, ?, 'ACTIVE', 0, ?, ?, 0)
                on conflict (id) do nothing
                """, id, email, hash, timestamp(Instant.now()), timestamp(Instant.now()));
            users.add(new SeedUser(id, email));
        }
        return users;
    }

    private void repairSeedRoles() {
        UUID adminRole = jdbc.queryForObject("select id from identity.roles where code = 'ADMIN'", UUID.class);
        UUID sellerRole = jdbc.queryForObject("select id from identity.roles where code = 'SELLER'", UUID.class);
        UUID adminAll = jdbc.queryForObject("select id from identity.permissions where code = 'ADMIN_ALL'", UUID.class);
        UUID userManage = jdbc.queryForObject("select id from identity.permissions where code = 'USER_MANAGE'", UUID.class);
        UUID stockAdjust = jdbc.queryForObject("select id from identity.permissions where code = 'STOCK_ADJUST'", UUID.class);
        UUID orderCreate = jdbc.queryForObject("select id from identity.permissions where code = 'ORDER_CREATE'", UUID.class);
        UUID saleDeliver = jdbc.queryForObject("select id from identity.permissions where code = 'SALE_DELIVER'", UUID.class);
        jdbc.update("insert into identity.role_permissions(role_id, permission_id) values (?, ?) on conflict do nothing", adminRole, adminAll);
        jdbc.update("insert into identity.role_permissions(role_id, permission_id) values (?, ?) on conflict do nothing", adminRole, userManage);
        jdbc.update("insert into identity.role_permissions(role_id, permission_id) values (?, ?) on conflict do nothing", adminRole, stockAdjust);
        jdbc.update("insert into identity.role_permissions(role_id, permission_id) values (?, ?) on conflict do nothing", sellerRole, orderCreate);
        jdbc.update("insert into identity.role_permissions(role_id, permission_id) values (?, ?) on conflict do nothing", sellerRole, saleDeliver);
        jdbc.update("insert into identity.user_roles(user_id, role_id) select id, ? from identity.users where email like 'admin%@distribuidora.local' on conflict do nothing", adminRole);
        jdbc.update("insert into identity.user_roles(user_id, role_id) select id, ? from identity.users where email like 'vendedor%@distribuidora.local' on conflict do nothing", sellerRole);
    }

    private List<UUID> insertSellerProfiles(List<SeedUser> sellers) {
        List<UUID> profiles = new ArrayList<>();
        for (int index = 0; index < sellers.size(); index++) {
            UUID id = UUID.randomUUID();
            profiles.add(id);
            jdbc.update("insert into seller.seller_profiles(id, user_id, display_name, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
                id, sellers.get(index).id(), "Vendedor Demo " + (index + 1), timestamp(Instant.now()));
        }
        return profiles;
    }

    private List<UUID> insertProducts() {
        String[] categories = {"Bebidas", "Almacén", "Limpieza", "Snacks", "Lácteos"};
        List<UUID> products = new ArrayList<>();
        for (int index = 1; index <= PRODUCT_COUNT; index++) {
            UUID id = UUID.randomUUID();
            products.add(id);
            BigDecimal cost = BigDecimal.valueOf(500 + (index % 100) * 37L);
            BigDecimal price = cost.multiply(BigDecimal.valueOf(1.35)).setScale(4);
            jdbc.update("""
                insert into catalog.products(id, sku, name, category, presentation, cost, price, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, id, "SKU-%04d".formatted(index), "Producto Demo %03d".formatted(index),
                categories[index % categories.length], "Unidad", cost, price, timestamp(Instant.now()));
            BigDecimal stock = BigDecimal.valueOf(20 + (index % 80));
            jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, ?, ?)", id, stock, timestamp(Instant.now()));
            jdbc.update("insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, reference_type, created_at) values (?, ?, 'MANUAL_ENTRY', ?, ?, 'DEMO_SEED', ?)",
                UUID.randomUUID(), id, stock, "Carga inicial demo", timestamp(Instant.now()));
        }
        return products;
    }

    private List<UUID> insertCustomers(List<UUID> sellerProfiles) {
        List<UUID> customers = new ArrayList<>();
        for (int index = 1; index <= CUSTOMER_COUNT; index++) {
            UUID id = UUID.randomUUID();
            customers.add(id);
            jdbc.update("""
                insert into customer.customers(id, business_name, tax_id, seller_id, balance, status, created_at)
                values (?, ?, ?, ?, 0, 'ACTIVE', ?)
                """, id, "Cliente Demo %03d".formatted(index), "30-7%08d-%d".formatted(index, index % 10),
                sellerProfiles.get(index % sellerProfiles.size()), timestamp(Instant.now()));
        }
        return customers;
    }

    private void insertSales(List<UUID> products, List<UUID> customers, List<UUID> sellerProfiles) {
        Random random = new Random(20260918L);
        UUID generalPriceListId = jdbc.queryForObject(
            "select id from catalog.price_lists where code = 'GENERAL' and is_default = true", UUID.class);
        for (int index = 1; index <= SALE_COUNT; index++) {
            UUID orderId = UUID.randomUUID();
            UUID saleId = UUID.randomUUID();
            UUID customerId = customers.get(index % customers.size());
            Instant createdAt = Instant.now().minus(index % 90, ChronoUnit.DAYS);
            BigDecimal total = BigDecimal.ZERO;
            List<Line> lines = new ArrayList<>();
            int lineCount = 2 + random.nextInt(4);
            for (int line = 0; line < lineCount; line++) {
                UUID productId = products.get(random.nextInt(products.size()));
                Map<String, Object> product = jdbc.queryForMap("select name, price from catalog.products where id = ?", productId);
                BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(8));
                BigDecimal price = (BigDecimal) product.get("price");
                BigDecimal lineTotal = price.multiply(quantity).setScale(4);
                total = total.add(lineTotal);
                lines.add(new Line(productId, (String) product.get("name"), quantity, price, lineTotal));
            }
            String status = index % 10 == 0 ? "DELIVERED" : "CONFIRMED";
            jdbc.update("insert into orders.orders(id, order_number, customer_id, seller_id, status, subtotal, discount, total, created_at) values (?, ?, ?, ?, ?, ?, 0, ?, ?)",
                orderId, "PED-%05d".formatted(index), customerId, sellerProfiles.get(index % sellerProfiles.size()), status, total, total, timestamp(createdAt));
            jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                saleId, "V-%06d".formatted(index), orderId, customerId, status, total, total.multiply(BigDecimal.valueOf(index % 3 == 0 ? 0.5 : 1)), timestamp(createdAt));
            for (Line line : lines) {
                jdbc.update("insert into orders.order_items(id, order_id, product_id, product_name, quantity, unit_price, line_total, price_list_id, price_list_code, line_discount_percent) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), orderId, line.productId(), line.name(), line.quantity(), line.price(), line.total(), generalPriceListId, "GENERAL", BigDecimal.ZERO.setScale(4));
                jdbc.update("insert into sale.sale_items(id, sale_id, product_id, product_name, quantity, unit_price, line_total, price_list_id, price_list_code, line_discount_percent) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), saleId, line.productId(), line.name(), line.quantity(), line.price(), line.total(), generalPriceListId, "GENERAL", BigDecimal.ZERO.setScale(4));
                jdbc.update("update inventory.inventory_balances set quantity = quantity - ?, updated_at = ? where product_id = ?", line.quantity(), timestamp(Instant.now()), line.productId());
                jdbc.update("insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, reference_type, reference_id, created_at) values (?, ?, 'SALE', ?, ?, 'SALE', ?, ?)",
                    UUID.randomUUID(), line.productId(), line.quantity().negate(), "Venta demo", saleId, timestamp(createdAt));
            }
            BigDecimal paid = total.multiply(BigDecimal.valueOf(index % 3 == 0 ? 0.5 : 1)).setScale(4);
            if (paid.signum() > 0) {
                jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, created_at) values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), saleId, customerId, paid, index % 2 == 0 ? "BANK_TRANSFER" : "CASH", timestamp(createdAt));
            }
            BigDecimal debt = total.subtract(paid);
            if (debt.signum() > 0) {
                jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)",
                    UUID.randomUUID(), customerId, saleId, debt, timestamp(createdAt));
                jdbc.update("update customer.customers set balance = balance + ? where id = ?", debt, customerId);
            }
        }
    }

    private void repairSeedLedger() {
        List<RepairLedgerEntry> missing = jdbc.query("""
            SELECT s.id, s.customer_id, s.total - s.paid AS amount, s.created_at
            FROM sale.sales s
            WHERE s.sale_number LIKE 'V-%'
              AND s.total > s.paid
              AND NOT EXISTS (
                  SELECT 1 FROM customer.account_ledger l
                  WHERE l.sale_id = s.id AND l.entry_type = 'DEBIT'
              )
            """, (rs, rowNum) -> new RepairLedgerEntry(
                rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getBigDecimal("amount"), rs.getTimestamp("created_at")));
        for (RepairLedgerEntry entry : missing) {
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)",
                UUID.randomUUID(), entry.customerId(), entry.saleId(), entry.amount(), entry.createdAt());
        }
        jdbc.update("""
            UPDATE customer.customers c
            SET balance = COALESCE((
                SELECT SUM(CASE WHEN l.entry_type = 'DEBIT' THEN l.amount
                                WHEN l.entry_type = 'CREDIT' THEN -l.amount ELSE 0 END)
                FROM customer.account_ledger l
                WHERE l.customer_id = c.id
            ), 0)
            WHERE EXISTS (
                SELECT 1 FROM sale.sales s
                WHERE s.customer_id = c.id AND s.sale_number LIKE 'V-%'
            )
            """);
    }

    private record SeedUser(UUID id, String email) { }
    private record Line(UUID productId, String name, BigDecimal quantity, BigDecimal price, BigDecimal total) { }
    record RepairLedgerEntry(UUID saleId, UUID customerId, BigDecimal amount, Timestamp createdAt) { }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
