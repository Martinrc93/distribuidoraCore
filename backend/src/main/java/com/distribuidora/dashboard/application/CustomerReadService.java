package com.distribuidora.dashboard.application;

import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerReadService {
    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    public CustomerReadService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    public Map<String, Object> customer(UUID customerId) {
        currentUser.requireCustomerAccess(customerId);
        return jdbc.queryForMap("""
            select c.id, c.business_name as name, c.tax_id as "cuitId",
                   c.email, c.phone, c.address, c.zone, c.seller_id as "sellerId",
                   coalesce(sp.display_name, 'Sin asignar') as seller,
                   c.price_list_id as "priceListId", pl.code as "priceListCode", pl.name as "priceList",
                   c.balance, c.status, c.created_at as "createdAt"
            from customer.customers c
            left join seller.seller_profiles sp on sp.id = c.seller_id
            left join catalog.price_lists pl on pl.id = c.price_list_id
            where c.id = ?
            """, customerId);
    }

    public PageResponse<Map<String, Object>> orders(UUID customerId, int page, int size) {
        currentUser.requireCustomerAccess(customerId);
        jdbc.queryForObject("select id from customer.customers where id = ?", UUID.class, customerId);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String where = " where o.customer_id = ?";
        List<Object> parameters = new ArrayList<>(List.of(customerId));
        if (!currentUser.isAdmin()) {
            UUID sellerId = currentUser.requireSellerProfile();
            where += " and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        Long total = jdbc.queryForObject("""
            select count(*) from orders.orders o
            join customer.customers c on c.id = o.customer_id
            """ + where, Long.class, parameters.toArray());
        parameters.add(safeSize);
        parameters.add((long) safePage * safeSize);
        var rows = jdbc.queryForList("""
            select o.id, o.order_number as number, o.total, o.status, o.created_at as date,
                   coalesce(sp.display_name, customer_seller.display_name, 'Sin asignar') as seller
            from orders.orders o
            join customer.customers c on c.id = o.customer_id
            left join seller.seller_profiles sp on sp.id = o.seller_id
            left join seller.seller_profiles customer_seller on customer_seller.id = c.seller_id
            """ + where + " order by o.created_at desc, o.id desc limit ? offset ?", parameters.toArray());
        return PageResponse.of(rows, safePage, safeSize, total == null ? 0 : total);
    }
}
