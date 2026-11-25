package com.distribuidora.pricing.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.distribuidora.shared.security.CurrentUserAccess;

@Service
public class PricingCommandService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final PriceListFollowService follows;

    public PricingCommandService(JdbcTemplate jdbc, AuditService audit) {
        this(jdbc, audit, new PriceListFollowService(jdbc));
    }

    @Autowired
    public PricingCommandService(JdbcTemplate jdbc, AuditService audit, PriceListFollowService follows) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.follows = follows;
    }

    @Transactional
    public UUID createList(String code, String name) {
        String normalizedName = validateText(name, "name", 120);
        UUID id = UUID.randomUUID();
        Timestamp now = timestamp();
        String normalizedCode;
        if (code == null) {
            normalizedCode = insertWithGeneratedCode(id, normalizedName, now);
        } else {
            normalizedCode = validateText(code, "code", 40);
            if (!insertList(id, normalizedCode, normalizedName, now)) {
                throw new IllegalStateException("Ya existe una lista con ese código");
            }
        }
        // Existing products are linked with the default list prices and stay marked until reviewed.
        int copied = follows.copyDefaultPricesIntoList(id);
        Map<String, Object> details = new HashMap<>(Map.of("code", normalizedCode));
        if (copied > 0) details.put("copiedFromDefault", copied);
        audit.recordWithinTransaction(CurrentUserAccess.resolveActorIdOrSystem(), "PRICELIST_CREATE", "PRICE_LIST", id.toString(), "SUCCESS", details);
        return id;
    }

    private String insertWithGeneratedCode(UUID id, String name, Timestamp now) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "LISTA";
        if (base.equals("GENERAL")) base = "LISTA_GENERAL";
        // Use the unique constraint to claim a code atomically, including concurrent creations.
        for (int number = 1; ; number++) {
            String suffix = number == 1 ? "" : "_" + number;
            String code = base.substring(0, Math.min(base.length(), 40 - suffix.length())) + suffix;
            if (insertList(id, code, name, now)) return code;
        }
    }

    private boolean insertList(UUID id, String code, String name, Timestamp now) {
        return jdbc.update("""
            insert into catalog.price_lists(id, code, name, status, is_default, created_at, updated_at)
            values (?, ?, ?, 'ACTIVE', false, ?, ?) on conflict (code) do nothing
            """, id, code, name, now, now) == 1;
    }

    /** What creating a list would copy, so the UI can ask for confirmation first. */
    public Map<String, Object> creationPreview() {
        UUID defaultId = follows.defaultListId();
        Map<String, Object> preview = new HashMap<>();
        preview.put("productsToCopy", follows.activeProductsWithDefaultPrice());
        preview.put("defaultListId", defaultId);
        preview.put("defaultListName", defaultId == null ? null
            : jdbc.queryForObject("select name from catalog.price_lists where id = ?", String.class, defaultId));
        return preview;
    }

    /** Marks copied prices as reviewed; they keep following the default list. */
    @Transactional
    public int confirmFollowingPrices(UUID listId, List<UUID> productIds) {
        requireId(listId, "listId");
        if (productIds != null && productIds.size() > 1000) {
            throw new IllegalArgumentException("Se pueden confirmar hasta 1000 productos por vez");
        }
        jdbc.queryForObject("select id from catalog.price_lists where id = ? for share", UUID.class, listId);
        int confirmed = follows.confirm(listId, productIds, CurrentUserAccess.resolveActorIdOrSystem(), timestamp());
        audit.recordWithinTransaction(CurrentUserAccess.resolveActorIdOrSystem(), "PRICELIST_REVIEW_CONFIRM", "PRICE_LIST", listId.toString(), "SUCCESS",
            Map.of("confirmed", confirmed, "scope", productIds == null || productIds.isEmpty() ? "ALL_PENDING" : "SELECTED"));
        return confirmed;
    }

    /** Lists that still warn before the default price of the product changes. */
    public List<PriceListFollowService.Follower> unacknowledgedFollowers(UUID productId) {
        requireId(productId, "productId");
        return follows.unacknowledgedFollowers(productId);
    }

    @Transactional
    public void renameList(UUID listId, String name) {
        requireId(listId, "listId");
        String normalizedName = validateText(name, "name", 120);
        if (jdbc.update("update catalog.price_lists set name = ?, updated_at = ? where id = ?",
            normalizedName, timestamp(), listId) == 0) {
            throw new EmptyResultDataAccessException(1);
        }
        audit.recordWithinTransaction(CurrentUserAccess.resolveActorIdOrSystem(), "PRICELIST_UPDATE", "PRICE_LIST", listId.toString(), "SUCCESS",
            Map.of("name", normalizedName));
    }

    @Transactional
    public void setListStatus(UUID listId, String status) {
        requireId(listId, "listId");
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }
        jdbc.queryForObject("select status from catalog.price_lists where id = ?", String.class, listId);
        if ("INACTIVE".equals(status)
            && Boolean.TRUE.equals(jdbc.queryForObject(
                "select is_default from catalog.price_lists where id = ?", Boolean.class, listId))) {
            throw new IllegalStateException("La lista default no puede desactivarse");
        }
        jdbc.update("update catalog.price_lists set status = ?, updated_at = ? where id = ?",
            status, timestamp(), listId);
        // An activated list gets the default prices for products it has no price for, like a new list.
        int copied = "ACTIVE".equals(status) ? follows.copyDefaultPricesIntoList(listId) : 0;
        Map<String, Object> details = new HashMap<>(Map.of("status", status));
        if (copied > 0) details.put("copiedFromDefault", copied);
        audit.recordWithinTransaction(CurrentUserAccess.resolveActorIdOrSystem(), "PRICELIST_STATUS", "PRICE_LIST", listId.toString(), "SUCCESS", details);
    }

    @Transactional
    public void setProductPrice(UUID listId, UUID productId, BigDecimal price) {
        setProductPrice(listId, productId, price, null);
    }

    @Transactional
    public void setProductPrice(UUID listId, UUID productId, BigDecimal price, LocalDate requestedEffectiveOn) {
        setProductPrice(listId, productId, price, requestedEffectiveOn, false);
    }

    /**
     * Sets a price on a list. On the default list the change is mirrored into the lists that follow it;
     * {@code acknowledgeFollowers} records that the user accepted the warning, so it is not shown again for
     * those product/list pairs. On any other list the price becomes its own and stops following.
     */
    @Transactional
    public void setProductPrice(UUID listId, UUID productId, BigDecimal price, LocalDate requestedEffectiveOn,
                                boolean acknowledgeFollowers) {
        requireId(listId, "listId");
        requireId(productId, "productId");
        validatePrice(price);
        jdbc.queryForObject("select id from catalog.products where id = ? for update", UUID.class, productId);
        String listStatus = jdbc.queryForObject(
            "select status from catalog.price_lists where id = ? for share", String.class, listId);
        if (!"ACTIVE".equals(listStatus)) {
            throw new IllegalStateException("La lista de precios no está activa");
        }
        String productStatus = jdbc.queryForObject(
            "select status from catalog.products where id = ?", String.class, productId);
        if (!"ACTIVE".equals(productStatus)) {
            throw new IllegalStateException("El producto no está activo");
        }
        BigDecimal cost = jdbc.queryForObject("select cost from catalog.products where id = ?", BigDecimal.class, productId);
        if (cost != null && price.compareTo(cost) < 0) {
            throw new IllegalArgumentException("El precio no puede ser menor al costo del producto");
        }

        LocalDate today = jdbc.queryForObject(
            "select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class);
        LocalDate effectiveOn = requestedEffectiveOn == null ? today : requestedEffectiveOn;
        if (effectiveOn.isBefore(today)) {
            throw new IllegalArgumentException("effectiveOn no puede ser una fecha pasada");
        }
        Timestamp now = timestamp();
        follows.writePrice(listId, productId, price, effectiveOn, today, now);
        Map<String, Object> details = new HashMap<>(
            Map.of("price", price, "effectiveOn", effectiveOn.toString(), "scheduled", effectiveOn.isAfter(today)));
        if (follows.isDefault(listId)) {
            if (acknowledgeFollowers) {
                int acknowledged = follows.acknowledgeWarnings(productId, now);
                if (acknowledged > 0) details.put("acknowledgedFollowers", acknowledged);
            }
            int propagated = follows.propagateFromDefault(productId, price, effectiveOn, today, now);
            if (propagated > 0) details.put("propagatedToFollowers", propagated);
        } else if (follows.stopFollowing(listId, productId)) {
            details.put("stoppedFollowingDefault", true);
        }
        audit.recordWithinTransaction(CurrentUserAccess.resolveActorIdOrSystem(), "PRODUCT_PRICE_UPDATE", "PRODUCT_PRICE", listId + ":" + productId, "SUCCESS", details);
    }

    @Transactional
    public void cancelScheduledPrice(UUID listId, UUID productId, LocalDate effectiveOn) {
        requireId(listId, "listId");
        requireId(productId, "productId");
        if (effectiveOn == null) throw new IllegalArgumentException("effectiveOn es obligatorio");
        LocalDate today = jdbc.queryForObject(
            "select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date", LocalDate.class);
        if (!effectiveOn.isAfter(today)) {
            throw new IllegalArgumentException("Solo se pueden cancelar precios con vigencia futura");
        }
        jdbc.queryForObject("select id from catalog.products where id = ? for update", UUID.class, productId);
        jdbc.queryForObject("select id from catalog.price_lists where id = ? for share", UUID.class, listId);
        int deleted = jdbc.update("""
            delete from catalog.product_price_history
            where price_list_id = ? and product_id = ? and effective_on = ?
              and effective_on > (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date
            """, listId, productId, effectiveOn);
        if (deleted == 0) throw new EmptyResultDataAccessException(1);
        Map<String, Object> details = new HashMap<>(Map.of("effectiveOn", effectiveOn.toString()));
        if (follows.isDefault(listId)) {
            int propagated = follows.propagateCancel(productId, effectiveOn);
            if (propagated > 0) details.put("propagatedToFollowers", propagated);
        } else if (follows.stopFollowing(listId, productId)) {
            details.put("stoppedFollowingDefault", true);
        }
        audit.recordWithinTransaction(CurrentUserAccess.resolveActorIdOrSystem(), "PRODUCT_PRICE_SCHEDULE_CANCEL", "PRODUCT_PRICE",
            listId + ":" + productId, "SUCCESS", details);
    }

    private void validatePrice(BigDecimal price) {
        if (price == null || price.signum() < 0 || price.scale() > 4 || price.precision() > 19
            || price.precision() - price.scale() > 15) {
            throw new IllegalArgumentException("price debe ser un valor no negativo con hasta cuatro decimales");
        }
    }

    private String validateText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + " es obligatorio y no puede superar " + maxLength + " caracteres");
        }
        return value.trim();
    }

    private void requireId(UUID id, String field) {
        if (id == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
    }

    private Timestamp timestamp() {
        return Timestamp.from(Instant.now());
    }
}
