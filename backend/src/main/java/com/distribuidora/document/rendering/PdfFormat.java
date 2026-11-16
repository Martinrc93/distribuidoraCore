package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/** Shared formatting for the business documents (es-AR amounts and dates, fonts and safe rendering). */
public final class PdfFormat {
    public static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15);
    public static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    public static final Font NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 9);
    public static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private PdfFormat() { }

    public static String money(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR")));
        return "$ " + format.format(value == null ? BigDecimal.ZERO : value);
    }

    public static String date(LocalDate value) {
        return value == null ? "" : DATE.format(value);
    }

    public static PdfPCell cell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4f);
        return cell;
    }

    /** Opens the document, runs the body and always closes it; failures surface as IllegalStateException. */
    public static byte[] render(Document document, java.io.ByteArrayOutputStream output, Consumer<Document> body, String failureMessage) {
        try {
            org.openpdf.text.pdf.PdfWriter.getInstance(document, output);
            document.open();
            body.accept(document);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(failureMessage, exception);
        } finally {
            if (document.isOpen()) document.close();
        }
        return output.toByteArray();
    }
}
