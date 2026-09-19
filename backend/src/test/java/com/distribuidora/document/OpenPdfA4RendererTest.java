package com.distribuidora.document;

import com.distribuidora.document.rendering.OpenPdfA4Renderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import org.junit.jupiter.api.Test;
import org.openpdf.text.PageSize;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenPdfA4RendererTest {

    private final OpenPdfA4Renderer renderer = new OpenPdfA4Renderer();

    @Test
    void rendersCompleteSaleSnapshotAsOneA4PageWithFixtureText() throws IOException {
        byte[] pdf = renderer.render(fixture());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");

        try (PdfReader reader = new PdfReader(pdf)) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(reader.getPageSize(1).getWidth()).isEqualTo(PageSize.A4.getWidth());
            assertThat(reader.getPageSize(1).getHeight()).isEqualTo(PageSize.A4.getHeight());

            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("SAL-2026-001", "Acme Retail", "Blue Widget", "Total",
                    "Paid", "Pending", "1,234.50", "500.00", "734.50");
        }
    }

    private SaleDocumentModel fixture() {
        return new SaleDocumentModel(
                "SAL-2026-001",
                LocalDate.of(2026, 9, 19),
                "Acme Retail",
                "Maria Seller",
                List.of(new SaleDocumentModel.Line(
                        "Blue Widget", new BigDecimal("2"), new BigDecimal("700.00"),
                        new BigDecimal("10.00"), new BigDecimal("1234.50"))),
                List.of(new SaleDocumentModel.Payment("CASH", new BigDecimal("500.00"))),
                new BigDecimal("1234.50"),
                new BigDecimal("500.00"));
    }
}
