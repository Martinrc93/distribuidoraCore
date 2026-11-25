package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.awt.Color;

import static com.distribuidora.document.rendering.PdfFormat.BOLD;
import static com.distribuidora.document.rendering.PdfFormat.NORMAL;
import static com.distribuidora.document.rendering.PdfFormat.SMALL;
import static com.distribuidora.document.rendering.PdfFormat.discount;
import static com.distribuidora.document.rendering.PdfFormat.money;
import static com.distribuidora.document.rendering.PdfFormat.number;

@Component
public class OpenPdfA4Renderer {

    private static final Font BANNER = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14f, Font.NORMAL, Color.WHITE);
    private static final Font BUSINESS_NAME = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20f);
    private static final Color LINE = new Color(210, 210, 210);
    private static final float SUMMARY_WIDTH_FRACTION = 0.60f;
    private static final float SUMMARY_GAP = 14f;

    public byte[] render(SaleDocumentModel model) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        render(model, output);
        return output.toByteArray();
    }

    public void render(SaleDocumentModel model, OutputStream output) {
        Document document;
        try {
            document = createDocument();
        } catch (RuntimeException failure) {
            throw new DocumentRenderingException("Unable to render sale document", failure);
        }
        PdfFormat.render(document, output, writer -> writer.setPageEvent(new PageNumberFooter()), (opened, writer) -> {
            addBusinessHeader(opened, model);
            opened.add(metadata(model));
            opened.add(products(model));
            addSummaryAtPageBottom(opened, writer, summary(model));
        }, "Unable to render sale document");
    }

    protected Document createDocument() {
        return new Document(PageSize.A4);
    }

    private static final class PageNumberFooter extends PdfPageEventHelper {
        private PdfTemplate totalPages;
        private int lastPage;

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalPages = writer.getDirectContent().createTemplate(32f, 12f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            lastPage = writer.getPageNumber();
            var prefix = new Phrase("Página " + lastPage + " de", SMALL);
            var font = SMALL.getCalculatedBaseFont(false);
            float prefixWidth = font.getWidthPoint(prefix.getContent(), SMALL.getSize());
            float digitWidth = font.getWidthPoint("0", SMALL.getSize());
            float spaceWidth = font.getWidthPoint(" ", SMALL.getSize());
            float center = (document.left() + document.right()) / 2f;
            float totalX = center + (prefixWidth + spaceWidth - digitWidth) / 2f;
            float baseline = document.bottom() / 2f;
            var canvas = writer.getDirectContent();
            ColumnText.showTextAligned(canvas, Element.ALIGN_RIGHT, prefix, totalX - spaceWidth, baseline, 0);
            canvas.addTemplate(totalPages, totalX, baseline);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            ColumnText.showTextAligned(totalPages, Element.ALIGN_LEFT,
                new Phrase(Integer.toString(lastPage), SMALL), 0, 0, 0);
        }
    }

    private void addBusinessHeader(Document document, SaleDocumentModel model) {
        if (model.businessLogo() != null) {
            var logo = PdfFormat.documentLogo(model.businessLogo(), 112f, 54f);
            logo.setSpacingAfter(6f);
            document.add(logo);
        }
        var business = model.businessProfile();
        if (!business.name().isBlank()) document.add(centered(business.name(), BUSINESS_NAME));
        if (!business.address().isBlank()) document.add(centered(business.address(), NORMAL));
        if (!business.phone().isBlank()) document.add(centered("Tel. " + business.phone(), NORMAL));

        var banner = new PdfPTable(1);
        banner.setWidthPercentage(100);
        banner.setSpacingBefore(12f);
        var title = plainCell("COMPROBANTE DE VENTA", BANNER, Element.ALIGN_CENTER);
        title.setPaddingTop(8f);
        title.setPaddingBottom(8f);
        title.setBackgroundColor(Color.BLACK);
        banner.addCell(title);
        document.add(banner);
        document.add(centered("Documento no válido como factura", SMALL));
    }

    private Paragraph centered(String value, Font font) {
        var paragraph = new Paragraph(value, font);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        return paragraph;
    }

    private PdfPTable metadata(SaleDocumentModel model) {
        var table = new PdfPTable(new float[]{16f, 47f, 15f, 22f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(12f);
        metadataField(table, "Venta", model.saleNumber());
        metadataField(table, "Fecha", PdfFormat.date(model.saleDate()));
        metadataField(table, "Cliente", model.customerName());
        metadataField(table, "Vendedor", model.sellerName().isBlank() ? "Sin asignar" : model.sellerName());
        return table;
    }

    private void metadataField(PdfPTable table, String label, String value) {
        table.addCell(plainCell(label, BOLD, Element.ALIGN_LEFT));
        table.addCell(plainCell(value, NORMAL, Element.ALIGN_LEFT));
    }

    private PdfPTable products(SaleDocumentModel model) {
        var table = new PdfPTable(new float[]{32f, 9f, 15f, 9f, 16f, 19f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(14f);
        for (String caption : new String[]{"Descripción", "Cant.", "P. unitario", "Desc. %", "Descuento", "Importe final"}) {
            var heading = borderedCell(caption, BOLD, "Descripción".equals(caption) ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            heading.setGrayFill(0.93f);
            table.addCell(heading);
        }
        table.setHeaderRows(1);
        for (var line : model.lines()) {
            boolean hasDiscount = line.discount().signum() > 0;
            table.addCell(borderedCell(line.description(), NORMAL, Element.ALIGN_LEFT));
            table.addCell(borderedCell(number(line.quantity()), NORMAL, Element.ALIGN_RIGHT));
            table.addCell(borderedCell(money(line.unitPrice()), NORMAL, Element.ALIGN_RIGHT));
            table.addCell(borderedCell(hasDiscount ? number(line.discount()) + "%" : "-", NORMAL, Element.ALIGN_RIGHT));
            table.addCell(borderedCell(hasDiscount ? discount(line.discountAmount()) : "-", NORMAL, Element.ALIGN_RIGHT));
            table.addCell(borderedCell(money(line.subtotal()), BOLD, Element.ALIGN_RIGHT));
        }
        return table;
    }

    private void addSummaryAtPageBottom(Document document, PdfWriter writer, PdfPTable table) {
        float width = (document.right() - document.left()) * SUMMARY_WIDTH_FRACTION;
        table.setTotalWidth(width);
        float top = document.bottom() + table.getTotalHeight();
        if (writer.getVerticalPosition(true) < top + SUMMARY_GAP) {
            document.newPage();
        }
        table.writeSelectedRows(0, -1, document.right() - width, top, writer.getDirectContent());
    }

    private PdfPTable summary(SaleDocumentModel model) {
        var table = new PdfPTable(new float[]{58f, 42f});

        var totals = model.totals();
        summaryRow(table, "Cant. de artículos", number(totals.quantity()), NORMAL, false);
        summaryRow(table, "Subtotal", money(totals.gross()), NORMAL, false);
        summaryRow(table, "Descuentos", discount(totals.productDiscount()), NORMAL, false);
        if (model.orderDiscountPercent().signum() > 0) {
            summaryRow(table, "Desc. general " + number(model.orderDiscountPercent()) + "%",
                discount(totals.generalDiscount()), NORMAL, false);
        }
        summaryRow(table, "TOTAL", money(model.total()), PdfFormat.TITLE, true);
        if (model.paid().signum() > 0) {
            summaryRow(table, "Pagado", money(model.paid()), NORMAL, false);
            summaryRow(table, "Pendiente", money(model.pendingBalance()), BOLD, false);
        }
        return table;
    }

    private void summaryRow(PdfPTable table, String label, String value, Font font, boolean emphasized) {
        var left = plainCell(label, font, Element.ALIGN_LEFT);
        var right = plainCell(value, font, Element.ALIGN_RIGHT);
        if (emphasized) {
            left.setGrayFill(0.93f);
            right.setGrayFill(0.93f);
        }
        table.addCell(left);
        table.addCell(right);
    }

    private PdfPCell plainCell(String value, Font font, int alignment) {
        var cell = PdfFormat.cell(value, font, alignment);
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private PdfPCell borderedCell(String value, Font font, int alignment) {
        var cell = plainCell(value, font, alignment);
        cell.setPadding(6f);
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColor(LINE);
        return cell;
    }
}
