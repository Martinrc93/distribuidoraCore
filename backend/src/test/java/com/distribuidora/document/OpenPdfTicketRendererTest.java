package com.distribuidora.document;

import com.distribuidora.document.rendering.OpenPdfTicketRenderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenPdfTicketRendererTest {
    @Test
    void rendersAn80MillimeterTicketPdfFromPersistedSaleSnapshot() {
        SaleDocumentModel model = new SaleDocumentModel("SAL-123", LocalDate.of(2026, 9, 24), "Cliente", "Vendedor",
            List.of(new SaleDocumentModel.Line("Producto", BigDecimal.ONE, new BigDecimal("10.0000"), BigDecimal.ZERO,
                new BigDecimal("10.0000"))), List.of(), new BigDecimal("10.0000"), BigDecimal.ZERO);

        byte[] pdf = new OpenPdfTicketRenderer().render(model);
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        assertThat(pdf.length).isGreaterThan(500);
    }
}
