package com.distribuidora.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Component
@Profile("!test")
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final int DAYS_BEFORE = 30;
    private static final int DAYS_AFTER = 30;
    private static final String SEED_NAME = "demo-v1";
    private static final int PRODUCT_COUNT = 500;
    private static final int CUSTOMER_COUNT = 300;
    private static final int SALE_COUNT = 1_000;
    private static final String[] CATEGORY_NAMES = {"Bebidas", "Almac\u00e9n", "Limpieza"};
    private static final String[] BRAND_NAMES = {
        "Marca Demo 01", "Marca Demo 02", "Marca Demo 03", "Marca Demo 04", "Marca Demo 05", "Marca Demo 06"
    };
    private static final int ZONE_COUNT = 10;
    private static final int SUPPLIER_COUNT = 12;
    private static final int SUPPLIER_ORDER_COUNT = 120;
    private static final String PURCHASING_SEED_PREFIX = "demo-supplier-orders-v1:";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String password;
    private final Clock clock;

    @Autowired
    public DemoDataSeeder(
        JdbcTemplate jdbc,
        PasswordEncoder passwordEncoder,
        @Value("${app.seed-demo:false}") boolean enabled,
        @Value("${app.demo-password:ChangeMe123!}") String password
    ) {
        this(jdbc, passwordEncoder, enabled, password, Clock.system(ZONE));
    }

    DemoDataSeeder(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, boolean enabled, String password, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.password = password;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        ensureDemoReferenceData();
        ensureProductPrices();
        if (alreadySeeded()) {
            repairSeedReferenceAssignments();
            repairSeedRoles();
            repairSeedLedger();
            if (args.containsOption("refresh-demo-dates")) {
                refreshDemoDates(today);
            }
            ensureDemoPurchasing(today, args.containsOption("refresh-demo-dates"));
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
        insertSales(products, customers, sellerProfiles, today);
        ensureDemoPurchasing(today, false);
        jdbc.update("insert into demo.seed_runs(name, created_at) values (?, ?)", SEED_NAME, timestamp(Instant.now()));
        log.info("Created {} demo orders and sales between {} and {}", SALE_COUNT,
            today.minusDays(DAYS_BEFORE), today.plusDays(DAYS_AFTER));
    }

    private boolean alreadySeeded() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from demo.seed_runs where name = ?)", Boolean.class, SEED_NAME
        ));
    }

    private void ensureDemoReferenceData() {
        Timestamp now = timestamp(clock.instant());
        for (int index = 0; index < CATEGORY_NAMES.length; index++) {
            jdbc.update("""
                insert into catalog.categories(id, name, code, status, created_at)
                values (?, ?, ?, 'ACTIVE', ?) on conflict do nothing
                """, UUID.randomUUID(), CATEGORY_NAMES[index], "DEMO_CAT_%02d".formatted(index + 1), now);
        }
        for (int index = 0; index < BRAND_NAMES.length; index++) {
            jdbc.update("""
                insert into catalog.brands(id, name, code, status, created_at)
                values (?, ?, ?, 'ACTIVE', ?) on conflict do nothing
                """, UUID.randomUUID(), BRAND_NAMES[index], "DEMO_BRAND_%02d".formatted(index + 1), now);
        }
        for (int index = 1; index <= ZONE_COUNT; index++) {
            jdbc.update("""
                insert into customer.zones(id, name, created_at, updated_at)
                values (?, ?, ?, ?) on conflict (name) do nothing
                """, UUID.randomUUID(), zoneName(index), now, now);
        }
    }

    private void repairSeedReferenceAssignments() {
        jdbc.update("""
            update catalog.products p
            set category_id = coalesce(p.category_id, c.id), brand_id = coalesce(p.brand_id, b.id),
                category = case when p.category_id is null then c.name else p.category end,
                description = coalesce(p.description, p.name),
                name = coalesce((select name from catalog.brands where id = p.brand_id), b.name) || ' ' || coalesce(p.description, p.name)
            from generate_series(1, ?) as seed(index)
            join (values (0, ?), (1, ?), (2, ?)) as categories(index, name)
                on categories.index = (seed.index - 1) % 3
            join catalog.categories c on lower(c.name) = lower(categories.name)
            join (values (0, ?), (1, ?), (2, ?), (3, ?), (4, ?), (5, ?)) as brands(index, name)
                on brands.index = (seed.index - 1) % 6
            join catalog.brands b on lower(b.name) = lower(brands.name)
            where coalesce(p.description, p.name) = 'Producto Demo ' || lpad(seed.index::text, 3, '0')
              and (p.category_id is null or p.brand_id is null)
              and exists (select 1 from inventory.stock_movements m
                          where m.product_id = p.id and m.reference_type = 'DEMO_SEED')
            """, PRODUCT_COUNT, CATEGORY_NAMES[0], CATEGORY_NAMES[1], CATEGORY_NAMES[2],
            BRAND_NAMES[0], BRAND_NAMES[1], BRAND_NAMES[2], BRAND_NAMES[3], BRAND_NAMES[4], BRAND_NAMES[5]);
        jdbc.update("""
            update customer.customers c set zone = z.name
            from generate_series(1, ?) as seed(index)
            join customer.zones z on z.name = 'Zona Demo ' || lpad((((seed.index - 1) % ?) + 1)::text, 2, '0')
            where c.business_name = 'Cliente Demo ' || lpad(seed.index::text, 3, '0')
              and c.tax_id = '30-7' || lpad(seed.index::text, 8, '0') || '-' || (seed.index % 10)::text
              and (c.zone is null or btrim(c.zone) = '')
            """, CUSTOMER_COUNT, ZONE_COUNT);
    }

    private String zoneName(int index) {
        return "Zona Demo %02d".formatted(index);
    }

    private void ensureDemoPurchasing(LocalDate today, boolean refreshDates) {
        for (int index = 1; index <= SUPPLIER_COUNT; index++) {
            UUID supplierId = demoSupplierId(index);
            jdbc.update("""
                insert into supplier.suppliers(id, name, phone, email, address, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?) on conflict (id) do nothing
                """, supplierId, "Proveedor Demo %02d".formatted(index), "+54 11 4000-%04d".formatted(index),
                "proveedor%02d@distribuidora.local".formatted(index), "Dirección Demo %03d".formatted(index),
                timestamp(clock.instant()), timestamp(clock.instant()));
            // Keep a fifth of demo products without suppliers, and give some two suppliers.
            jdbc.update("""
                insert into catalog.product_suppliers(product_id, supplier_id)
                select p.id, ? from generate_series(1, ?) as seed(index)
                join catalog.products p on p.description = 'Producto Demo ' || lpad(seed.index::text, 3, '0')
                where seed.index % 5 <> 0
                  and ((seed.index - 1) % ? + 1 = ?
                    or (seed.index % 3 = 0 and seed.index % ? + 1 = ?))
                  and exists (select 1 from inventory.stock_movements m
                              where m.product_id = p.id and m.reference_type = 'DEMO_SEED')
                on conflict do nothing
                """, supplierId, PRODUCT_COUNT, SUPPLIER_COUNT, index, SUPPLIER_COUNT, index);
        }
        for (int index = 1; index <= SUPPLIER_ORDER_COUNT; index++) {
            UUID supplierId = demoSupplierId((index - 1) % SUPPLIER_COUNT + 1);
            String key = PURCHASING_SEED_PREFIX + "%03d".formatted(index);
            LocalDate date = supplierOrderDate(today, index);
            var existing = jdbc.queryForList("select id, supplier_id from purchasing.supplier_orders where idempotency_key = ?", key);
            if (!existing.isEmpty()) {
                if (refreshDates) {
                    UUID orderId = (UUID) existing.getFirst().get("id");
                    var lines = jdbc.queryForList("select product_id, quantity, unit_cost from purchasing.supplier_order_items where order_id = ?", orderId);
                    jdbc.update("""
                        update purchasing.supplier_orders set order_date = ?, created_at = ?, request_fingerprint = ?
                        where id = ?
                        """, date, timestamp(date.atTime(LocalTime.NOON).atZone(ZONE).toInstant()),
                        purchasingFingerprint((UUID) existing.getFirst().get("supplier_id"), date, lines), orderId);
                }
                continue;
            }
            var products = jdbc.queryForList("""
                select p.id, p.name, p.cost from catalog.products p
                join catalog.product_suppliers ps on ps.product_id = p.id
                where ps.supplier_id = ? and p.status = 'ACTIVE'
                  and exists (select 1 from inventory.stock_movements m
                              where m.product_id = p.id and m.reference_type = 'DEMO_SEED')
                order by p.description, p.id
                """, supplierId);
            if (products.isEmpty()) continue;
            var lines = new ArrayList<Map<String, Object>>();
            BigDecimal total = BigDecimal.ZERO.setScale(4);
            for (int line = 0; line < Math.min(2 + index % 4, products.size()); line++) {
                var product = products.get((index + line) % products.size());
                BigDecimal quantity = BigDecimal.valueOf(5 + (index + line) % 20);
                BigDecimal cost = ((BigDecimal) product.get("cost"))
                    .multiply(BigDecimal.valueOf(90 + index % 6, 2)).setScale(4, RoundingMode.HALF_UP);
                BigDecimal lineTotal = quantity.multiply(cost).setScale(4, RoundingMode.HALF_UP);
                lines.add(Map.of("product_id", product.get("id"), "name", product.get("name"),
                    "quantity", quantity, "unit_cost", cost, "line_total", lineTotal));
                total = total.add(lineTotal);
            }
            UUID orderId = UUID.randomUUID();
            var inserted = jdbc.queryForList("""
                insert into purchasing.supplier_orders(id, order_number, supplier_id, supplier_name, order_date,
                    total, idempotency_key, request_fingerprint, created_by, created_at)
                values (?, 'PRV-' || lpad(nextval('purchasing.supplier_order_number_seq')::text, 8, '0'), ?,
                    (select name from supplier.suppliers where id = ?), ?, ?, ?, ?,
                    (select id from identity.users where email = 'admin1@distribuidora.local'), ?)
                on conflict (idempotency_key) do nothing returning id
                """, orderId, supplierId, supplierId, date, total, key, purchasingFingerprint(supplierId, date, lines),
                timestamp(date.atTime(LocalTime.NOON).atZone(ZONE).toInstant()));
            if (inserted.isEmpty()) continue;
            for (var line : lines) {
                jdbc.update("""
                    insert into purchasing.supplier_order_items(id, order_id, product_id, product_name, quantity, unit_cost, line_total)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), orderId, line.get("product_id"), line.get("name"), line.get("quantity"),
                    line.get("unit_cost"), line.get("line_total"));
            }
        }
        log.info("Updated demo purchasing: {} suppliers, up to {} supplier orders between {} and {}",
            SUPPLIER_COUNT, SUPPLIER_ORDER_COUNT, today.minusDays(29), today);
    }

    private UUID demoSupplierId(int index) {
        return UUID.nameUUIDFromBytes(("demo-supplier-v1:" + index).getBytes(StandardCharsets.UTF_8));
    }

    private LocalDate supplierOrderDate(LocalDate today, int index) {
        // Every supplier has ten orders across the month, including one today.
        int batch = (index - 1) / SUPPLIER_COUNT;
        return today.minusDays(29 - batch * 29 / (SUPPLIER_ORDER_COUNT / SUPPLIER_COUNT - 1));
    }

    private String purchasingFingerprint(UUID supplierId, LocalDate date, List<Map<String, Object>> lines) {
        String values = lines.stream().sorted(Comparator.comparing(line -> line.get("product_id").toString()))
            .map(line -> "|" + line.get("product_id") + ":"
                + ((BigDecimal) line.get("quantity")).stripTrailingZeros().toPlainString() + ":"
                + ((BigDecimal) line.get("unit_cost")).stripTrailingZeros().toPlainString())
            .reduce("", String::concat);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((supplierId + "|" + date + values).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void ensureProductPrices() {
        jdbc.update("""
            INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
            SELECT price_lists.id, products.id, round((products.cost * 1.35)::numeric, 4), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM catalog.price_lists
            CROSS JOIN catalog.products
            WHERE price_lists.status = 'ACTIVE'
            ON CONFLICT (price_list_id, product_id) DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO catalog.product_price_history
                (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
            SELECT gen_random_uuid(), pp.price_list_id, pp.product_id, pp.price,
                   (CURRENT_TIMESTAMP AT TIME ZONE 'America/Argentina/Buenos_Aires')::date,
                   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            FROM catalog.product_prices pp
            WHERE NOT EXISTS (
                SELECT 1 FROM catalog.product_price_history h
                WHERE h.price_list_id = pp.price_list_id AND h.product_id = pp.product_id
            )
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
        List<UUID> products = new ArrayList<>();
        for (int index = 1; index <= PRODUCT_COUNT; index++) {
            UUID id = UUID.randomUUID();
            products.add(id);
            BigDecimal cost = BigDecimal.valueOf(500 + (index % 100) * 37L);
            String category = CATEGORY_NAMES[(index - 1) % CATEGORY_NAMES.length];
            String brand = BRAND_NAMES[(index - 1) % BRAND_NAMES.length];
            jdbc.update("""
                insert into catalog.products(id, name, description, category, presentation, cost, status, created_at, category_id, brand_id)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?,
                    (select id from catalog.categories where lower(name) = lower(?)),
                    (select id from catalog.brands where lower(name) = lower(?)))
                """, id, brand + " " + "Producto Demo %03d".formatted(index), "Producto Demo %03d".formatted(index),
                category, "Unidad", cost, timestamp(Instant.now()), category, brand);
            BigDecimal stock = BigDecimal.valueOf(20 + (index % 80));
            jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, ?, ?)",
                id, stock, timestamp(Instant.now()));
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
                insert into customer.customers(id, business_name, tax_id, seller_id, zone, balance, status, created_at)
                values (?, ?, ?, ?, ?, 0, 'ACTIVE', ?)
                """, id, "Cliente Demo %03d".formatted(index), "30-7%08d-%d".formatted(index, index % 10),
                sellerProfiles.get(index % sellerProfiles.size()), zoneName((index - 1) % ZONE_COUNT + 1), timestamp(Instant.now()));
        }
        return customers;
    }

    private void insertSales(List<UUID> products, List<UUID> customers, List<UUID> sellerProfiles, LocalDate today) {
        Random random = new Random(20260918L);
        UUID generalPriceListId = jdbc.queryForObject(
            "select id from catalog.price_lists where code = 'GENERAL' and is_default = true", UUID.class);
        for (int index = 1; index <= SALE_COUNT; index++) {
            UUID orderId = UUID.randomUUID();
            UUID saleId = UUID.randomUUID();
            UUID customerId = customers.get(index % customers.size());
            Instant createdAt = saleDate(today, index);
            BigDecimal total = BigDecimal.ZERO;
            List<Line> lines = new ArrayList<>();
            int lineCount = 2 + random.nextInt(4);
            for (int line = 0; line < lineCount; line++) {
                UUID productId = products.get(random.nextInt(products.size()));
                Map<String, Object> product = jdbc.queryForMap("""
                    select p.name, current_price.price
                    from catalog.products p
                    join lateral (
                        select h.price
                        from catalog.product_price_history h
                        where h.product_id = p.id and h.price_list_id = ?
                          and h.effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
                        order by h.effective_on desc, h.created_at desc, h.id desc
                        limit 1
                    ) current_price on true
                    where p.id = ?
                    """, generalPriceListId, productId);
                BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(8));
                BigDecimal price = (BigDecimal) product.get("price");
                BigDecimal lineTotal = price.multiply(quantity).setScale(4);
                total = total.add(lineTotal);
                lines.add(new Line(productId, (String) product.get("name"), quantity, price, lineTotal));
            }
            String status = index % 10 == 0 ? "DELIVERED" : "CONFIRMED";
            String orderNumber = jdbc.queryForObject("select orders.next_customer_order_number(?)", String.class, customerId);
            jdbc.update("insert into orders.orders(id, order_number, customer_id, seller_id, status, subtotal, discount, total, created_at, legacy_order_number) values (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)",
                orderId, orderNumber, customerId, sellerProfiles.get(index % sellerProfiles.size()), status, total, total, timestamp(createdAt), "PED-%05d".formatted(index));
            jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                saleId, "V-%06d".formatted(index), orderId, customerId, status, total, total.multiply(BigDecimal.valueOf(index % 3 == 0 ? 0.5 : 1)), timestamp(createdAt));
            for (Line line : lines) {
                jdbc.update("insert into orders.order_items(id, order_id, product_id, product_name, quantity, unit_price, line_total, price_list_id, price_list_code, line_discount_percent) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), orderId, line.productId(), line.name(), line.quantity(), line.price(), line.total(), generalPriceListId, "GENERAL", BigDecimal.ZERO.setScale(4));
                jdbc.update("insert into sale.sale_items(id, sale_id, product_id, product_name, quantity, unit_price, line_total, price_list_id, price_list_code, line_discount_percent) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), saleId, line.productId(), line.name(), line.quantity(), line.price(), line.total(), generalPriceListId, "GENERAL", BigDecimal.ZERO.setScale(4));
                jdbc.update("update inventory.inventory_balances set quantity = quantity - ?, updated_at = ? where product_id = ?",
                    line.quantity(), timestamp(Instant.now()), line.productId());
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

    private Instant saleDate(LocalDate today, int index) {
        int dayOffset = (index - 1) % (DAYS_BEFORE + DAYS_AFTER + 1) - DAYS_BEFORE;
        return today.plusDays(dayOffset).atTime(LocalTime.NOON).atZone(ZONE).toInstant();
    }

    private void refreshDemoDates(LocalDate today) {
        List<SeedDates> dates = jdbc.query("""
            SELECT o.id AS order_id, s.id AS sale_id, seed.index, s.created_at
            FROM generate_series(1, 1000) AS seed(index)
            JOIN orders.orders o ON o.legacy_order_number = 'PED-' || lpad(seed.index::text, 5, '0')
            JOIN sale.sales s ON s.order_id = o.id
                AND s.sale_number = 'V-' || lpad(seed.index::text, 6, '0')
            """, (rs, rowNum) -> new SeedDates(rs.getObject("order_id", UUID.class),
                rs.getObject("sale_id", UUID.class), rs.getInt("index"), rs.getTimestamp("created_at")));
        for (SeedDates entry : dates) {
            Timestamp date = timestamp(saleDate(today, entry.index()));
            jdbc.update("update orders.orders set created_at = ? where id = ?", date, entry.orderId());
            jdbc.update("update sale.sales set created_at = ? where id = ?", date, entry.saleId());
            // Only move the original seed entries; keep subsequent business activity unchanged.
            jdbc.update("update payment.payments set created_at = ? where sale_id = ? and created_at = ?",
                date, entry.saleId(), entry.createdAt());
            jdbc.update("update customer.account_ledger set created_at = ? where sale_id = ? and entry_type = 'DEBIT' and created_at = ?",
                date, entry.saleId(), entry.createdAt());
            jdbc.update("""
                update inventory.stock_movements set created_at = ?
                where reference_id = ? and reference_type = 'SALE' and movement_type = 'SALE' and created_at = ?
                """, date, entry.saleId(), entry.createdAt());
        }
        log.info("Refreshed {} demo orders and sales between {} and {}", dates.size(),
            today.minusDays(DAYS_BEFORE), today.plusDays(DAYS_AFTER));
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
    record SeedDates(UUID orderId, UUID saleId, int index, Timestamp createdAt) { }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
