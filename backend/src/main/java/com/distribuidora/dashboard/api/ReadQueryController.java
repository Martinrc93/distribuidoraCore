package com.distribuidora.dashboard.api;

import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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
    public Map<String, Object> dashboard(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax) {
        return queries.dashboard(dateMin, dateMax);
    }

    @GetMapping("/dashboard/seller-orders")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> dashboardSellerOrders(
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.dashboardSellerOrders(sellerId, unassigned, dateMin, dateMax, page, size);
    }

    @GetMapping("/customers")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'CUSTOMER_VIEW', 'CUSTOMER_CREATE', 'CUSTOMER_EDIT', 'CUSTOMER_STATUS_CHANGE', 'CUSTOMER_PRICE_LIST_CHANGE', 'ACCOUNT_SETTLE', 'CUSTOMER_STATEMENT_EXPORT', 'ORDER_CREATE', 'ORDER_EDIT', 'SALE_PAYMENT')")
    public PageResponse<Map<String, Object>> customers(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UUID sellerId, @RequestParam(defaultValue = "false") boolean hasBalance,
            @RequestParam(defaultValue = "") String status, @RequestParam(defaultValue = "") String kind,
            @RequestParam(required = false) UUID resellerId) {
        return queries.customers(page, size, search, sellerId, hasBalance, status, kind, resellerId);
    }

    @GetMapping("/customers/filter-options")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'CUSTOMER_VIEW', 'CUSTOMER_CREATE', 'CUSTOMER_EDIT', 'CUSTOMER_STATUS_CHANGE', 'CUSTOMER_PRICE_LIST_CHANGE', 'ACCOUNT_SETTLE', 'CUSTOMER_STATEMENT_EXPORT', 'ORDER_CREATE', 'ORDER_EDIT', 'SALE_PAYMENT')")
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
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'ORDER_CREATE', 'ORDER_EDIT', 'CUSTOMER_VIEW', 'REPORTS_VIEW')")
    public PageResponse<Map<String, Object>> products(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "true") boolean includeStock,
            @RequestParam(required = false) UUID brandId, @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "ALL") ReadQueryService.StockFilter stock) {
        return queries.products(page, size, search, includeStock, brandId, categoryId, stock);
    }

    @GetMapping("/inventory")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> inventory(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search) { return queries.inventory(page, size, search); }

    @GetMapping("/inventory/{productId}/movements")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> movements(@PathVariable UUID productId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return queries.movements(productId, page, size); }

    @GetMapping("/orders")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'ORDER_CREATE', 'ORDER_EDIT', 'ORDER_PREPARE', 'ORDER_CANCEL', 'ORDER_REACTIVATE', 'ORDER_DOCUMENT_EXPORT', 'ORDER_NOTIFY', 'SALE_DELIVER', 'SALE_PAYMENT', 'SALE_RETURN', 'REPORTS_VIEW')")
    public PageResponse<Map<String, Object>> orders(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax,
            @RequestParam(required = false) String createdBy,
            @RequestParam(defaultValue = "false") boolean paidUndelivered,
            @RequestParam(required = false) String fulfillmentStatus,
            @RequestParam(defaultValue = "false") boolean reviewPending) {
        return queries.orders(page, size, search, status, dateMin, dateMax, createdBy, paidUndelivered, fulfillmentStatus, reviewPending);
    }

    @GetMapping("/orders/{orderId}")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'ORDER_CREATE', 'ORDER_EDIT', 'ORDER_PREPARE', 'ORDER_CANCEL', 'ORDER_REACTIVATE', 'ORDER_DOCUMENT_EXPORT', 'ORDER_NOTIFY', 'SALE_DELIVER', 'SALE_PAYMENT', 'SALE_RETURN', 'REPORTS_VIEW')")
    public Map<String, Object> order(@PathVariable UUID orderId) { return queries.orderDetail(orderId); }

    @GetMapping("/orders/by-number/{orderNumber}")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'ORDER_CREATE', 'ORDER_EDIT', 'ORDER_PREPARE', 'ORDER_CANCEL', 'ORDER_REACTIVATE', 'ORDER_DOCUMENT_EXPORT', 'ORDER_NOTIFY', 'SALE_DELIVER', 'SALE_PAYMENT', 'SALE_RETURN', 'REPORTS_VIEW')")
    public Map<String, Object> orderByNumber(@PathVariable String orderNumber) { return queries.orderDetailByNumber(orderNumber); }

    @GetMapping("/sales/{saleId}")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'ORDER_CREATE', 'ORDER_EDIT', 'SALE_DELIVER', 'SALE_PAYMENT', 'SALE_RETURN', 'ACCOUNT_SETTLE', 'REPORTS_VIEW')")
    public Map<String, Object> sale(@PathVariable UUID saleId) { return queries.saleDetail(saleId); }

    @GetMapping("/sales")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'ORDER_CREATE', 'ORDER_EDIT', 'SALE_DELIVER', 'SALE_PAYMENT', 'SALE_RETURN', 'ACCOUNT_SETTLE', 'REPORTS_VIEW')")
    public PageResponse<Map<String, Object>> sales(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UUID customerId, @RequestParam(required = false) UUID accountCustomerId,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(defaultValue = "false") boolean pendingBalance,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax) {
        return queries.sales(page, size, search, customerId, accountCustomerId, sellerId, pendingBalance, dateMin, dateMax);
    }

    @GetMapping("/sales/filter-options")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'ORDER_CREATE', 'ORDER_EDIT', 'SALE_DELIVER', 'SALE_PAYMENT', 'SALE_RETURN', 'ACCOUNT_SETTLE', 'REPORTS_VIEW')")
    public Map<String, Object> saleFilterOptions() {
        return queries.saleFilterOptions();
    }

    @GetMapping("/payments")
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'SALE_PAYMENT', 'ACCOUNT_SETTLE')")
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

    @GetMapping("/sellers/profiles")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> commercialProfiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String kind,
            Authentication authentication) {
        boolean canViewResellers = authentication.getAuthorities().stream()
            .anyMatch(authority -> "RESELLER_MANAGE".equals(authority.getAuthority()));
        return queries.commercialProfiles(page, size, search, status, kind, canViewResellers);
    }

    @GetMapping("/sellers/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public Map<String, Object> seller(@PathVariable UUID id) {
        return queries.sellerDetail(id);
    }
}
