package com.distribuidora.purchasing.api;

import com.distribuidora.purchasing.application.SupplierOrderService;
import com.distribuidora.purchasing.application.SupplierOrderService.OrderInput;
import com.distribuidora.purchasing.application.SupplierOrderService.OrderResult;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/supplier-orders")
@PreAuthorize("hasAuthority('ADMIN_ALL')")
public class SupplierOrderController {
    private final SupplierOrderService service;
    public SupplierOrderController(SupplierOrderService service) { this.service = service; }

    @GetMapping
    public PageResponse<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax) {
        return service.list(page, size, supplierId, dateMin, dateMax);
    }

    @PostMapping
    public ResponseEntity<OrderResult> create(@RequestBody OrderInput input) {
        return ResponseEntity.status(201).body(service.create(input));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) { return service.detail(id); }

    @GetMapping("/supplier/{supplierId}/last-order")
    public Map<String, Object> lastOrder(@PathVariable UUID supplierId) { return service.lastOrder(supplierId); }
}
