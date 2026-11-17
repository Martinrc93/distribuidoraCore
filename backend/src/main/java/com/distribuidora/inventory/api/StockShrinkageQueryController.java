package com.distribuidora.inventory.api;

import com.distribuidora.inventory.application.StockShrinkageQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/inventory/shrinkages")
@PreAuthorize("hasAuthority('ADMIN_ALL')")
public class StockShrinkageQueryController {
    private final StockShrinkageQueryService queries;

    public StockShrinkageQueryController(StockShrinkageQueryService queries) {
        this.queries = queries;
    }

    @GetMapping
    public StockShrinkageQueryService.Report report(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax
    ) {
        return queries.report(page, size, dateMin, dateMax);
    }
}
