package com.distribuidora.document;

import com.distribuidora.document.rendering.OpenPdfA4Renderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                    "Paid", "Pending", "1234.50", "500.00", "734.50");
        }
    }

    @Test
    void preservesScaleFourValuesWithoutLocaleGrouping() throws IOException {
        byte[] pdf = renderer.render(scaleFourFixture());

        try (PdfReader reader = new PdfReader(pdf)) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("1234.5000", "500.0123", "734.4877");
            assertThat(text).doesNotContain(",");
        }
    }

    @Test
    void translatesCloseFailureAndKeepsOriginalRenderFailure() {
        RuntimeException closeFailure = new RuntimeException("close failed");
        OpenPdfA4Renderer failingRenderer = new OpenPdfA4Renderer() {
            @Override
            protected Document createDocument() {
                return new Document(PageSize.A4) {
                    @Override
                    public void close() {
                        throw closeFailure;
                    }
                };
            }
        };

        assertThatThrownBy(() -> failingRenderer.render(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to render sale document")
                .hasCauseInstanceOf(NullPointerException.class)
                .satisfies(exception -> assertThat(exception.getCause().getSuppressed())
                        .containsExactly(closeFailure));
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

    private SaleDocumentModel scaleFourFixture() {
        return new SaleDocumentModel(
                "SAL-2026-004",
                LocalDate.of(2026, 9, 19),
                "Acme Retail",
                "Maria Seller",
                List.of(new SaleDocumentModel.Line(
                        "Blue Widget", new BigDecimal("2.0000"), new BigDecimal("700.0000"),
                        new BigDecimal("10.0000"), new BigDecimal("1234.5000"))),
                List.of(new SaleDocumentModel.Payment("CASH", new BigDecimal("500.0123"))),
                new BigDecimal("1234.5000"),
                new BigDecimal("500.0123"));
    }
}
