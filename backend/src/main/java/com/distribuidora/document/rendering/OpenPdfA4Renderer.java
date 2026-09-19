package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

@Component
public class OpenPdfA4Renderer {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    public byte[] render(SaleDocumentModel model) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = null;
        RuntimeException failure = null;
        try {
            document = createDocument();
            PdfWriter.getInstance(document, output);
            document.open();

            document.add(new Paragraph("SALE DOCUMENT"));
            document.add(new Paragraph("Sale: " + model.saleNumber()));
            document.add(new Paragraph("Date: " + DATE_FORMAT.format(model.saleDate())));
            document.add(new Paragraph("Customer: " + model.customerName()));
            document.add(new Paragraph("Seller: " + model.sellerName()));

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
                lines.addCell(decimal(line.unitPrice()));
                lines.addCell(decimal(line.discount()));
                lines.addCell(decimal(line.subtotal()));
            }
            document.add(lines);

            document.add(new Paragraph("Total: " + decimal(model.total())));
            document.add(new Paragraph("Paid: " + decimal(model.paid())));
            document.add(new Paragraph("Pending: " + decimal(model.pendingBalance())));
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
        }
        if (failure != null) {
            throw new IllegalStateException("Unable to render sale document", failure);
        }
        return output.toByteArray();
    }

    protected Document createDocument() {
        return new Document(PageSize.A4);
    }

    private String decimal(BigDecimal value) {
        return value.toPlainString();
    }
}
