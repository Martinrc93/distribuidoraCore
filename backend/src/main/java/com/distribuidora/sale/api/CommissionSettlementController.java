package com.distribuidora.sale.api;

import com.distribuidora.sale.application.CommissionSettlementService;
import com.distribuidora.sale.application.CommissionSettlementService.Settlement;
import com.distribuidora.sale.application.CommissionSettlementService.Statement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/commissions/sellers/{sellerId}")
@PreAuthorize("hasAuthority('ADMIN_ALL')")
public class CommissionSettlementController {
    public record SettleRequest(String token) { }

    private final CommissionSettlementService commissions;

    public CommissionSettlementController(CommissionSettlementService commissions) {
        this.commissions = commissions;
    }

    @GetMapping
    public Statement statement(
        @PathVariable UUID sellerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return commissions.statement(sellerId, dateMin, dateMax, page, size);
    }

    @PostMapping("/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public Settlement register(@PathVariable UUID sellerId, @RequestBody SettleRequest request) {
        return commissions.settle(sellerId, request.token());
    }
}
