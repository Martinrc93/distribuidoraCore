package com.distribuidora.document.rendering;

import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfWriter;

import java.io.OutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Shared formatting for the business documents (es-AR amounts and dates, fonts and safe rendering). */
public final class PdfFormat {
    public static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15);
    public static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    public static final Font NORMAL = FontFactory.getFont(FontFactory.HELVETICA, 9);
    public static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 7.5f);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DecimalFormatSymbols AMOUNT_SYMBOLS = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-AR"));

    private PdfFormat() { }

    public static String money(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00", AMOUNT_SYMBOLS);
        return "$ " + format.format(value == null ? BigDecimal.ZERO : value);
    }

    public static String date(LocalDate value) {
        return value == null ? "" : DATE.format(value);
    }

    public static String number(BigDecimal value) {
        if (value == null) return "0";
        DecimalFormat format = new DecimalFormat("#,##0.####", AMOUNT_SYMBOLS);
        format.setMaximumFractionDigits(Math.max(0, value.scale()));
        return format.format(value);
    }

    public static String discount(BigDecimal value) {
        if (value == null) return money(BigDecimal.ZERO);
        return value.signum() > 0 ? "-" + money(value) : money(value.negate());
    }

    public static PdfPCell cell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(4f);
        return cell;
    }

    static Image documentLogo(byte[] png, float maxWidth, float maxHeight) {
        try {
            Image logo = Image.getInstance(png);
            logo.scaleToFit(maxWidth, maxHeight);
            logo.setAlignment(Element.ALIGN_CENTER);
            return logo;
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo incorporar el logo del comercio", exception);
        }
    }

    static void render(Document document, OutputStream output, Consumer<Document> body, String failureMessage) {
        render(document, output, writer -> { }, (opened, writer) -> body.accept(opened), failureMessage);
    }

    static void render(Document document, OutputStream output, Consumer<PdfWriter> setup,
                       BiConsumer<Document, PdfWriter> body, String failureMessage) {
        RuntimeException failure = null;
        try {
            PdfWriter writer = PdfWriter.getInstance(document, output);
            writer.setCloseStream(false);
            setup.accept(writer);
            document.open();
            body.accept(document, writer);
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            try {
                document.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else if (failure != closeFailure) failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw new DocumentRenderingException(failureMessage, failure);
    }
}
