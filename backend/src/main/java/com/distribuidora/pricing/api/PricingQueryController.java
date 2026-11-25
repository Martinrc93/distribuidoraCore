package com.distribuidora.pricing.api;

import com.distribuidora.shared.security.PermissionAccess;

import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.web.PageResponse;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.security.access.AccessDeniedException;
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
    @PreAuthorize("hasAnyAuthority('ADMIN_ALL', 'ORDER_CREATE', 'ORDER_EDIT', 'CUSTOMER_CREATE', 'CUSTOMER_EDIT', 'CUSTOMER_PRICE_LIST_CHANGE')")
    public PageResponse<Map<String, Object>> lists(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return queries.lists(page, size, access.isAdmin());
    }

    @GetMapping("/lists/{listId}/prices")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public PageResponse<Map<String, Object>> prices(
        @PathVariable UUID listId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String followStatus,
        @RequestParam(required = false) UUID categoryId,
        @RequestParam(required = false) UUID brandId,
        @RequestParam(required = false) String search
    ) {
        return queries.prices(listId, page, size, new PricingQueryService.PriceFilter(
            followStatus == null || followStatus.isBlank() ? null : followStatus, categoryId, brandId, search, access.isAdmin(), !access.isAdmin()));
    }

    @GetMapping("/lists/{listId}/products/{productId}/history")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> history(
        @PathVariable UUID listId,
        @PathVariable UUID productId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return queries.history(listId, productId, page, size);
    }

    @GetMapping("/resolve")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_EDIT', 'ADMIN_ALL')")
    public Map<String, Object> resolve(
        @RequestParam UUID customerId,
        @RequestParam UUID productId,
        @RequestParam(required = false) UUID priceListId,
        @RequestParam(required = false) LocalDate asOf,
        @RequestParam(required = false) UUID orderId
    ) {
        if (asOf != null && !access.isAdmin()) {
            throw new AccessDeniedException("Consultar precios de otra fecha requiere acceso administrativo");
        }
        requireOrderPricingAccess(customerId, orderId);
        return queries.resolveAsOf(customerId, productId, priceListId, asOf);
    }

    @GetMapping("/resolve-batch")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_EDIT', 'ADMIN_ALL')")
    public List<Map<String, Object>> resolveBatch(
        @RequestParam UUID customerId,
        @RequestParam List<UUID> productIds,
        @RequestParam(required = false) UUID priceListId,
        @RequestParam(required = false) UUID orderId
    ) {
        requireOrderPricingAccess(customerId, orderId);
        return queries.resolveBatch(customerId, productIds, priceListId);
    }

    private void requireOrderPricingAccess(UUID customerId, UUID orderId) {
        if (orderId == null) {
            PermissionAccess.require("ORDER_CREATE");
            access.requireCustomerAccess(customerId);
        } else {
            PermissionAccess.require("ORDER_EDIT");
            access.requireOrderCustomerAccess(orderId, customerId);
        }
    }
}
