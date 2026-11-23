package com.distribuidora.document;

import com.distribuidora.document.rendering.OpenPdfTicketRenderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import com.distribuidora.settings.application.BusinessProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenPdfTicketRendererTest {
    @ParameterizedTest
    @CsvSource({"400.0000, '400,00', '600,00'", "1000.0000, '1.000,00', '0,00'"})
    void showsPaidAndPendingWhenThereIsAPartialOrFullPayment(String paid, String expectedPaid, String expectedPending) throws Exception {
        SaleDocumentModel model = new SaleDocumentModel("SAL-127", LocalDate.of(2026, 11, 23), "Cliente", "Vendedor",
            List.of(new SaleDocumentModel.Line("Producto", BigDecimal.ONE, new BigDecimal("1000"),
                BigDecimal.ZERO, new BigDecimal("1000"))), List.of(), new BigDecimal("1000"), new BigDecimal(paid));

        try (PdfReader reader = new PdfReader(new OpenPdfTicketRenderer().render(model))) {
            assertThat(text(reader)).contains(
                "TOTAL $ 1.000,00", "PAGADO $ " + expectedPaid, "PENDIENTE $ " + expectedPending);
        }
    }

    @Test
    void formatsThousandsAndDecimalsWithoutTrailingZerosInQuantities() throws Exception {
        SaleDocumentModel model = new SaleDocumentModel("SAL-126", LocalDate.of(2026, 11, 23), "Cliente", "Vendedor",
            List.of(new SaleDocumentModel.Line("Producto", new BigDecimal("1250.5000"),
                new BigDecimal("1234.5000"), BigDecimal.ZERO, new BigDecimal("1543742.2500"))),
            List.of(), new BigDecimal("1543742.2500"), BigDecimal.ZERO);

        try (PdfReader reader = new PdfReader(new OpenPdfTicketRenderer().render(model))) {
            assertThat(text(reader)).contains(
                "1.250,5 x $ 1.234,50", "TOTAL $ 1.543.742,25")
                .doesNotContain("1250.5000", "1234.5000", "1543742.2500");
        }
    }

    @Test
    void showsQuantityUnitPriceAndDiscountedAmountForEachProduct() throws Exception {
        SaleDocumentModel model = new SaleDocumentModel("SAL-124", LocalDate.of(2026, 11, 23), "Cliente", "Vendedor",
            List.of(new SaleDocumentModel.Line("Jabón en polvo 800 g", new BigDecimal("2.5000"),
                new BigDecimal("1000.0000"), new BigDecimal("10.0000"), new BigDecimal("2250.0000"))),
            List.of(), new BigDecimal("2250.0000"), BigDecimal.ZERO);

        try (PdfReader reader = new PdfReader(new OpenPdfTicketRenderer().render(model))) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(reader.getPageSize(1).getWidth()).isEqualTo(226.77f);
            assertThat(text(reader)).contains(
                "Jabón en polvo 800 g", "2,5 x $ 1.000,00", "$ 2.500,00", "TOTAL $ 2.250,00")
                .containsPattern("Desc\\. 10% ?-\\$ 250,00")
                .containsPattern("Importe C/Desc ?\\$ 2\\.250,00");
        }
    }

    @Test
    void showsThePersistedGeneralDiscountSeparatelyFromProductDiscounts() throws Exception {
        SaleDocumentModel model = new SaleDocumentModel("010600007", LocalDate.of(2026, 11, 23), "Almacén Avenida #058", "Vendedor",
            List.of(new SaleDocumentModel.Line("Ala Jabón en Polvo Baja Espuma 800g", BigDecimal.ONE,
                new BigDecimal("10219.0000"), BigDecimal.ZERO, new BigDecimal("10219.0000"))),
            List.of(), new BigDecimal("9708.0500"), BigDecimal.ZERO, new BigDecimal("5.0000"),
            new BusinessProfile("Distribuidora Central", "Av. Ejemplo 1234, Morón", "(011) 0000-0000"));

        try (PdfReader reader = new PdfReader(new OpenPdfTicketRenderer().render(model))) {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(text(reader)).contains(
                "Distribuidora Central", "Av. Ejemplo 1234, Morón", "Tel. (011) 0000-0000",
                "Fecha 23/11/2026 Cliente", "Vendedor Vendedor", "1 x $ 10.219,00",
                "Subtotal $ 10.219,00 Descuentos $ 0,00 Desc. general 5%")
                .containsPattern("Desc\\. general 5% ?-\\$ 510,95 TOTAL \\$ 9\\.708,05")
                .doesNotContain("Importe C/Desc", "Desc. 0%", "PAGADO", "PENDIENTE");
        }
    }

    @Test
    void fitsWrappedProductNamesAndFullyDiscountedItemsWithoutLosingTheTotals() throws Exception {
        List<SaleDocumentModel.Line> lines = java.util.stream.IntStream.rangeClosed(1, 40)
            .mapToObj(i -> new SaleDocumentModel.Line("Producto " + i + " con nombre largo, presentación familiar y descripción extendida",
                BigDecimal.ONE, new BigDecimal("12345.6789"), new BigDecimal("100.0000"), BigDecimal.ZERO))
            .toList();
        SaleDocumentModel model = new SaleDocumentModel("SAL-125", LocalDate.of(2026, 11, 23), "Cliente", "Vendedor",
            lines, List.of(), BigDecimal.ZERO, BigDecimal.ZERO);

        try (PdfReader reader = new PdfReader(new OpenPdfTicketRenderer().render(model))) {
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                assertThat(reader.getPageSize(page).getWidth()).isEqualTo(226.77f);
                text.append(new PdfTextExtractor(reader).getTextFromPage(page));
            }
            for (int i = 1; i <= 40; i++) assertThat(text).contains("Producto " + i + " con nombre largo");
            assertThat(text.toString().replaceAll("\\s+", " ")).contains("Desc. 100%", "TOTAL $ 0,00")
                .containsPattern("Importe C/Desc ?\\$ 0,00");
        }
    }

    @Test
    void rendersAn80MillimeterTicketPdfFromPersistedSaleSnapshot() {
        SaleDocumentModel model = new SaleDocumentModel("SAL-123", LocalDate.of(2026, 9, 24), "Cliente", "Vendedor",
            List.of(new SaleDocumentModel.Line("Producto", BigDecimal.ONE, new BigDecimal("10.0000"), BigDecimal.ZERO,
                new BigDecimal("10.0000"))), List.of(), new BigDecimal("10.0000"), BigDecimal.ZERO);

        byte[] pdf = new OpenPdfTicketRenderer().render(model);
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        assertThat(pdf.length).isGreaterThan(500);
    }

    private String text(PdfReader reader) throws Exception {
        return new PdfTextExtractor(reader).getTextFromPage(1).replaceAll("\\s+", " ");
    }
}
