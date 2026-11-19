package com.distribuidora.catalog.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.pricing.application.PriceListFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductCommandService {
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PriceListFollowService follows;
    private final InventoryMovementService inventory;

    public ProductCommandService(JdbcTemplate jdbc, AuditService audit) {
        this(jdbc, audit, new PriceListFollowService(jdbc));
    }

    public ProductCommandService(JdbcTemplate jdbc, AuditService audit, PriceListFollowService follows) {
        this(jdbc, audit, follows, new InventoryMovementService(jdbc));
    }

    @Autowired
    public ProductCommandService(JdbcTemplate jdbc, AuditService audit, PriceListFollowService follows,
                                 InventoryMovementService inventory) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.follows = follows;
        this.inventory = inventory;
    }

    public record ProductPriceInput(UUID priceListId, BigDecimal price) { }

    public record ProductInput(
        String description,
        String category,
        String presentation,
        BigDecimal cost,
        List<ProductPriceInput> prices,
        UUID categoryId,
        UUID brandId,
        List<UUID> supplierIds,
        BigDecimal initialStock
    ) {
        public ProductInput(String description, String category, String presentation, BigDecimal cost,
                            List<ProductPriceInput> prices, UUID categoryId, UUID brandId, List<UUID> supplierIds) {
            this(description, category, presentation, cost, prices, categoryId, brandId, supplierIds, null);
        }

        public ProductInput(String description, String category, String presentation, BigDecimal cost,
                            List<ProductPriceInput> prices, UUID categoryId, UUID brandId) {
            this(description, category, presentation, cost, prices, categoryId, brandId, null);
        }
    }

    public record ActiveListPrice(UUID priceListId, String code, BigDecimal price) { }

    public static void validate(ProductInput input) {
        if (input == null || blank(input.description()) || input.categoryId() == null || input.brandId() == null) {
            throw new IllegalArgumentException("La descripción, la categoría y la marca son obligatorias");
        }
        if (input.description().trim().length() > 200) {
            throw new IllegalArgumentException("La descripción no puede superar los 200 caracteres");
        }
        if (input.cost() == null || input.cost().signum() < 0) {
            throw new IllegalArgumentException("cost no puede ser negativo");
        }
        if (input.supplierIds() != null && (input.supplierIds().stream().anyMatch(java.util.Objects::isNull)
            || new HashSet<>(input.supplierIds()).size() != input.supplierIds().size())) {
            throw new IllegalArgumentException("La lista de proveedores contiene identificadores inválidos o repetidos");
        }
        if (input.prices() != null) {
            Set<UUID> seenLists = new HashSet<>();
            for (ProductPriceInput priceInput : input.prices()) {
                if (priceInput.priceListId() == null) {
                    throw new IllegalArgumentException("priceListId es obligatorio");
                }
                if (!seenLists.add(priceInput.priceListId())) {
                    throw new IllegalArgumentException("No se pueden repetir listas de precios en el payload");
                }
                if (priceInput.price() == null || priceInput.price().signum() < 0) {
                    throw new IllegalArgumentException("El precio no puede ser negativo");
                }
                if (priceInput.price().compareTo(input.cost()) < 0) {
                    throw new IllegalArgumentException("El precio de la lista no puede ser menor al costo");
                }
            }
        }
    }

    @Transactional
    public UUID create(ProductInput input) {
        validate(input);
        BigDecimal initialStock = input.initialStock() == null ? BigDecimal.ZERO : input.initialStock();
        if (initialStock.signum() < 0 || initialStock.remainder(HALF).signum() != 0) {
            throw new IllegalArgumentException("El stock inicial debe ser una cantidad no negativa y múltiplo de 0,5.");
        }
        if (initialStock.signum() > 0) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || authentication.getAuthorities().stream()
                .noneMatch(authority -> "STOCK_ADJUST".equals(authority.getAuthority()))) {
                throw new AccessDeniedException("Se requiere el permiso STOCK_ADJUST para cargar stock inicial.");
            }
        }
        validateSuppliers(input.supplierIds());
        validateCreatePrices(input.prices());
        String categoryName = resolveActiveName("catalog.categories", input.categoryId(), input.category());
        String name = productName(input);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into catalog.products(id, name, description, category, presentation, cost, status, created_at, category_id, brand_id)
            values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            """, id, name, input.description().trim(), categoryName.trim(), nullable(input.presentation()),
            input.cost(), timestamp(), input.categoryId(), input.brandId());
        if (initialStock.signum() == 0) {
            jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, 0, ?)", id, timestamp());
        } else {
            inventory.apply(id, initialStock, "MANUAL_ADJUSTMENT", null, "Stock inicial");
        }

        if (input.prices() != null) {
            for (ProductPriceInput priceInput : input.prices()) {
                storeCurrentPrice(priceInput.priceListId(), id, priceInput.price());
            }
        }
        // Active lists without an explicit price take the default list price and follow it.
        follows.copyDefaultPricesForProduct(id);

        replaceSuppliers(id, input.supplierIds());
        audit.recordWithinTransaction(actorId(), "PRODUCT_CREATE", "PRODUCT", id.toString(), "SUCCESS",
            Map.of("name", name, "initialStock", initialStock));
        return id;
    }

    @Transactional
    public void update(UUID id, ProductInput input) {
        update(id, input, false);
    }

    /**
     * Updates the product. Lists following the default list are not asked for replacement prices: they take
     * the default price. {@code acknowledgeFollowers} records that the user accepted the warning about them.
     */
    @Transactional
    public void update(UUID id, ProductInput input, boolean acknowledgeFollowers) {
        validate(input);
        if (input.initialStock() != null) {
            throw new IllegalArgumentException("El stock inicial solo se puede indicar al crear el producto.");
        }
        validateSuppliers(input.supplierIds());
        String categoryName = resolveActiveName("catalog.categories", input.categoryId(), input.category());
        String name = productName(input);
        if (!exists("select exists(select 1 from catalog.products where id = ?)", id)) throw new EmptyResultDataAccessException(1);
        jdbc.queryForObject("select id from catalog.products where id = ? for update", UUID.class, id);

        List<ActiveListPrice> activePrices = jdbc.query("""
            select pl.id as price_list_id, pl.code, effective.price
            from catalog.price_lists pl
            join lateral (
                select h.price
                from catalog.product_price_history h
                where h.price_list_id = pl.id and h.product_id = ?
                  and h.effective_on <= (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
                order by h.effective_on desc, h.created_at desc, h.id desc
                limit 1
            ) effective on true
            where pl.status = 'ACTIVE'
            """, (rs, rowNum) -> new ActiveListPrice(
                rs.getObject("price_list_id", UUID.class),
                rs.getString("code"),
                rs.getBigDecimal("price")
            ), id);

        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(
                select 1 from catalog.product_price_history h
                join catalog.price_lists pl on pl.id = h.price_list_id and pl.status = 'ACTIVE'
                where h.product_id = ?
                  and h.effective_on > (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
                  and h.price < ?
            )
            """, Boolean.class, id, input.cost()))) {
            throw new IllegalStateException("El nuevo costo supera un precio futuro programado; ajuste o cancele esa vigencia primero");
        }

        Set<UUID> followers = new HashSet<>(follows.followerListIds(id));
        List<ProductPriceValidationException.AffectedPriceList> affected = activePrices.stream()
            .filter(ap -> !followers.contains(ap.priceListId()))
            .filter(ap -> input.cost().compareTo(ap.price()) > 0)
            .map(ap -> new ProductPriceValidationException.AffectedPriceList(ap.priceListId(), ap.code(), ap.price()))
            .toList();

        Map<UUID, BigDecimal> replacements = input.prices() != null
            ? input.prices().stream().collect(Collectors.toMap(ProductPriceInput::priceListId, ProductPriceInput::price, (a, b) -> a))
            : Map.of();

        if (!affected.isEmpty()) {
            List<ProductPriceValidationException.AffectedPriceList> missing = affected.stream()
                .filter(a -> !replacements.containsKey(a.priceListId()))
                .toList();
            if (!missing.isEmpty()) {
                throw new ProductPriceValidationException("El nuevo costo supera los precios de listas activas", missing);
            }
            for (ProductPriceValidationException.AffectedPriceList a : affected) {
                BigDecimal newPrice = replacements.get(a.priceListId());
                if (newPrice.compareTo(input.cost()) < 0) {
                    throw new IllegalArgumentException("El precio de la lista " + a.code() + " no puede ser menor al nuevo costo");
                }
            }
        }

        jdbc.update("update catalog.products set name = ?, description = ?, category = ?, presentation = ?, cost = ?, category_id = ?, brand_id = ? where id = ?",
            name, input.description().trim(), categoryName.trim(), nullable(input.presentation()),
            input.cost(), input.categoryId(), input.brandId(), id);

        UUID defaultListId = followers.isEmpty() && (input.prices() == null || input.prices().isEmpty()) ? null : follows.defaultListId();
        if (input.prices() != null) {
            for (ProductPriceInput priceInput : input.prices()) {
                storeCurrentPrice(priceInput.priceListId(), id, priceInput.price());
                if (priceInput.priceListId().equals(defaultListId)) {
                    if (acknowledgeFollowers) follows.acknowledgeWarnings(id, timestamp());
                    LocalDate today = jdbc.queryForObject(
                        "select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class);
                    follows.propagateFromDefault(id, priceInput.price(), today, today, timestamp());
                } else if (followers.contains(priceInput.priceListId())) {
                    follows.stopFollowing(priceInput.priceListId(), id);
                }
            }
        }

        replaceSuppliers(id, input.supplierIds());
        audit.record(actorId(), "PRODUCT_UPDATE", "PRODUCT", id.toString(), "SUCCESS", Map.of());
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        if (jdbc.update("update catalog.products set status = ? where id = ?", status, id) == 0) throw new EmptyResultDataAccessException(1);
        // A reactivated product gets the default price in lists created while it was inactive.
        if ("ACTIVE".equals(status)) follows.copyDefaultPricesForProduct(id);
        audit.record(actorId(), "PRODUCT_STATUS", "PRODUCT", id.toString(), "SUCCESS", Map.of("status", status));
    }

    private boolean exists(String sql, Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args)); }
    private void validateSuppliers(List<UUID> supplierIds) {
        if (supplierIds == null) return;
        for (UUID supplierId : supplierIds) {
            if (!exists("select exists(select 1 from supplier.suppliers where id = ?)", supplierId)) {
                throw new IllegalArgumentException("Uno de los proveedores seleccionados no existe");
            }
        }
    }

    private void replaceSuppliers(UUID productId, List<UUID> supplierIds) {
        if (supplierIds == null) return;
        jdbc.update("delete from catalog.product_suppliers where product_id = ?", productId);
        for (UUID supplierId : supplierIds) jdbc.update(
            "insert into catalog.product_suppliers(product_id, supplier_id) values (?, ?)", productId, supplierId);
    }
    private void validateCreatePrices(List<ProductPriceInput> prices) {
        if (prices == null || prices.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos un precio para una lista activa");
        }
        for (ProductPriceInput price : prices) {
            if (!exists("select exists(select 1 from catalog.price_lists where id = ? and status = 'ACTIVE')", price.priceListId())) {
                throw new IllegalArgumentException("Los precios iniciales solo pueden pertenecer a listas activas");
            }
        }
    }

    private void storeCurrentPrice(UUID listId, UUID productId, BigDecimal price) {
        Timestamp now = timestamp();
        jdbc.update("""
            insert into catalog.product_prices(price_list_id, product_id, price, created_at, updated_at)
            values (?, ?, ?, ?, ?)
            on conflict (price_list_id, product_id)
            do update set price = excluded.price, updated_at = excluded.updated_at
            """, listId, productId, price, now, now);
        jdbc.update("""
            insert into catalog.product_price_history
                (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
            values (?, ?, ?, ?, (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date, ?, ?)
            """, UUID.randomUUID(), listId, productId, price, now, now);
    }
    private String resolveActiveName(String table, UUID entityId, String fallback) {
        if (entityId == null) return fallback;
        List<String> names = jdbc.query("select name from " + table + " where id = ? and status = 'ACTIVE'",
            (rs, row) -> rs.getString(1), entityId);
        if (names.isEmpty()) throw new IllegalArgumentException("La categoría seleccionada no existe o está inactiva");
        return names.getFirst();
    }
    private String productName(ProductInput input) {
        List<String> names = jdbc.query("select name from catalog.brands where id = ? and status = 'ACTIVE'",
            (rs, row) -> rs.getString(1), input.brandId());
        if (names.isEmpty()) throw new IllegalArgumentException("La marca seleccionada no existe o está inactiva");
        return names.getFirst().trim() + " " + input.description().trim();
    }
    private UUID actorId() { try { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); } catch (Exception ignored) { return null; } }
    private Timestamp timestamp() { return Timestamp.from(Instant.now()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullable(String value) { return blank(value) ? null : value.trim(); }
}
