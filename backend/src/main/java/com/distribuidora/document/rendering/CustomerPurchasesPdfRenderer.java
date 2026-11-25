package com.distribuidora.document.rendering;

import com.distribuidora.dashboard.application.CustomerPurchasesReportService.Export;
import com.distribuidora.dashboard.application.CustomerPurchasesReportService.Purchase;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.util.Locale;

import static com.distribuidora.document.rendering.PdfFormat.BOLD;
import static com.distribuidora.document.rendering.PdfFormat.NORMAL;
import static com.distribuidora.document.rendering.PdfFormat.SMALL;
import static com.distribuidora.document.rendering.PdfFormat.TITLE;
import static com.distribuidora.document.rendering.PdfFormat.cell;
import com.distribuidora.shared.time.BusinessTime;

@Component
public class CustomerPurchasesPdfRenderer {
    private static final ZoneId BUSINESS_ZONE = BusinessTime.ZONE;

    public byte[] render(Export report) {
        var output = new ByteArrayOutputStream();
        render(report, output);
        return output.toByteArray();
    }

    public void render(Export report, OutputStream output) {
        var symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR"));
        var amount = new DecimalFormat("#,##0.00##", symbols);
        var units = new DecimalFormat("#,##0.####", symbols);
        Document document = new Document(PageSize.A4, 36f, 36f, 36f, 36f);
        PdfFormat.render(document, output, opened -> {
            try {
                var summary = report.summary();
                opened.add(new Paragraph("Compras del cliente", TITLE));
                opened.add(new Paragraph(summary.customer(), BOLD));
                opened.add(new Paragraph("Período: " + PdfFormat.date(summary.dateMin()) + " al " + PdfFormat.date(summary.dateMax()), NORMAL));
                opened.add(new Paragraph("Total comprado: $ " + amount.format(summary.totalAmount())
                    + "  |  Ventas: " + summary.salesCount(), BOLD));
                opened.add(new Paragraph("Importes con descuentos. Las devoluciones se informan en unidades.", SMALL));
                if (report.purchases().isEmpty()) {
                    opened.add(new Paragraph("No hay compras en el período seleccionado.", NORMAL));
                }
                for (Purchase purchase : report.purchases()) {
                    opened.add(table(purchase, amount, units));
                }
            } catch (DocumentException exception) {
                throw new IllegalStateException("No se pudo generar el reporte de compras", exception);
            }
        }, "No se pudo generar el reporte de compras");
    }

    private PdfPTable table(Purchase purchase, DecimalFormat amount, DecimalFormat units) {
        PdfPTable table = new PdfPTable(new float[]{44, 12, 14, 12, 18});
        table.setWidthPercentage(100);
        table.setSpacingBefore(12f);
        table.setHeaderRows(2);
        table.setSplitLate(false);
        String status = "DELIVERED".equals(purchase.status()) ? "Entregada" : "Confirmada";
        var sale = cell("Pedido " + purchase.orderNumber() + "  |  Venta " + purchase.saleNumber()
            + "  |  " + PdfFormat.date(purchase.date().atZone(BUSINESS_ZONE).toLocalDate())
            + "  |  " + status + "  |  $ " + amount.format(purchase.amount()), BOLD, Element.ALIGN_LEFT);
        sale.setColspan(5);
        table.addCell(sale);
        table.addCell(cell("Producto", BOLD, Element.ALIGN_LEFT));
        for (String name : new String[]{"Vendidas", "Devueltas", "Netas", "Importe"}) {
            table.addCell(cell(name, BOLD, Element.ALIGN_RIGHT));
        }
        for (var line : purchase.items()) {
            table.addCell(cell(line.product(), NORMAL, Element.ALIGN_LEFT));
            table.addCell(cell(units.format(line.soldUnits()), NORMAL, Element.ALIGN_RIGHT));
            table.addCell(cell(units.format(line.returnedUnits()), NORMAL, Element.ALIGN_RIGHT));
            table.addCell(cell(units.format(line.netUnits()), NORMAL, Element.ALIGN_RIGHT));
            table.addCell(cell("$ " + amount.format(line.amount()), NORMAL, Element.ALIGN_RIGHT));
        }
        if (purchase.items().isEmpty()) {
            var empty = cell("La venta no tiene líneas registradas.", SMALL, Element.ALIGN_LEFT);
            empty.setColspan(5);
            table.addCell(empty);
        }
        return table;
    }
}
