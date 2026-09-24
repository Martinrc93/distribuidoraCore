package com.distribuidora.catalog.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductCommandService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ProductCommandService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ProductPriceInput(UUID priceListId, BigDecimal price) { }

    public record ProductInput(
        String sku,
        String name,
        String category,
        String presentation,
        BigDecimal cost,
        List<ProductPriceInput> prices,
        UUID categoryId,
        UUID brandId
    ) {
        public ProductInput(String sku, String name, String category, String presentation,
                            BigDecimal cost, List<ProductPriceInput> prices) {
            this(sku, name, category, presentation, cost, prices, null, null);
        }
    }

    public record ActiveListPrice(UUID priceListId, String code, BigDecimal price) { }

    public static void validate(ProductInput input) {
        if (input == null || blank(input.sku()) || blank(input.name()) || blank(input.category()) || blank(input.presentation())) {
            throw new IllegalArgumentException("sku, name, category y presentation son obligatorios");
        }
        if (input.cost() == null || input.cost().signum() < 0) {
            throw new IllegalArgumentException("cost no puede ser negativo");
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
        String categoryName = resolveActiveName("catalog.categories", input.categoryId(), input.category());
        validateActive("catalog.brands", input.brandId());
        if (exists("select exists(select 1 from catalog.products where sku = ?)", input.sku())) {
            throw new IllegalStateException("Ya existe un producto con ese SKU");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into catalog.products(id, sku, name, category, presentation, cost, status, created_at, category_id, brand_id)
            values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
            """, id, input.sku().trim(), input.name().trim(), categoryName.trim(), input.presentation().trim(),
            input.cost(), timestamp(), input.categoryId(), input.brandId());
        jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, 0, ?)", id, timestamp());

        if (input.prices() != null) {
            for (ProductPriceInput priceInput : input.prices()) {
                storeCurrentPrice(priceInput.priceListId(), id, priceInput.price());
            }
        }

        audit.record(actorId(), "PRODUCT_CREATE", "PRODUCT", id.toString(), "SUCCESS", Map.of("sku", input.sku()));
        return id;
    }

    @Transactional
    public void update(UUID id, ProductInput input) {
        validate(input);
        String categoryName = resolveActiveName("catalog.categories", input.categoryId(), input.category());
        validateActive("catalog.brands", input.brandId());
        if (!exists("select exists(select 1 from catalog.products where id = ?)", id)) throw new EmptyResultDataAccessException(1);
        jdbc.queryForObject("select id from catalog.products where id = ? for update", UUID.class, id);
        if (exists("select exists(select 1 from catalog.products where sku = ? and id <> ?)", input.sku(), id)) {
            throw new IllegalStateException("Ya existe un producto con ese SKU");
        }

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

        List<ProductPriceValidationException.AffectedPriceList> affected = activePrices.stream()
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

        if (input.categoryId() == null && input.brandId() == null) {
            jdbc.update("update catalog.products set sku = ?, name = ?, category = ?, presentation = ?, cost = ? where id = ?",
                input.sku().trim(), input.name().trim(), categoryName.trim(), input.presentation().trim(), input.cost(), id);
        } else {
            jdbc.update("update catalog.products set sku = ?, name = ?, category = ?, presentation = ?, cost = ?, category_id = coalesce(?, category_id), brand_id = coalesce(?, brand_id) where id = ?",
                input.sku().trim(), input.name().trim(), categoryName.trim(), input.presentation().trim(),
                input.cost(), input.categoryId(), input.brandId(), id);
        }

        if (input.prices() != null) {
            for (ProductPriceInput priceInput : input.prices()) {
                storeCurrentPrice(priceInput.priceListId(), id, priceInput.price());
            }
        }

        audit.record(actorId(), "PRODUCT_UPDATE", "PRODUCT", id.toString(), "SUCCESS", Map.of());
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        if (jdbc.update("update catalog.products set status = ? where id = ?", status, id) == 0) throw new EmptyResultDataAccessException(1);
        audit.record(actorId(), "PRODUCT_STATUS", "PRODUCT", id.toString(), "SUCCESS", Map.of("status", status));
    }

    private boolean exists(String sql, Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args)); }
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
    private void validateActive(String table, UUID entityId) {
        if (entityId != null && !exists("select exists(select 1 from " + table + " where id = ? and status = 'ACTIVE')", entityId)) {
            throw new IllegalArgumentException("La marca seleccionada no existe o está inactiva");
        }
    }
    private UUID actorId() { try { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); } catch (Exception ignored) { return null; } }
    private Timestamp timestamp() { return Timestamp.from(Instant.now()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
