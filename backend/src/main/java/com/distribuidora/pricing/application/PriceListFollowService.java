package com.distribuidora.pricing.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Keeps prices of other lists "following" the default list (GENERAL).
 *
 * <p>A row in {@code catalog.price_list_follows} means the product's price in that list mirrors the
 * default list. Following is implemented on write: every default price version (current or scheduled)
 * is also written to the followers in the same transaction, so price resolution, orders and snapshots
 * keep reading {@code catalog.product_price_history} unchanged. Setting a price of its own on a follower
 * deletes the row. {@code review_status} marks copied prices as PENDING until the user confirms them, and
 * {@code warning_acknowledged} silences the warning shown before a default price change for that pair.</p>
 */
@Service
public class PriceListFollowService {
    static final String TODAY = "(current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date";

    public record Follower(UUID priceListId, String code, String name) { }

    private final JdbcTemplate jdbc;

    public PriceListFollowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID defaultListId() {
        List<UUID> ids = jdbc.queryForList(
            "select id from catalog.price_lists where is_default = true order by id limit 1", UUID.class);
        return ids == null || ids.isEmpty() ? null : ids.getFirst();
    }

    public boolean isDefault(UUID listId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select is_default from catalog.price_lists where id = ?", Boolean.class, listId));
    }

    /** Writes one price version for a list: today's price updates the current row; later dates are scheduled. */
    public void writePrice(UUID listId, UUID productId, BigDecimal price, LocalDate effectiveOn, LocalDate today, Timestamp now) {
        if (effectiveOn.equals(today)) {
            jdbc.update("insert into catalog.product_prices(price_list_id, product_id, price, created_at, updated_at) values (?, ?, ?, ?, ?) on conflict (price_list_id, product_id) do update set price = excluded.price, updated_at = excluded.updated_at",
                listId, productId, price, now, now);
            jdbc.update("""
                insert into catalog.product_price_history
                    (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), listId, productId, price, effectiveOn, now, now);
            return;
        }
        var scheduled = jdbc.queryForList("""
            select id from catalog.product_price_history
            where price_list_id = ? and product_id = ? and effective_on = ?
            order by created_at desc, id desc limit 1 for update
            """, UUID.class, listId, productId, effectiveOn);
        if (scheduled == null || scheduled.isEmpty()) {
            jdbc.update("""
                insert into catalog.product_price_history
                    (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), listId, productId, price, effectiveOn, now, now);
        } else {
            jdbc.update("update catalog.product_price_history set price = ?, updated_at = ? where id = ?",
                price, now, scheduled.getFirst());
        }
    }

    /** Mirrors a default-list price version into every list that follows it for the product. Returns the count. */
    public int propagateFromDefault(UUID productId, BigDecimal price, LocalDate effectiveOn, LocalDate today, Timestamp now) {
        List<UUID> followers = followerListIds(productId);
        for (UUID listId : followers) {
            writePrice(listId, productId, price, effectiveOn, today, now);
        }
        if (!followers.isEmpty()) {
            jdbc.update("update catalog.price_list_follows set updated_at = ? where product_id = ?", now, productId);
        }
        return followers.size();
    }

    /** Removes the same scheduled date from followers when it is cancelled on the default list. */
    public int propagateCancel(UUID productId, LocalDate effectiveOn) {
        return jdbc.update("""
            delete from catalog.product_price_history h
            using catalog.price_list_follows f
            where f.product_id = ? and h.price_list_id = f.price_list_id and h.product_id = f.product_id
              and h.effective_on = ? and h.effective_on > %s
            """.formatted(TODAY), productId, effectiveOn);
    }

    public List<UUID> followerListIds(UUID productId) {
        List<UUID> ids = jdbc.queryForList("""
            select price_list_id from catalog.price_list_follows
            where product_id = ? order by price_list_id for update
            """, UUID.class, productId);
        return ids;
    }

    /** The list gets a price of its own for the product, so it stops following the default list. */
    public boolean stopFollowing(UUID listId, UUID productId) {
        return jdbc.update("delete from catalog.price_list_follows where price_list_id = ? and product_id = ?",
            listId, productId) > 0;
    }

