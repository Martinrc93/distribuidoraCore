package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;

@Component
public class OpenPdfTicketRenderer {
    private static final float WIDTH_80_MM = 226.77f;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    public byte[] render(SaleDocumentModel model) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        float pageHeight = Math.max(360f, 250f + model.lines().size() * 42f);
        Document document = new Document(new Rectangle(WIDTH_80_MM, pageHeight), 18f, 18f, 16f, 16f);
        try {
            PdfWriter.getInstance(document, output);
            document.open();
            document.add(new Paragraph("COMPROBANTE DE VENTA"));
            document.add(new Paragraph("Venta: " + model.saleNumber()));
            document.add(new Paragraph("Fecha: " + DATE_FORMAT.format(model.saleDate())));
            document.add(new Paragraph("Cliente: " + model.customerName()));
            for (SaleDocumentModel.Line line : model.lines()) {
                document.add(new Paragraph(line.description() + "  x" + line.quantity().toPlainString()));
                document.add(new Paragraph("Unitario: " + line.unitPrice().toPlainString()
                    + "  Importe: " + line.subtotal().toPlainString()));
            }
            document.add(new Paragraph("TOTAL: " + model.total().toPlainString()));
            document.add(new Paragraph("PAGADO: " + model.paid().toPlainString()));
            document.add(new Paragraph("PENDIENTE: " + model.pendingBalance().toPlainString()));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el ticket", exception);
        } finally {
            if (document.isOpen()) document.close();
        }
        return output.toByteArray();
    }
}
