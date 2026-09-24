package com.distribuidora.sale;

import com.distribuidora.sale.api.SaleReturnController;
import com.distribuidora.sale.api.SaleReturnDtos;
import com.distribuidora.sale.application.SaleReturnService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SaleReturnControllerTest {
    private final SaleReturnService service = mock(SaleReturnService.class);
    private final SaleReturnController controller = new SaleReturnController(service);

    @Test
    void createsReturnAndReturnsCreatedResponse() {
        UUID saleId = UUID.randomUUID();
        var request = new SaleReturnDtos.ReturnRequest("Producto dañado", List.of());
        var response = new SaleReturnDtos.ReturnResponse(UUID.randomUUID(), saleId, "Producto dañado", List.of());
        when(service.create(saleId, request)).thenReturn(response);

        assertThat(controller.create(saleId, request)).isEqualTo(response);
        verify(service).create(saleId, request);
    }

    @Test
    void requiresAdministratorAuthority() throws Exception {
        Method method = SaleReturnController.class.getDeclaredMethod("create", UUID.class, SaleReturnDtos.ReturnRequest.class);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
        assertThat(method.getAnnotation(ResponseStatus.class).value().value()).isEqualTo(201);
    }
}
