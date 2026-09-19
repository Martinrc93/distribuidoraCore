package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class OpenPdfA4Renderer {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    public byte[] render(SaleDocumentModel model) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);
        try {
            PdfWriter.getInstance(document, output);
            document.open();

            document.add(new Paragraph("SALE DOCUMENT"));
            document.add(new Paragraph("Sale: " + model.saleNumber()));
            document.add(new Paragraph("Date: " + DATE_FORMAT.format(model.saleDate())));
            document.add(new Paragraph("Customer: " + model.customerName()));
            document.add(new Paragraph("Seller: " + model.sellerName()));

            NumberFormat money = NumberFormat.getNumberInstance(Locale.ROOT);
            money.setMinimumFractionDigits(2);
            money.setMaximumFractionDigits(2);

            PdfPTable lines = new PdfPTable(5);
            lines.setWidthPercentage(100);
            lines.addCell("Description");
            lines.addCell("Quantity");
            lines.addCell("Unit price");
            lines.addCell("Discount");
            lines.addCell("Subtotal");
            for (SaleDocumentModel.Line line : model.lines()) {
                lines.addCell(line.description());
                lines.addCell(line.quantity().toPlainString());
                lines.addCell(money.format(line.unitPrice()));
                lines.addCell(money.format(line.discount()));
                lines.addCell(money.format(line.subtotal()));
            }
            document.add(lines);

            document.add(new Paragraph("Total: " + money.format(model.total())));
            document.add(new Paragraph("Paid: " + money.format(model.paid())));
            document.add(new Paragraph("Pending: " + money.format(model.pendingBalance())));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to render sale document", exception);
        } finally {
            document.close();
        }
        return output.toByteArray();
    }
}
