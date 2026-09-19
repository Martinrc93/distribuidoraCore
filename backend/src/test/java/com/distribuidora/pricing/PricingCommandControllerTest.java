package com.distribuidora.pricing;

import com.distribuidora.pricing.api.PricingCommandController;
import com.distribuidora.pricing.application.PricingCommandService;
import jakarta.validation.constraints.Digits;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import com.distribuidora.shared.error.ApiExceptionHandler;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class PricingCommandControllerTest {
    private final PricingCommandService service = mock(PricingCommandService.class);
    private final PricingCommandController controller = new PricingCommandController(service);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void returnsCreatedIdForListCreation() {
        UUID id = UUID.randomUUID();
        when(service.createList("CUSTOM", "Clientes")).thenReturn(id);

        var response = controller.createList(new PricingCommandController.CreateListRequest("CUSTOM", "Clientes"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().id()).isEqualTo(id);
        verify(service).createList("CUSTOM", "Clientes");
    }

    @Test
    void returnsNoContentForTheThreeUpdateCommands() {
        UUID listId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        assertThat(controller.renameList(listId, new PricingCommandController.RenameListRequest("Nuevo"))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.setListStatus(listId, new PricingCommandController.StatusRequest("ACTIVE"))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.setProductPrice(listId, productId,
            new PricingCommandController.ProductPriceRequest(new BigDecimal("10.2500")))
            .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(service).renameList(listId, "Nuevo");
        verify(service).setListStatus(listId, "ACTIVE");
        verify(service).setProductPrice(listId, productId, new BigDecimal("10.2500"));
    }

    @Test
    void protectsEveryMutationWithAdminAll() throws Exception {
        for (String method : new String[]{"createList", "renameList", "setListStatus", "setProductPrice"}) {
            PreAuthorize annotation = PricingCommandController.class.getDeclaredMethod(
                method, methodParameterTypes(method)).getAnnotation(PreAuthorize.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo("hasAuthority('ADMIN_ALL')");
        }
    }

    @Test
    void constrainsProductPriceToNumericBounds() throws Exception {
        Digits digits = PricingCommandController.ProductPriceRequest.class
            .getDeclaredMethod("price")
            .getAnnotation(Digits.class);

        assertThat(digits).isNotNull();
        assertThat(digits.integer()).isEqualTo(15);
        assertThat(digits.fraction()).isEqualTo(4);
    }

    @Test
    void rejectsLiteralNullBodiesAsInvalidRequests() throws Exception {
        UUID listId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        mockMvc.perform(post("/api/pricing/lists").contentType(APPLICATION_JSON).content("null"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(put("/api/pricing/lists/{listId}", listId)
                .contentType(APPLICATION_JSON).content("null"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(patch("/api/pricing/lists/{listId}/status", listId)
                .contentType(APPLICATION_JSON).content("null"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(put("/api/pricing/lists/{listId}/products/{productId}", listId, productId)
                .contentType(APPLICATION_JSON).content("null"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private static Class<?>[] methodParameterTypes(String method) {
        return switch (method) {
            case "createList" -> new Class<?>[]{PricingCommandController.CreateListRequest.class};
            case "renameList" -> new Class<?>[]{UUID.class, PricingCommandController.RenameListRequest.class};
            case "setListStatus" -> new Class<?>[]{UUID.class, PricingCommandController.StatusRequest.class};
            case "setProductPrice" -> new Class<?>[]{UUID.class, UUID.class, PricingCommandController.ProductPriceRequest.class};
            default -> throw new IllegalArgumentException(method);
        };
    }
}
