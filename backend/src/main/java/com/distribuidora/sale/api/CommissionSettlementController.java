package com.distribuidora.sale.api;

import com.distribuidora.sale.application.CommissionSettlementService;
import com.distribuidora.sale.application.CommissionSettlementService.Beneficiary;
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
@RequestMapping("/api/commissions")
@PreAuthorize("hasAuthority('ADMIN_ALL')")
public class CommissionSettlementController {
    public record SettleRequest(String token) { }

    private final CommissionSettlementService commissions;

    public CommissionSettlementController(CommissionSettlementService commissions) {
        this.commissions = commissions;
    }

    @GetMapping("/sellers/{sellerId}")
    public Statement sellerStatement(
        @PathVariable UUID sellerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return commissions.statement(Beneficiary.SELLER, sellerId, dateMin, dateMax, page, size);
    }

    @PostMapping("/sellers/{sellerId}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public Settlement settleSeller(@PathVariable UUID sellerId, @RequestBody SettleRequest request) {
        return commissions.settle(Beneficiary.SELLER, sellerId, request.token());
    }

    @GetMapping("/resellers/{customerId}")
    public Statement resellerStatement(
        @PathVariable UUID customerId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMin,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateMax,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return commissions.statement(Beneficiary.RESELLER, customerId, dateMin, dateMax, page, size);
    }

    @PostMapping("/resellers/{customerId}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public Settlement settleReseller(@PathVariable UUID customerId, @RequestBody SettleRequest request) {
        return commissions.settle(Beneficiary.RESELLER, customerId, request.token());
    }
}
