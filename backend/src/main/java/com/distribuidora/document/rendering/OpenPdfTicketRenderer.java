package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import static com.distribuidora.document.rendering.PdfFormat.discount;
import static com.distribuidora.document.rendering.PdfFormat.money;
import static com.distribuidora.document.rendering.PdfFormat.number;

@Component
public class OpenPdfTicketRenderer {
    private static final float WIDTH_80_MM = 226.77f;
    private static final float MARGIN = 11f;
    private static final Font NORMAL = FontFactory.getFont(FontFactory.COURIER, 8.5f);
    private static final Font BOLD = FontFactory.getFont(FontFactory.COURIER_BOLD, 8.5f);
    private static final Font SMALL = FontFactory.getFont(FontFactory.COURIER, 7f);
    private static final Font BUSINESS_NAME = FontFactory.getFont(FontFactory.COURIER_BOLD, 11f);
    private static final Font TITLE = FontFactory.getFont(FontFactory.COURIER_BOLD, 10f, Font.NORMAL, Color.WHITE);
    private static final Font TOTAL = FontFactory.getFont(FontFactory.COURIER_BOLD, 12f);

    public byte[] render(SaleDocumentModel model) {
        var output = new ByteArrayOutputStream();
        render(model, output);
        return output.toByteArray();
    }

    public void render(SaleDocumentModel model, OutputStream output) {
        PdfPTable content = new PdfPTable(new float[]{52f, 48f});
        content.setTotalWidth(WIDTH_80_MM - MARGIN * 2);
        content.setLockedWidth(true);

        if (model.businessLogo() != null) {
            var logo = PdfFormat.documentLogo(model.businessLogo(), 104f, 42f);
            var logoCell = new PdfPCell(logo, false);
            logoCell.setColspan(2);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setPadding(0f);
            logoCell.setPaddingBottom(6f);
            logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            content.addCell(logoCell);
        }
        var business = model.businessProfile();
        if (!business.name().isBlank()) centered(content, business.name(), BUSINESS_NAME, 2f);
        if (!business.address().isBlank()) centered(content, business.address(), NORMAL, 1f);
        if (!business.phone().isBlank()) centered(content, "Tel. " + business.phone(), NORMAL, 1f);

        rule(content, 0f, 4f);
        PdfPCell title = wide("COMPROBANTE DE VENTA", TITLE, Element.ALIGN_CENTER, 4f);
        title.setBackgroundColor(Color.BLACK);
        content.addCell(title);
        centered(content, "Documento no válido como factura", SMALL, 3f);
        rule(content, 0.4f, 2f);

        PdfPTable details = new PdfPTable(new float[]{25f, 75f});
        details.setWidthPercentage(100);
        row(details, "Venta", model.saleNumber(), NORMAL);
        row(details, "Fecha", PdfFormat.date(model.saleDate()), NORMAL);
        row(details, "Cliente", model.customerName(), NORMAL);
        row(details, "Vendedor", model.sellerName().isBlank() ? "Sin asignar" : model.sellerName(), NORMAL);
        PdfPCell metadata = wide("", NORMAL, Element.ALIGN_LEFT, 0f);
        metadata.addElement(details);
        content.addCell(metadata);

        rule(content, 0.6f, 3f);
        row(content, "DESCRIPCIÓN", "IMPORTE", BOLD);
        rule(content, 0.4f, 2f);

        for (SaleDocumentModel.Line line : model.lines()) {
            content.addCell(wide(line.description(), BOLD, Element.ALIGN_LEFT, 2f));
            row(content, number(line.quantity()) + " x " + money(line.unitPrice()), money(line.grossAmount()), NORMAL);
            if (line.discount().signum() > 0) {
                row(content, "Desc. " + number(line.discount()) + "%", discount(line.discountAmount()), NORMAL);
                row(content, "Importe C/Desc", money(line.subtotal()), BOLD);
            }
        }

        var totals = model.totals();
        rule(content, 0.6f, 3f);
        row(content, "Cant. de artículos", number(totals.quantity()), NORMAL);
        row(content, "Subtotal", money(totals.gross()), NORMAL);
        row(content, "Descuentos", discount(totals.productDiscount()), NORMAL);
        if (model.orderDiscountPercent().signum() > 0) {
            row(content, "Desc. general " + number(model.orderDiscountPercent()) + "%",
                discount(totals.generalDiscount()), NORMAL);
        }

        rule(content, 0.7f, 1f);
        rule(content, 0.7f, 2f);
        row(content, "TOTAL", money(model.total()), TOTAL);
        if (model.paid().signum() > 0) {
            row(content, "PAGADO", money(model.paid()), NORMAL);
            row(content, "PENDIENTE", money(model.pendingBalance()), BOLD);
        }
        rule(content, 0.7f, 2f);
        rule(content, 0.7f, 1f);

        // Measure wrapped text at the printable width before sizing the paper.
        content.calculateHeights(true);
        float height = Math.min(14400f, Math.max(180f, content.getTotalHeight() + 25f));
        Document document = new Document(new Rectangle(WIDTH_80_MM, height), MARGIN, MARGIN, 11f, 11f);
        PdfFormat.render(document, output, doc -> doc.add(content),
            "No se pudo generar el ticket");
    }

    private void row(PdfPTable table, String label, String value, Font font) {
        table.addCell(cell(label, font, Element.ALIGN_LEFT, 1f));
        table.addCell(cell(value, font, Element.ALIGN_RIGHT, 1f));
    }

    private void centered(PdfPTable table, String text, Font font, float padding) {
        table.addCell(wide(text, font, Element.ALIGN_CENTER, padding));
    }

    private void rule(PdfPTable table, float weight, float spacing) {
        PdfPCell rule = wide("", NORMAL, Element.ALIGN_LEFT, 0f);
        rule.setFixedHeight(spacing);
        if (weight > 0f) {
            rule.setBorder(Rectangle.BOTTOM);
            rule.setBorderWidth(weight);
        }
        table.addCell(rule);
    }

    private PdfPCell wide(String text, Font font, int alignment, float padding) {
        PdfPCell cell = cell(text, font, alignment, padding);
        cell.setColspan(2);
        return cell;
    }

    private PdfPCell cell(String text, Font font, int alignment, float padding) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0f);
        cell.setPaddingTop(padding);
        cell.setPaddingBottom(padding);
        cell.setHorizontalAlignment(alignment);
        cell.setUseAscender(true);
        cell.setUseDescender(true);
        return cell;
    }

}
