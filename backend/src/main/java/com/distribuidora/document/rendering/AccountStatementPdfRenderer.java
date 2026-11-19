package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

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
        Document document = new Document(PageSize.A4, 36f, 36f, 36f, 36f);
        return PdfFormat.render(document, new ByteArrayOutputStream(), doc -> {
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
                for (AccountStatementModel.Line line : model.lines()) {
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
        float height = Math.max(300f, 200f + model.lines().size() * 40f);
        Document document = new Document(new Rectangle(WIDTH_80_MM, height), 14f, 14f, 14f, 14f);
        return PdfFormat.render(document, new ByteArrayOutputStream(), doc -> {
            try {
                doc.add(new Paragraph("ESTADO DE DEUDA", BOLD));
                header(doc, model);
                for (AccountStatementModel.Line line : model.lines()) {
                    doc.add(new Paragraph(PdfFormat.date(line.date()) + "  " + line.saleNumber(), BOLD));
                    if (model.showCustomers()) doc.add(new Paragraph("Cliente " + line.customer(), SMALL));
                    doc.add(new Paragraph("Total " + PdfFormat.money(line.total()) + " · Pagado " + PdfFormat.money(line.paid()), SMALL));
                    doc.add(new Paragraph("Saldo " + PdfFormat.money(line.due()), NORMAL));
                }
                if (model.lines().isEmpty()) doc.add(new Paragraph("Sin saldo pendiente.", NORMAL));
                doc.add(new Paragraph("TOTAL PENDIENTE: " + PdfFormat.money(model.totalDue()), BOLD));
            } catch (DocumentException exception) {
                throw new IllegalStateException(exception);
            }
        }, "No se pudo generar el ticket de estado de deuda");
    }

    private void header(Document doc, AccountStatementModel model) throws DocumentException {
        doc.add(new Paragraph("Cliente: " + model.customerName(), NORMAL));
        if (model.customerTaxId() != null && !model.customerTaxId().isBlank()) doc.add(new Paragraph("CUIT/DNI: " + model.customerTaxId(), NORMAL));
        if (model.customerAddress() != null && !model.customerAddress().isBlank()) doc.add(new Paragraph("Dirección: " + model.customerAddress(), NORMAL));
        doc.add(new Paragraph("Fecha: " + PdfFormat.date(model.date()), NORMAL));
    }
}
