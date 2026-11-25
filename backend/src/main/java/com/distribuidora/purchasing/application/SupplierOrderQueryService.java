package com.distribuidora.purchasing.application;

import com.distribuidora.shared.web.PageResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SupplierOrderQueryService {
    public record DocumentLine(String product, BigDecimal quantity) { }
    public record OrderDocument(String number, String supplier, LocalDate date, List<DocumentLine> lines) { }

    private final JdbcTemplate jdbc;

    public SupplierOrderQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<Map<String, Object>> list(int page, int size, UUID supplierId, LocalDate dateMin, LocalDate dateMax) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Paginación inválida");
        if (dateMin != null && dateMax != null && dateMin.isAfter(dateMax)) throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la final");
        var parameters = new ArrayList<Object>();
        String where = " where 1 = 1";
        if (supplierId != null) { where += " and supplier_id = ?"; parameters.add(supplierId); }
        if (dateMin != null) { where += " and order_date >= ?"; parameters.add(dateMin); }
        if (dateMax != null) { where += " and order_date <= ?"; parameters.add(dateMax); }
        Long total = jdbc.queryForObject("select count(*) from purchasing.supplier_orders" + where, Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((long) page * size);
        var rows = jdbc.queryForList("select id, order_number as number, supplier_id as \"supplierId\", supplier_name as supplier, status, "
            + "order_date as date, total from purchasing.supplier_orders" + where + " order by order_date desc, created_at desc, id desc limit ? offset ?",
            parameters.toArray());
        rows.forEach(row -> row.put("date", row.get("date").toString()));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(UUID id) {
        return loadDetail(id);
    }

    Map<String, Object> loadDetail(UUID id) {
        var order = jdbc.queryForMap("select id, order_number as number, supplier_id as \"supplierId\", supplier_name as supplier, status, "
            + "confirmed_at as \"confirmedAt\", cancelled_at as \"cancelledAt\", "
            + "order_date as date, total from purchasing.supplier_orders where id = ?", id);
        order.put("date", order.get("date").toString());
        var items = jdbc.queryForList("""
            select i.product_id as "productId", i.product_name as "productName", i.quantity,
                   i.unit_cost as "unitCost", i.line_total as "lineTotal", p.status, p.cost as "currentCost"
            from purchasing.supplier_order_items i join catalog.products p on p.id = i.product_id
            where i.order_id = ? order by i.product_name, i.product_id
            """, id);
        return Map.of("order", order, "items", items);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderDocument document(UUID id) {
        return jdbc.query("""
            select o.order_number, o.supplier_name, o.order_date, i.product_name, i.quantity
            from purchasing.supplier_orders o
            left join purchasing.supplier_order_items i on i.order_id = o.id
            where o.id = ? order by i.product_name, i.product_id
            """, rows -> {
                if (!rows.next()) throw new EmptyResultDataAccessException(1);
                String number = rows.getString("order_number");
                String supplier = rows.getString("supplier_name");
                LocalDate date = rows.getObject("order_date", LocalDate.class);
                var lines = new ArrayList<DocumentLine>();
                do {
                    String product = rows.getString("product_name");
                    if (product != null) lines.add(new DocumentLine(product, rows.getBigDecimal("quantity")));
                } while (rows.next());
                return new OrderDocument(number, supplier, date, List.copyOf(lines));
            }, id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> lastOrder(UUID supplierId) {
        jdbc.queryForObject("select id from supplier.suppliers where id = ?", UUID.class, supplierId);
        var ids = jdbc.queryForList("select id from purchasing.supplier_orders where supplier_id = ? "
            + "order by order_date desc, created_at desc, id desc limit 1", UUID.class, supplierId);
        if (ids.isEmpty()) return Map.of("available", false);
        var result = new HashMap<>(loadDetail(ids.getFirst()));
        result.put("available", true);
        return result;
    }
}
