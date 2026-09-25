package com.distribuidora.sale.api;

import com.distribuidora.sale.application.SaleReturnService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/sales")
public class SaleReturnController {
    private final SaleReturnService service;

    public SaleReturnController(SaleReturnService service) {
        this.service = service;
    }

    @PostMapping("/{saleId}/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public SaleReturnDtos.ReturnResponse create(@PathVariable UUID saleId,
                                                @Valid @RequestBody SaleReturnDtos.ReturnRequest request) {
        SaleReturnService.ReturnResult result = service.create(saleId, request);
        var items = result.items().stream()
            .map(item -> new SaleReturnDtos.ReturnItemResponse(item.saleItemId(), item.productId(), item.quantity()))
            .toList();
        return new SaleReturnDtos.ReturnResponse(result.returnId(), result.saleId(), result.reason(), items);
    }
}
