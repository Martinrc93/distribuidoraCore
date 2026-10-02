package com.distribuidora.dashboard.api;

import com.distribuidora.dashboard.application.DashboardAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
public class DashboardAnalyticsController {
    private final DashboardAnalyticsService analytics;

    public DashboardAnalyticsController(DashboardAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/api/dashboard/analytics")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public Map<String, Object> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax) {
        return analytics.report(dateMin, dateMax);
    }
}
