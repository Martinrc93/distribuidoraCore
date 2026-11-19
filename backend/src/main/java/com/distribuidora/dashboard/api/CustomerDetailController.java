package com.distribuidora.dashboard.api;

import com.distribuidora.dashboard.application.CustomerReadService;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
public class CustomerDetailController {
    private final CustomerReadService queries;

    public CustomerDetailController(CustomerReadService queries) {
        this.queries = queries;
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_VIEW', 'ADMIN_ALL')")
    public Map<String, Object> customer(@PathVariable UUID customerId) {
        return queries.customer(customerId);
    }

    @GetMapping("/{customerId}/orders")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_VIEW', 'ADMIN_ALL')")
    public PageResponse<Map<String, Object>> orders(@PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.orders(customerId, page, size);
    }

    @GetMapping("/assignment-context")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_CREATE', 'CUSTOMER_EDIT', 'ADMIN_ALL')")
    public Map<String, Object> assignmentContext() { return queries.assignmentContext(); }

    @GetMapping("/{customerId}/reseller-assignment")
    @PreAuthorize("hasAuthority('ADMIN_ALL') and hasAuthority('RESELLER_MANAGE')")
    public Map<String, Object> resellerAssignment(@PathVariable UUID customerId) {
        return queries.resellerAssignment(customerId);
    }

    @GetMapping("/{customerId}/account-balance")
    @PreAuthorize("hasAnyAuthority('ACCOUNT_SETTLE', 'SALE_PAYMENT', 'ADMIN_ALL')")
    public Map<String, Object> accountBalance(@PathVariable UUID customerId) { return queries.accountBalance(customerId); }
}
