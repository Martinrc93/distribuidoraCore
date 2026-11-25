package com.distribuidora.purchasing.api;

import com.distribuidora.purchasing.application.SupplierOrderQueryService;
import com.distribuidora.purchasing.application.SupplierOrderService;
import com.distribuidora.purchasing.application.SupplierOrderService.OrderInput;
import com.distribuidora.purchasing.application.SupplierOrderService.OrderResult;
import com.distribuidora.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/supplier-orders")
@PreAuthorize("hasAuthority('ADMIN_ALL')")
public class SupplierOrderController {
    private final SupplierOrderService service;
    private final SupplierOrderQueryService queries;

    public SupplierOrderController(SupplierOrderService service, SupplierOrderQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    @GetMapping
    public PageResponse<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax) {
        return queries.list(page, size, supplierId, dateMin, dateMax);
    }

    @PostMapping
    public ResponseEntity<OrderResult> create(@Valid @RequestBody OrderInput input) {
        return ResponseEntity.status(201).body(service.create(input));
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) { return queries.detail(id); }

    /** Replaces supplier, date and lines; a confirmed purchase moves stock by the difference. */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @Valid @RequestBody OrderInput input) { return service.update(id, input); }

    /** Confirms a pending purchase and adds its quantities to stock. */
    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable UUID id) { return service.confirm(id); }

    /** Cancels the purchase; a confirmed one gives its quantities back. */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) { return service.cancel(id); }

    @GetMapping("/supplier/{supplierId}/last-order")
    public Map<String, Object> lastOrder(@PathVariable UUID supplierId) { return queries.lastOrder(supplierId); }
}
