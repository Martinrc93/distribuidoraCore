package com.distribuidora.dashboard.application;

import com.distribuidora.dashboard.application.ReadQueryService.StockFilter;
import com.distribuidora.payment.application.CustomerAccountBalanceQuery;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.security.PermissionAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CustomerCatalogReadQueryService {
    private final ReadQuerySupport support;

    @Autowired
    public CustomerCatalogReadQueryService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.support = new ReadQuerySupport(jdbc, currentUser);
    }

    public PageResponse<Map<String, Object>> customers(int page, int size, String search) {
        return customers(page, size, search, null, false, "");
    }

    public PageResponse<Map<String, Object>> customers(int page, int size, String search,
            UUID sellerId, boolean hasBalance, String status) {
        return customers(page, size, search, sellerId, hasBalance, status, null, null);
    }

    /** {@code kind} filters regular customers or resellers; {@code resellerId}, the customers of a reseller. */
    public PageResponse<Map<String, Object>> customers(int page, int size, String search,
            UUID sellerId, boolean hasBalance, String status, String kind, UUID resellerId) {
        String state = status == null ? "" : status.trim();
        if (!state.isEmpty() && !state.equals("ACTIVE") && !state.equals("INACTIVE")) {
            throw new IllegalArgumentException("El estado del cliente debe ser ACTIVE o INACTIVE");
        }
        String customerKind = kind == null ? "" : kind.trim();
        if (!customerKind.isEmpty() && !customerKind.equals("REGULAR") && !customerKind.equals("RESELLER")) {
            throw new IllegalArgumentException("El tipo de cliente debe ser REGULAR o RESELLER");
        }
        String term = ReadQuerySupport.escapedLike(search);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> filterParams = new ArrayList<>();
        if (term != null) {
            where.append(" and (lower(c.business_name) like ? escape '!' or lower(c.tax_id) like ? escape '!')");
            filterParams.add(term);
            filterParams.add(term);
        }
        if (support.sellerScoped()) {
            where.append(" and c.seller_id = ?");
            filterParams.add(support.currentUser().requireSellerProfile());
        }
        if (sellerId != null) {
            where.append(" and c.seller_id = ?");
            filterParams.add(sellerId);
        }
        if (!state.isEmpty()) {
            where.append(" and c.status = ?");
            filterParams.add(state);
        }
        if (!customerKind.isEmpty()) {
            where.append(" and c.kind = ?");
            filterParams.add(customerKind);
        }
        if (resellerId != null) {
            where.append(" and c.reseller_id = ?");
            filterParams.add(resellerId);
        }
        var balance = new CustomerAccountBalanceQuery(support.jdbc(), support.currentUser()).projection();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        if (!hasBalance) {
            String countSql = "select count(*) from customer.customers c" + where;
            List<Object> rowParams = new ArrayList<>(filterParams);
            rowParams.add(safeSize);
            rowParams.add((long) safePage * safeSize);
            rowParams.addAll(balance.parameters());
            String rowsSql = """
                with paged as (
                    select c.*
                    from customer.customers c
                """ + where + """
                    order by c.business_name, c.id
                    limit ? offset ?
                )
                select c.id, lpad(cast(c.customer_number as varchar), greatest(4, length(cast(c.customer_number as varchar))), '0') as "number", c.business_name as name, c.tax_id as "cuitId", c.email, c.phone, c.address, c.zone, c.seller_id as "sellerId",
                       c.price_list_id as "priceListId", coalesce(sp.display_name, 'Sin asignar') as seller,
                       %s as balance, c.status,
                       c.kind, c.reseller_id as "resellerId", reseller.business_name as reseller
                from paged c
                left join seller.seller_profiles sp on sp.id = c.seller_id
                left join customer.customers reseller on reseller.id = c.reseller_id
                order by c.business_name, c.id
                """.formatted(balance.sql());
            return support.pageUnpaged(rowsSql, rowParams.toArray(), countSql, filterParams.toArray(), safePage, safeSize);
        }
        String filteredCte = "with filtered as (select c.*, " + balance.sql()
            + " as scoped_balance from customer.customers c" + where + ")";
        List<Object> parameters = new ArrayList<>(balance.parameters());
        parameters.addAll(filterParams);
        String source = filteredCte + """
             select c.id, lpad(cast(c.customer_number as varchar), greatest(4, length(cast(c.customer_number as varchar))), '0') as "number", c.business_name as name, c.tax_id as "cuitId", c.email, c.phone, c.address, c.zone, c.seller_id as "sellerId",
                    c.price_list_id as "priceListId", coalesce(sp.display_name, 'Sin asignar') as seller,
                    c.scoped_balance as balance, c.status,
                    c.kind, c.reseller_id as "resellerId", reseller.business_name as reseller
             from filtered c
             left join seller.seller_profiles sp on sp.id = c.seller_id
             left join customer.customers reseller on reseller.id = c.reseller_id
             where c.scoped_balance <> 0
             order by c.business_name, c.id
            """;
        String countSql = filteredCte + " select count(*) from filtered where scoped_balance <> 0";
        return support.page(source, countSql, page, size, parameters.toArray());
    }

    public Map<String, Object> customerFilterOptions() {
        String where = "";
        List<Object> parameters = new ArrayList<>();
        if (support.sellerScoped()) {
            where = " where c.seller_id = ?";
            parameters.add(support.currentUser().requireSellerProfile());
        }
        return Map.of("sellers", support.jdbc().queryForList("""
            select distinct sp.id, sp.display_name as name
            from customer.customers c join seller.seller_profiles sp on sp.id = c.seller_id
            """ + where + " order by sp.display_name, sp.id", parameters.toArray()));
    }

    public PageResponse<Map<String, Object>> products(int page, int size, String search) {
        return products(page, size, search, true);
    }

    public PageResponse<Map<String, Object>> products(int page, int size, String search, boolean includeStock) {
        return products(page, size, search, includeStock, null, null, StockFilter.ALL);
    }

    public PageResponse<Map<String, Object>> products(int page, int size, String search, boolean includeStock,
            UUID brandId, UUID categoryId, StockFilter stock) {
        boolean assigned = support.sellerScoped();
        if (assigned && stock != StockFilter.ALL) {
            throw new AccessDeniedException("El filtro de inventario requiere acceso administrativo");
        }
        boolean showStock = includeStock && (!assigned
            || PermissionAccess.allows("ORDER_CREATE") || PermissionAccess.allows("ORDER_EDIT"));
        String term = ReadQuerySupport.escapedLike(search);
        String costColumn = assigned ? "" : ", p.cost";
        String stockColumn = showStock ? ", coalesce(ib.quantity, 0) as stock" : "";
        String stockJoin = showStock || stock != StockFilter.ALL
            ? " left join inventory.inventory_balances ib on ib.product_id = p.id" : "";
        StringBuilder source = new StringBuilder("""
             from catalog.products p
             left join catalog.categories cat on cat.id = p.category_id
            """).append(stockJoin).append(" where 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (term != null) {
            source.append(" and lower(p.name) like ? escape '!'");
            parameters.add(term);
        }
        if (brandId != null) {
            source.append(" and p.brand_id = ?");
            parameters.add(brandId);
        }
        if (categoryId != null) {
            source.append(" and p.category_id = ?");
            parameters.add(categoryId);
        }
        source.append(switch (stock) {
            case ALL -> "";
            case POSITIVE -> " and coalesce(ib.quantity, 0) > 0";
            case NEGATIVE -> " and coalesce(ib.quantity, 0) < 0";
        });
        PageResponse<Map<String, Object>> result = support.page("""
            select p.id, p.name, coalesce(p.description, p.name) as description, coalesce(cat.name, p.category) as category,
                   p.category_id as "categoryId", p.brand_id as "brandId", p.presentation, p.status
            """ + costColumn + stockColumn + source + " order by p.name, p.id",
            "select count(*) " + source, page, size, parameters.toArray());
        if (assigned || result.content().isEmpty()) return result;
        String ids = String.join(",", Collections.nCopies(result.content().size(), "?"));
        var associations = support.jdbc().queryForList("select ps.product_id, s.id, s.name from catalog.product_suppliers ps "
            + "join supplier.suppliers s on s.id = ps.supplier_id where ps.product_id in (" + ids + ") order by s.name, s.id",
            result.content().stream().map(product -> product.get("id")).toArray());
        var content = result.content().stream().map(product -> {
            Map<String, Object> row = new LinkedCaseInsensitiveMap<>();
            row.putAll(product);
            row.put("suppliers", associations.stream().filter(association -> product.get("id").equals(association.get("product_id")))
                .map(association -> Map.of("id", association.get("id"), "name", association.get("name"))).toList());
            return row;
        }).toList();
        return new PageResponse<>(content, result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    public PageResponse<Map<String, Object>> inventory(int page, int size, String search) {
        String term = ReadQuerySupport.escapedLike(search);
        String where = term == null ? "" : " where lower(name) like ? escape '!'";
        Object[] countParams = term == null ? new Object[0] : new Object[]{term};
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Object[] rowParams = term == null
            ? new Object[]{safeSize, (long) safePage * safeSize}
            : new Object[]{term, safeSize, (long) safePage * safeSize};
        String rowsSql = """
            with paged_products as (
                select id, name
                from catalog.products%s
                order by name, id
                limit ? offset ?
            )
            select p.id, p.name as product,
                   coalesce(ib.quantity, 0) as stock,
                   sm.movement_type as "lastMovement",
                   sm.created_at as updated
            from paged_products p
            left join inventory.inventory_balances ib on ib.product_id = p.id
            left join lateral (select movement_type, created_at from inventory.stock_movements
                where product_id = p.id order by created_at desc limit 1) sm on true
            order by p.name, p.id
            """.formatted(where);
        return support.pageUnpaged(rowsSql, rowParams, "select count(*) from catalog.products" + where, countParams, safePage, safeSize);
    }

    public PageResponse<Map<String, Object>> movements(UUID productId, int page, int size) {
        return support.page("""
            select sm.id, sm.movement_type as "movementType", sm.quantity, sm.reason,
                   sm.reference_type as "referenceType", sm.reference_id as "referenceId",
                   sm.created_at as date
            from inventory.stock_movements sm
            where sm.product_id = ?
            order by sm.created_at desc
            """, "select count(*) from inventory.stock_movements where product_id = ?",
            page, size, productId);
    }

    /** Open debts owed by the customer's account; the scope fallback uses each order's recipient. */
    public PageResponse<Map<String, Object>> customerDebts(UUID customerId, int page, int size) {
        support.currentUser().requireCustomerAccess(customerId);
        support.jdbc().queryForObject("select id from customer.customers where id = ?", UUID.class, customerId);
        String source = """
            from sale.sales s
            join orders.orders o on o.id = s.order_id
            join customer.customers c on c.id = s.customer_id
            left join lateral (
                select sum(amount) filter (where entry_type = 'DEBIT') as debit,
                       sum(amount) filter (where entry_type = 'CREDIT') as credit
                from customer.account_ledger where sale_id = s.id
            ) l on true
            where s.account_customer_id = ? and s.status in ('CONFIRMED', 'DELIVERED')
              and s.paid < s.total
              and least(greatest(coalesce(l.debit, 0) - coalesce(l.credit, 0), 0), greatest(s.total - s.paid, 0)) > 0
            """;
        List<Object> parameters = new ArrayList<>(List.of(customerId));
        if (support.sellerScoped()) {
            UUID sellerId = support.currentUser().requireSellerProfile();
            source += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        String rows = """
            select s.id as "saleId", s.sale_number as "saleNumber", s.status,
                   s.total, s.paid, o.id as "orderId", o.order_number as "orderNumber",
                   s.created_at as "createdAt", c.id as "customerId", c.business_name as customer,
                   least(greatest(coalesce(l.debit, 0) - coalesce(l.credit, 0), 0), greatest(s.total - s.paid, 0)) as balance
            """ + source + " order by s.created_at, s.id";
        return support.page(rows, "select count(*) " + source, page, size, parameters.toArray());
    }
}
