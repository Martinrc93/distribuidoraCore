package com.distribuidora.pricing.api;

import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.web.PageResponse;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/pricing")
public class PricingQueryController {
    private final PricingQueryService queries;
    private final CurrentUserAccess access;

    public PricingQueryController(PricingQueryService queries, CurrentUserAccess access) {
        this.queries = queries;
        this.access = access;
    }

    @GetMapping("/lists")
    public PageResponse<Map<String, Object>> lists(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return queries.lists(page, size);
    }

    @GetMapping("/lists/{listId}/prices")
    public PageResponse<Map<String, Object>> prices(
        @PathVariable UUID listId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return queries.prices(listId, page, size);
    }

    @GetMapping("/lists/{listId}/products/{productId}/history")
    public PageResponse<Map<String, Object>> history(
        @PathVariable UUID listId,
        @PathVariable UUID productId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return queries.history(listId, productId, page, size);
    }

    @GetMapping("/resolve")
    public Map<String, Object> resolve(
        @RequestParam UUID customerId,
        @RequestParam UUID productId,
        @RequestParam(required = false) UUID priceListId,
        @RequestParam(required = false) LocalDate asOf
    ) {
        return queries.resolveAsOf(customerId, productId, priceListId, asOf);
    }

    @GetMapping("/resolve-batch")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public List<Map<String, Object>> resolveBatch(
        @RequestParam UUID customerId,
        @RequestParam List<UUID> productIds,
        @RequestParam(required = false) UUID priceListId
    ) {
        access.requireCustomerAccess(customerId);
        return queries.resolveBatch(customerId, productIds, priceListId);
    }
}
