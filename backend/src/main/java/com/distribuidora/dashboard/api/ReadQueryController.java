package com.distribuidora.dashboard.api;

import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ReadQueryController {
    private final ReadQueryService queries;

    public ReadQueryController(ReadQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public Map<String, Object> dashboard() { return queries.dashboard(); }

    @GetMapping("/customers")
    public PageResponse<Map<String, Object>> customers(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UUID sellerId, @RequestParam(defaultValue = "false") boolean hasBalance,
            @RequestParam(defaultValue = "") String status) {
        return queries.customers(page, size, search, sellerId, hasBalance, status);
    }

    @GetMapping("/customers/filter-options")
    public Map<String, Object> customerFilterOptions() { return queries.customerFilterOptions(); }

    @GetMapping("/customers/{customerId}/debts")
    @PreAuthorize("hasAnyAuthority('SALE_PAYMENT', 'ADMIN_ALL')")
    public PageResponse<Map<String, Object>> customerDebts(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.customerDebts(customerId, page, size);
    }

    @GetMapping("/customers/{customerId}/last-order")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public Map<String, Object> lastCustomerOrder(@PathVariable UUID customerId) {
        return queries.lastCustomerOrder(customerId);
    }

    @GetMapping("/products")
    public PageResponse<Map<String, Object>> products(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "true") boolean includeStock) {
        return queries.products(page, size, search, includeStock);
    }

    @GetMapping("/inventory")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> inventory(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search) { return queries.inventory(page, size, search); }

    @GetMapping("/inventory/{productId}/movements")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> movements(@PathVariable UUID productId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return queries.movements(productId, page, size); }

    @GetMapping("/orders")
    public PageResponse<Map<String, Object>> orders(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax) {
        return queries.orders(page, size, search, status, dateMin, dateMax);
    }

    @GetMapping("/orders/{orderId}")
    public Map<String, Object> order(@PathVariable UUID orderId) { return queries.orderDetail(orderId); }

    @GetMapping("/orders/by-number/{orderNumber}")
    public Map<String, Object> orderByNumber(@PathVariable String orderNumber) { return queries.orderDetailByNumber(orderNumber); }

    @GetMapping("/sales/{saleId}")
    public Map<String, Object> sale(@PathVariable UUID saleId) { return queries.saleDetail(saleId); }

    @GetMapping("/sales")
    public PageResponse<Map<String, Object>> sales(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UUID customerId, @RequestParam(required = false) UUID sellerId,
            @RequestParam(defaultValue = "false") boolean pendingBalance,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax) {
        return queries.sales(page, size, search, customerId, sellerId, pendingBalance, dateMin, dateMax);
    }

    @GetMapping("/sales/filter-options")
    public Map<String, Object> saleFilterOptions() {
        return queries.saleFilterOptions();
    }

    @GetMapping("/payments")
    public PageResponse<Map<String, Object>> payments(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search) { return queries.payments(page, size, search); }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> auditEvents(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search) {
        return queries.auditEvents(page, size, search);
    }

    @GetMapping("/users")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> users(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search) { return queries.users(page, size, search); }

    @GetMapping("/sellers")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> sellers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String status) {
        if ((search == null || search.isBlank()) && (status == null || status.isBlank())) {
            return queries.sellers(page, size);
        }
        return queries.sellers(page, size, search, status);
    }

    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> sellers(int page, int size) {
        return queries.sellers(page, size);
    }

    @GetMapping("/sellers/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public Map<String, Object> seller(@PathVariable UUID id) {
        return queries.sellerDetail(id);
    }
}