    /** Followers that still show a warning before the default price of the product changes. */
    public List<Follower> unacknowledgedFollowers(UUID productId) {
        List<Follower> followers = jdbc.query("""
            select pl.id, pl.code, pl.name
            from catalog.price_list_follows f
            join catalog.price_lists pl on pl.id = f.price_list_id
            where f.product_id = ? and f.warning_acknowledged = false
            order by pl.code, pl.id
            """, (rs, row) -> new Follower(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name")),
            productId);
        return followers;
    }

    /** Never warn again for these product/list pairs; they keep following the default list. */
    public int acknowledgeWarnings(UUID productId, Timestamp now) {
        return jdbc.update("""
            update catalog.price_list_follows set warning_acknowledged = true, updated_at = ?
            where product_id = ? and warning_acknowledged = false
            """, now, productId);
    }

    /** Marks copied prices as reviewed. Without product ids, confirms every pending price of the list. */
    public int confirm(UUID listId, List<UUID> productIds, UUID actorId, Timestamp now) {
        if (productIds == null || productIds.isEmpty()) {
            return jdbc.update("""
                update catalog.price_list_follows
                set review_status = 'CONFIRMED', reviewed_by = ?, reviewed_at = ?, updated_at = ?
                where price_list_id = ? and review_status = 'PENDING'
                """, actorId, now, now, listId);
        }
        List<Object> params = new ArrayList<>(List.of(actorId, now, now, listId));
        params.addAll(productIds);
        return jdbc.update("""
            update catalog.price_list_follows
            set review_status = 'CONFIRMED', reviewed_by = ?, reviewed_at = ?, updated_at = ?
            where price_list_id = ? and product_id in (%s)
            """.formatted(String.join(", ", Collections.nCopies(productIds.size(), "?"))), params.toArray());
    }

    /**
     * Gives every active product without any price in {@code targetListId} the default list's current and
     * scheduled prices, and marks them as following (PENDING review). Locks the active products first so a
     * concurrent default price change cannot be missed. Returns how many products were copied.
     */
    @Transactional
    public int copyDefaultPricesIntoList(UUID targetListId) {
        UUID defaultId = defaultListId();
        if (defaultId == null || defaultId.equals(targetListId)) return 0;
        jdbc.queryForList("select id from catalog.products where status = 'ACTIVE' order by id for share", UUID.class);
        return copy(defaultId, "t.id = ?", targetListId, "true", null);
    }

    /** Fills the active non-default lists where an active product has no price yet, following the default list. */
    @Transactional
    public int copyDefaultPricesForProduct(UUID productId) {
        UUID defaultId = defaultListId();
        if (defaultId == null) return 0;
        return copy(defaultId, "t.status = 'ACTIVE'", null, "p.id = ?", productId);
    }

    /** Number of active products that would be copied into a new list (for the confirmation dialog). */
    public int activeProductsWithDefaultPrice() {
        UUID defaultId = defaultListId();
        if (defaultId == null) return 0;
        Integer count = jdbc.queryForObject("""
            select count(*) from catalog.products p
            where p.status = 'ACTIVE' and exists (
                select 1 from catalog.product_price_history h
                where h.price_list_id = ? and h.product_id = p.id and h.effective_on <= %s)
            """.formatted(TODAY), Integer.class, defaultId);
        return count == null ? 0 : count;
    }

    private int copy(UUID defaultId, String targetCondition, UUID targetParam, String productCondition, UUID productParam) {
        Timestamp now = Timestamp.from(Instant.now());
        List<Object> params = new ArrayList<>();
        params.add(defaultId);
        if (targetParam != null) params.add(targetParam);
        params.add(defaultId);
        if (productParam != null) params.add(productParam);
        // follows, current_rows, current_history and scheduled_history timestamps, then the default list id.
        params.addAll(List.of(now, now, now, now, now, now, now, now, defaultId));
        Integer copied = jdbc.queryForObject("""
            with targets as (
                select t.id from catalog.price_lists t
                where t.id <> ? and %s
            ), candidates as (
                select t.id as list_id, p.id as product_id, current_price.price
                from targets t
                cross join catalog.products p
                join lateral (
                    select h.price from catalog.product_price_history h
                    where h.price_list_id = ? and h.product_id = p.id and h.effective_on <= %s
                    order by h.effective_on desc, h.created_at desc, h.id desc
                    limit 1
                ) current_price on true
                where p.status = 'ACTIVE' and %s
                  and not exists (
                      select 1 from catalog.product_price_history existing
                      where existing.price_list_id = t.id and existing.product_id = p.id)
            ), follows as (
                insert into catalog.price_list_follows (price_list_id, product_id, review_status, created_at, updated_at)
                select list_id, product_id, 'PENDING', ?, ? from candidates
                on conflict (price_list_id, product_id) do nothing
                returning product_id
            ), current_rows as (
                insert into catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
                select list_id, product_id, price, ?, ? from candidates
                on conflict (price_list_id, product_id)
                do update set price = excluded.price, updated_at = excluded.updated_at
                returning product_id
            ), current_history as (
                insert into catalog.product_price_history
                    (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
                select gen_random_uuid(), list_id, product_id, price, %s, ?, ? from candidates
                returning product_id
            ), scheduled_history as (
                insert into catalog.product_price_history
                    (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
                select gen_random_uuid(), c.list_id, c.product_id, h.price, h.effective_on, ?, ?
                from candidates c
                join catalog.product_price_history h on h.product_id = c.product_id and h.price_list_id = ?
                where h.effective_on > %s
                returning product_id
            )
            select count(*) from follows
            """.formatted(targetCondition, TODAY, productCondition, TODAY, TODAY),
            Integer.class, params.toArray());
        return copied == null ? 0 : copied;
    }
}
