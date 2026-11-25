package com.distribuidora.document.application;

import com.distribuidora.document.rendering.SaleDocumentModel;
import com.distribuidora.settings.application.BusinessProfile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.distribuidora.shared.security.CurrentUserAccess;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

@Service
public class SaleDocumentService {
    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;

    @Autowired
    public SaleDocumentService(JdbcTemplate jdbc, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SaleDocumentModel load(UUID orderId) {
        currentUser.requireOrderAccess(orderId);
        return loadModel(orderId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SaleDocumentModel loadForDelivery(UUID orderId) {
        return loadModel(orderId);
    }

    private SaleDocumentModel loadModel(UUID orderId) {
        Map<String, Object> header;
        try {
            header = jdbc.queryForMap("""
                select s.id as sale_id, s.sale_number,
                       (s.created_at at time zone 'America/Argentina/Buenos_Aires')::date as sale_date,
                       c.business_name as customer_name, coalesce(sp.display_name, '') as seller_name,
                       s.total, s.paid, s.order_discount_percent, b.business_name, b.business_address, b.business_phone,
                       b.business_logo
                  from orders.orders o
                  left join sale.sales s on s.order_id = o.id
                  join customer.customers c on c.id = coalesce(s.customer_id, o.customer_id)
                  left join seller.seller_profiles sp on sp.id = o.seller_id
                  cross join app.business_settings b
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
        return new SaleDocumentModel(
            (String) header.get("sale_number"),
            saleDate(header.get("sale_date")),
            (String) header.get("customer_name"),
            (String) header.get("seller_name"),
            lines,
            List.of(),
            decimal(header.get("total")),
            decimal(header.get("paid")),
            decimal(header.getOrDefault("order_discount_percent", BigDecimal.ZERO)),
            new BusinessProfile((String) header.getOrDefault("business_name", ""),
                (String) header.getOrDefault("business_address", ""), (String) header.getOrDefault("business_phone", "")),
            (byte[]) header.get("business_logo"));
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
