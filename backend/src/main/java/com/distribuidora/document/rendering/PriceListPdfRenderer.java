package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.Objects;

import static com.distribuidora.document.rendering.PdfFormat.BOLD;
import static com.distribuidora.document.rendering.PdfFormat.NORMAL;
import static com.distribuidora.document.rendering.PdfFormat.SMALL;
import static com.distribuidora.document.rendering.PdfFormat.TITLE;
import static com.distribuidora.document.rendering.PdfFormat.cell;

/** A4 price list grouped by category (rubro), for printing or offline consultation. */
@Component
public class PriceListPdfRenderer {
    public byte[] render(PriceListDocumentModel model) {
        Document document = new Document(PageSize.A4, 36f, 36f, 36f, 36f);
        return PdfFormat.render(document, new ByteArrayOutputStream(), doc -> {
            try {
                doc.add(new Paragraph("Lista de precios: " + model.listName(), TITLE));
                doc.add(new Paragraph(model.listCode() + " · Precios vigentes al " + PdfFormat.date(model.date()), NORMAL));
                if (model.filters() != null && !model.filters().isBlank()) doc.add(new Paragraph("Filtros: " + model.filters(), NORMAL));
                doc.add(new Paragraph(" ", SMALL));
                PdfPTable table = new PdfPTable(new float[]{75f, 25f});
                table.setWidthPercentage(100);
                table.setHeaderRows(1);
                table.addCell(cell("Producto", BOLD, Element.ALIGN_LEFT));
                table.addCell(cell("Precio", BOLD, Element.ALIGN_RIGHT));
                String category = null;
                for (PriceListDocumentModel.Line line : model.lines()) {
                    if (!Objects.equals(category, line.category())) {
                        category = line.category();
                        PdfPCell group = cell(category == null || category.isBlank() ? "Sin categoría" : category, BOLD, Element.ALIGN_LEFT);
                        group.setColspan(2);
                        group.setGrayFill(0.92f);
                        table.addCell(group);
                    }
                    table.addCell(cell(line.product(), NORMAL, Element.ALIGN_LEFT));
                    table.addCell(cell(PdfFormat.money(line.price()), NORMAL, Element.ALIGN_RIGHT));
                }
                doc.add(table);
                doc.add(new Paragraph(model.lines().size() + (model.lines().size() == 1 ? " producto" : " productos"), SMALL));
            } catch (DocumentException exception) {
                throw new IllegalStateException(exception);
            }
        }, "No se pudo generar la lista de precios");
    }
}
