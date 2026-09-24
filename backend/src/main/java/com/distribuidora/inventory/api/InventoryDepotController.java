package com.distribuidora.inventory.api;

import com.distribuidora.inventory.application.InventoryDepotService;
import com.distribuidora.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory/depots")
public class InventoryDepotController {
    private final InventoryDepotService depots;

    public InventoryDepotController(InventoryDepotService depots) {
        this.depots = depots;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public List<InventoryDepotService.Depot> list() {
        return depots.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<InventoryDepotService.Depot> create(@Valid @RequestBody CreateDepotRequest request) {
        return ResponseEntity.status(201).body(depots.create(request.code(), request.name()));
    }

    @PatchMapping("/{depotId}/status")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public InventoryDepotService.Depot setStatus(@PathVariable UUID depotId,
                                                  @Valid @RequestBody DepotStatusRequest request) {
        return depots.setActive(depotId, request.active());
    }

    @GetMapping("/{depotId}/balances")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public PageResponse<Map<String, Object>> balances(
            @PathVariable UUID depotId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "") String search) {
        return depots.balances(depotId, page, size, search);
    }

    public record CreateDepotRequest(@NotBlank @Size(max = 40) String code,
                                     @NotBlank @Size(max = 120) String name) { }

    public record DepotStatusRequest(@NotNull Boolean active) { }
}
