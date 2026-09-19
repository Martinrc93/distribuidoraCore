package com.distribuidora.document.application;

import com.distribuidora.document.rendering.SaleDocumentModel;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SaleDocumentService {
    private final JdbcTemplate jdbc;

    public SaleDocumentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SaleDocumentModel load(UUID orderId) {
        Map<String, Object> header;
        try {
            header = jdbc.queryForMap("""
                select s.id as sale_id, s.sale_number, s.created_at as sale_date,
                       c.business_name as customer_name, coalesce(sp.display_name, '') as seller_name,
                       s.total, s.paid
                  from orders.orders o
                  left join sale.sales s on s.order_id = o.id
                  join customer.customers c on c.id = coalesce(s.customer_id, o.customer_id)
                  left join seller.seller_profiles sp on sp.id = o.seller_id
                 where o.id = ?
                """, orderId);
        } catch (EmptyResultDataAccessException exception) {
            throw new SaleDocumentNotFoundException(orderId);
        }

        if (header.get("sale_id") == null) {
            throw new SaleDocumentConflictException(orderId);
        }

        UUID saleId = uuid(header.get("sale_id"));
        List<SaleDocumentModel.Line> lines = jdbc.query("""
            select si.product_name, si.quantity, si.unit_price,
                   si.line_discount_percent, si.line_total
              from sale.sale_items si
             where si.sale_id = ?
             order by si.id
            """, (rs, rowNum) -> new SaleDocumentModel.Line(
                rs.getString("product_name"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("line_discount_percent"),
                rs.getBigDecimal("line_total")), saleId);
        List<SaleDocumentModel.Payment> payments = jdbc.query("""
            select p.method, p.amount
              from payment.payments p
             where p.sale_id = ?
             order by p.created_at, p.id
            """, (rs, rowNum) -> new SaleDocumentModel.Payment(
                rs.getString("method"), rs.getBigDecimal("amount")), saleId);

        return new SaleDocumentModel(
            (String) header.get("sale_number"),
            saleDate(header.get("sale_date")),
            (String) header.get("customer_name"),
            (String) header.get("seller_name"),
            lines,
            payments,
            decimal(header.get("total")),
            decimal(header.get("paid")));
    }

    private UUID uuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private LocalDate saleDate(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return ((java.time.LocalDateTime) value).toLocalDate();
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }
}
