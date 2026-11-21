package com.distribuidora.document;

import com.distribuidora.document.api.ExportDocumentController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExportDocumentControllerTest {
    @Test
    void priceListsAreForOrderCreatorsAndStatementsForPaymentUsers() throws Exception {
        assertThat(ExportDocumentController.class.getDeclaredMethod("priceList", UUID.class, UUID.class, UUID.class, String.class)
            .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')");
        assertThat(ExportDocumentController.class.getDeclaredMethod("allPriceLists")
            .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
        assertThat(ExportDocumentController.class.getDeclaredMethod("statement", UUID.class, String.class)
            .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAnyAuthority('CUSTOMER_STATEMENT_EXPORT', 'ADMIN_ALL')");
    }

    @Test
    void rejectsUnknownStatementFormatsBeforeLoadingData() {
        var controller = new ExportDocumentController(null, null,
            mock(com.distribuidora.document.application.AccountStatementDocumentService.class), null);
        assertThatThrownBy(() -> controller.statement(UUID.randomUUID(), "pdf")).isInstanceOf(IllegalArgumentException.class);
    }
}
