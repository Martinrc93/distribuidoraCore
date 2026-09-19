package com.distribuidora.order.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class DeliveryLifecycleDtos {
    private DeliveryLifecycleDtos() { }

    public record DeliveryAttemptRequest(
        @NotBlank @Size(max = 20) String result,
        @Size(max = 2000) String observation
    ) { }
}
