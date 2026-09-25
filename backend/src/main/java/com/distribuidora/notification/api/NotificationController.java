package com.distribuidora.notification.api;

import com.distribuidora.notification.application.NotificationRequestService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders/{orderId}/notifications")
public class NotificationController {
    private final NotificationRequestService service;

    public NotificationController(NotificationRequestService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public NotificationDtos.CreateResponse create(@PathVariable UUID orderId,
                                                   @RequestBody NotificationDtos.CreateRequest request) {
        NotificationRequestService.CreateNotificationResult result = service.request(orderId, request);
        return new NotificationDtos.CreateResponse(result.requestId(), result.status());
    }

    @GetMapping("/{requestId}")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public NotificationDtos.StatusResponse status(@PathVariable UUID orderId, @PathVariable UUID requestId) {
        NotificationRequestService.NotificationStatus result = service.status(orderId, requestId);
        return new NotificationDtos.StatusResponse(result.requestId(), result.status(), result.attemptCount(),
            result.requestedAt(), result.sentAt(), result.lastError());
    }
}
