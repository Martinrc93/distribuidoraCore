package com.distribuidora.pricing.api;

import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pricing")
public class PricingQueryController {
    private final PricingQueryService queries;

    public PricingQueryController(PricingQueryService queries) {
        this.queries = queries;
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

    @GetMapping("/resolve")
    public Map<String, Object> resolve(
        @RequestParam UUID customerId,
        @RequestParam UUID productId,
        @RequestParam(required = false) UUID priceListId
    ) {
        return queries.resolve(customerId, productId, priceListId);
    }
}
