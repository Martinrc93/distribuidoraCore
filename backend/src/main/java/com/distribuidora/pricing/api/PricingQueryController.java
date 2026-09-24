package com.distribuidora.pricing.api;

import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.pricing.application.CommercialDiscountRuleQueryService;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/pricing")
public class PricingQueryController {
    private final PricingQueryService queries;
    private final CommercialDiscountRuleQueryService discountRules;

    public PricingQueryController(PricingQueryService queries, CommercialDiscountRuleQueryService discountRules) {
        this.queries = queries;
        this.discountRules = discountRules;
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

    @GetMapping("/discount-rules")
    public PageResponse<Map<String, Object>> discountRules(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return discountRules.rules(page, size);
    }
}
