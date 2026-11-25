package com.distribuidora.settings.api;

import com.distribuidora.settings.application.CommissionPlansService;
import com.distribuidora.settings.application.CommissionPlansService.Assignment;
import com.distribuidora.settings.application.CommissionPlansService.AssignmentOverview;
import com.distribuidora.settings.application.CommissionPlansService.Overview;
import com.distribuidora.settings.application.CommissionPlansService.Plan;
import com.distribuidora.settings.application.CommissionPlansService.ResellerAssignment;
import com.distribuidora.settings.application.CommissionPlansService.Rule;
import com.distribuidora.shared.web.PageResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/settings/commission-plans")
@PreAuthorize("hasAuthority('ADMIN_ALL')")
public class CommissionPlansController {
    public record SavePlan(
        @NotBlank @Size(max = 100) String name,
        Long version,
        @NotEmpty @Size(max = 1000) List<Rule> rules) { }
    public record SaveAssignment(@JsonProperty(required = true) UUID planId,
                                 @JsonProperty(required = true) UUID expectedPlanId) { }

    private final CommissionPlansService service;

    public CommissionPlansController(CommissionPlansService service) {
        this.service = service;
    }

    @GetMapping
    public List<Overview> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Plan find(@PathVariable UUID id) {
        return service.find(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Plan create(@Valid @RequestBody SavePlan request) {
        return service.create(request.name(), request.rules());
    }

    @PutMapping("/{id}")
    public Plan update(@PathVariable UUID id, @Valid @RequestBody SavePlan request) {
        return service.update(id, request.version(), request.name(), request.rules());
    }

    @GetMapping("/assignments")
    public PageResponse<AssignmentOverview> listAssignments(@RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return service.listAssignments(page, size);
    }

    @GetMapping("/assignments/{sellerId}")
    public Assignment assignment(@PathVariable UUID sellerId) {
        return service.assignment(sellerId);
    }

    @PutMapping("/assignments/{sellerId}")
    public Assignment assign(@PathVariable UUID sellerId, @Valid @RequestBody SaveAssignment request) {
        return service.assign(sellerId, request.planId(), request.expectedPlanId());
    }

    @GetMapping("/reseller-assignments/{customerId}")
    public ResellerAssignment resellerAssignment(@PathVariable UUID customerId) {
        return service.resellerAssignment(customerId);
    }

    @PutMapping("/reseller-assignments/{customerId}")
    public ResellerAssignment assignReseller(@PathVariable UUID customerId, @Valid @RequestBody SaveAssignment request) {
        return service.assignReseller(customerId, request.planId(), request.expectedPlanId());
    }
}
