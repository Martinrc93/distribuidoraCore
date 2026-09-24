package com.distribuidora.order.api;

import com.distribuidora.order.application.DeliveryLifecycleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

public final class DeliveryLifecycleDtos {
    private DeliveryLifecycleDtos() { }

    public record DeliveryAttemptRequest(
        @NotBlank @Size(max = 20) String result,
        @Size(max = 2000) String observation,
        List<@NotNull @Valid DeliveryPaymentRequest> payments,
        @Size(max = 100) String transferReference
    ) implements DeliveryLifecycleService.DeliveryAttemptCommand {
        public DeliveryAttemptRequest(String result, String observation) {
            this(result, observation, null, null);
        }
    }

    public record DeliveryPaymentRequest(
        @NotBlank @Pattern(regexp = "CASH|BANK_TRANSFER") String method,
        @NotNull @DecimalMin(value = "0.0000", inclusive = false) @Digits(integer = 15, fraction = 4)
        BigDecimal amount
    ) implements DeliveryLifecycleService.DeliveryPaymentCommand { }
}
