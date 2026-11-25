package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import static com.distribuidora.document.rendering.PdfFormat.BOLD;
import static com.distribuidora.document.rendering.PdfFormat.NORMAL;
import static com.distribuidora.document.rendering.PdfFormat.SMALL;
import static com.distribuidora.document.rendering.PdfFormat.TITLE;
import static com.distribuidora.document.rendering.PdfFormat.cell;

/** Customer debt statement in A4 and in an 80 mm ticket. */
@Component
public class AccountStatementPdfRenderer {
    private static final float WIDTH_80_MM = 226.77f;

    public byte[] renderA4(AccountStatementModel model) {
        var output = new ByteArrayOutputStream();
        renderA4(model, output);
        return output.toByteArray();
    }

    public void renderA4(AccountStatementModel model, OutputStream output) {
        Document document = new Document(PageSize.A4, 36f, 36f, 36f, 36f);
        PdfFormat.render(document, output, doc -> {
            try {
                doc.add(new Paragraph("Estado de deuda", TITLE));
                header(doc, model);
                doc.add(new Paragraph(" ", SMALL));
                // A reseller's statement also identifies the customer that received each order.
                boolean customers = model.showCustomers();
                PdfPTable table = new PdfPTable(customers
                    ? new float[]{12f, 18f, 15f, 19f, 12f, 12f, 12f}
                    : new float[]{14f, 22f, 22f, 14f, 14f, 14f});
                table.setWidthPercentage(100);
                table.setHeaderRows(1);
                String[] titles = customers
                    ? new String[]{"Fecha", "Venta", "Pedido", "Cliente", "Total", "Pagado", "Saldo"}
                    : new String[]{"Fecha", "Venta", "Pedido", "Total", "Pagado", "Saldo"};
                for (String title : titles) {
                    boolean left = title.equals("Fecha") || title.equals("Venta") || title.equals("Pedido") || title.equals("Cliente");
                    table.addCell(cell(title, BOLD, left ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT));
                }
                int rows = 0;
                table.setComplete(false);
                for (AccountStatementModel.Line line : model.lines()) {
                    if (rows > 0 && rows % 100 == 0) doc.add(table);
                    rows++;
                    table.addCell(cell(PdfFormat.date(line.date()), NORMAL, Element.ALIGN_LEFT));
                    table.addCell(cell(line.saleNumber(), NORMAL, Element.ALIGN_LEFT));
                    table.addCell(cell(line.orderNumber(), NORMAL, Element.ALIGN_LEFT));
                    if (customers) table.addCell(cell(line.customer(), NORMAL, Element.ALIGN_LEFT));
                    table.addCell(cell(PdfFormat.money(line.total()), NORMAL, Element.ALIGN_RIGHT));
                    table.addCell(cell(PdfFormat.money(line.paid()), NORMAL, Element.ALIGN_RIGHT));
                    table.addCell(cell(PdfFormat.money(line.due()), NORMAL, Element.ALIGN_RIGHT));
                }
                if (model.lines().isEmpty()) {
                    var empty = cell("El cliente no tiene saldo pendiente.", NORMAL, Element.ALIGN_LEFT);
                    empty.setColspan(titles.length);
                    table.addCell(empty);
                }
                table.setComplete(true);
                doc.add(table);
                Paragraph total = new Paragraph("Saldo total pendiente: " + PdfFormat.money(model.totalDue()), BOLD);
                total.setAlignment(Element.ALIGN_RIGHT);
                doc.add(total);
            } catch (DocumentException exception) {
                throw new IllegalStateException(exception);
            }
        }, "No se pudo generar el estado de deuda");
    }

    public byte[] renderTicket(AccountStatementModel model) {
        var output = new ByteArrayOutputStream();
        renderTicket(model, output);
        return output.toByteArray();
    }

    public void renderTicket(AccountStatementModel model, OutputStream output) {
        float margin = 14f;
        var table = new PdfPTable(1);
        table.setTotalWidth(WIDTH_80_MM - 2 * margin);
        table.setLockedWidth(true);
        table.setSplitLate(false);
        ticketLine(table, "ESTADO DE DEUDA", BOLD);
        ticketLine(table, "Cliente: " + model.customerName(), NORMAL);
        if (model.customerTaxId() != null && !model.customerTaxId().isBlank()) {
            ticketLine(table, "CUIT/DNI: " + model.customerTaxId(), NORMAL);
        }
        if (model.customerAddress() != null && !model.customerAddress().isBlank()) {
            ticketLine(table, "Dirección: " + model.customerAddress(), NORMAL);
        }
        ticketLine(table, "Fecha: " + PdfFormat.date(model.date()), NORMAL);
        for (AccountStatementModel.Line line : model.lines()) {
            ticketLine(table, PdfFormat.date(line.date()) + "  " + line.saleNumber(), BOLD);
            if (model.showCustomers()) ticketLine(table, "Cliente " + line.customer(), SMALL);
            ticketLine(table, "Total " + PdfFormat.money(line.total()) + " · Pagado " + PdfFormat.money(line.paid()), SMALL);
            ticketLine(table, "Saldo " + PdfFormat.money(line.due()), NORMAL);
        }
        if (model.lines().isEmpty()) ticketLine(table, "Sin saldo pendiente.", NORMAL);
        ticketLine(table, "TOTAL PENDIENTE: " + PdfFormat.money(model.totalDue()), BOLD);
        float height = Math.min(14400f, Math.max(180f, table.calculateHeights(true) + 2 * margin + 2f));
        var document = new Document(new Rectangle(WIDTH_80_MM, height), margin, margin, margin, margin);
        PdfFormat.render(document, output, doc -> doc.add(table), "No se pudo generar el ticket de estado de deuda");
    }

    private void ticketLine(PdfPTable table, String text, Font font) {
        var line = new PdfPCell(new Phrase(text, font));
        line.setLeading(0f, 1.25f);
        line.setPadding(1f);
        line.setBorder(Rectangle.NO_BORDER);
        table.addCell(line);
    }

    private void header(Document doc, AccountStatementModel model) throws DocumentException {
        doc.add(new Paragraph("Cliente: " + model.customerName(), NORMAL));
        if (model.customerTaxId() != null && !model.customerTaxId().isBlank()) doc.add(new Paragraph("CUIT/DNI: " + model.customerTaxId(), NORMAL));
        if (model.customerAddress() != null && !model.customerAddress().isBlank()) doc.add(new Paragraph("Dirección: " + model.customerAddress(), NORMAL));
        doc.add(new Paragraph("Fecha: " + PdfFormat.date(model.date()), NORMAL));
    }
}
