package com.distribuidora.sale.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SaleReturnService {
    public interface ReturnCommand {
        String reason();
        List<? extends ReturnItemCommand> items();
    }

    public interface ReturnItemCommand {
        UUID saleItemId();
        BigDecimal quantity();
    }

    public record ReturnItemResult(UUID saleItemId, UUID productId, BigDecimal quantity) { }

    public record ReturnResult(UUID returnId, UUID saleId, String reason, List<ReturnItemResult> items) {
        public ReturnResult { items = List.copyOf(items); }
    }

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final JdbcTemplate jdbc;
    private final InventoryMovementService inventory;
    private final AuditService audit;

    public SaleReturnService(JdbcTemplate jdbc, InventoryMovementService inventory, AuditService audit) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
    }

    @Transactional
    public ReturnResult create(UUID saleId, ReturnCommand request) {
        UUID actorId = actorId();
        validate(saleId, request);

        Map<String, Object> sale = jdbc.queryForMap("""
            select s.id, s.status as sale_status, o.status as order_status
            from sale.sales s
            join orders.orders o on o.id = s.order_id
            where s.id = ?
            for update of s
            """, saleId);
        if (!"DELIVERED".equals(sale.get("sale_status")) || !"DELIVERED".equals(sale.get("order_status"))) {
            throw new IllegalStateException("Solo se pueden devolver ventas entregadas");
        }

        Set<UUID> requestedIds = new HashSet<>();
        for (ReturnItemCommand item : request.items()) {
            if (item.saleItemId() == null || !requestedIds.add(item.saleItemId())) {
                throw new IllegalArgumentException("Cada línea de venta debe aparecer una sola vez en la devolución");
            }
            if (item.quantity() == null || item.quantity().signum() <= 0 || item.quantity().remainder(HALF).signum() != 0) {
                throw new IllegalArgumentException("La cantidad devuelta debe ser un múltiplo positivo de 0.5");
            }
        }

        List<ReturnItemResult> resolved = new ArrayList<>();
        for (ReturnItemCommand item : request.items()) {
            List<Map<String, Object>> lines = jdbc.queryForList(
                "select id, product_id, quantity from sale.sale_items where id = ? and sale_id = ?",
                item.saleItemId(), saleId);
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("Una línea informada no pertenece a la venta");
            }
            Map<String, Object> line = lines.getFirst();
            BigDecimal sold = decimal(line.get("quantity"));
            BigDecimal previouslyReturned = jdbc.queryForObject("""
                select coalesce(sum(ri.quantity), 0)
                from sale.return_items ri
                join sale.returns r on r.id = ri.return_id
                where r.sale_id = ? and ri.sale_item_id = ?
                """, BigDecimal.class, saleId, item.saleItemId());
            if (previouslyReturned.add(item.quantity()).compareTo(sold) > 0) {
                throw new IllegalStateException("La cantidad solicitada supera la cantidad aún disponible para devolución");
            }
            resolved.add(new ReturnItemResult(
                item.saleItemId(), uuid(line.get("product_id")), item.quantity().setScale(4)));
        }

        UUID returnId = UUID.randomUUID();
        String reason = request.reason().trim();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into sale.returns(id, sale_id, returned_by, reason, created_at) values (?, ?, ?, ?, ?)",
            returnId, saleId, actorId, reason, now);
        for (ReturnItemResult item : resolved) {
            jdbc.update("insert into sale.return_items(id, return_id, sale_id, sale_item_id, product_id, quantity) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), returnId, saleId, item.saleItemId(), item.productId(), item.quantity());
            inventory.apply(item.productId(), item.quantity(), "RETURN", returnId, reason);
        }

        audit.recordWithinTransaction(actorId, "SALE_RETURN", "SALE", saleId.toString(), "SUCCESS",
            Map.of("returnId", returnId.toString(), "itemCount", resolved.size(), "reason", reason));
        return new ReturnResult(returnId, saleId, reason, resolved);
    }

    private UUID actorId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new AccessDeniedException("Se requiere un administrador autenticado para registrar devoluciones");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("La identidad autenticada no es válida", exception);
        }
    }

    private void validate(UUID saleId, ReturnCommand request) {
        if (saleId == null || request == null || request.reason() == null || request.reason().isBlank()
            || request.reason().length() > 500 || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("La venta, el motivo y al menos una línea son obligatorios");
        }
    }

    private UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal amount ? amount : new BigDecimal(String.valueOf(value));
    }
}
