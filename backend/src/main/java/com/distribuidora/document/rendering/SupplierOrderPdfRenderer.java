package com.distribuidora.document.rendering;

import com.distribuidora.purchasing.application.SupplierOrderService.OrderDocument;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

import static com.distribuidora.document.rendering.PdfFormat.BOLD;
import static com.distribuidora.document.rendering.PdfFormat.NORMAL;
import static com.distribuidora.document.rendering.PdfFormat.cell;

@Component
public class SupplierOrderPdfRenderer {
    public byte[] render(OrderDocument order) {
        Document document = new Document(PageSize.A4, 36f, 36f, 36f, 36f);
        return PdfFormat.render(document, new ByteArrayOutputStream(), doc -> {
            try {
                doc.add(new Paragraph("Proveedor: " + order.supplier(), NORMAL));
                doc.add(new Paragraph("Fecha: " + PdfFormat.date(order.date()), NORMAL));
                doc.add(lines(order));
            } catch (DocumentException exception) {
                throw new IllegalStateException(exception);
            }
        }, "No se pudo generar el PDF de la compra");
    }

    private PdfPTable lines(OrderDocument order) {
        var units = new DecimalFormat("#,##0.####", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR")));
        var table = new PdfPTable(new float[]{80f, 20f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(12f);
        table.setHeaderRows(1);
        table.setSplitLate(false);
        var product = cell("Producto", BOLD, Element.ALIGN_LEFT);
        var quantity = cell("Cantidad", BOLD, Element.ALIGN_RIGHT);
        product.setGrayFill(0.94f);
        quantity.setGrayFill(0.94f);
        table.addCell(product);
        table.addCell(quantity);
        for (var line : order.lines()) {
            table.addCell(cell(line.product(), NORMAL, Element.ALIGN_LEFT));
            table.addCell(cell(units.format(line.quantity()), NORMAL, Element.ALIGN_RIGHT));
        }
        return table;
    }
}
