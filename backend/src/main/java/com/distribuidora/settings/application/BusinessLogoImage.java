package com.distribuidora.settings.application;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public final class BusinessLogoImage {
    private static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_ENCODED_LENGTH = ((MAX_BYTES + 2) / 3) * 4;
    private static final int MAX_INPUT_SIDE = 2048;
    private static final int MAX_OUTPUT_SIDE = 384;

    private BusinessLogoImage() { }

    public static byte[] normalize(String content) {
        if (content == null || content.isBlank()) throw invalidImage(null);
        if (content.length() > MAX_ENCODED_LENGTH) throw tooLarge();
        byte[] source;
        try {
            source = Base64.getDecoder().decode(content);
        } catch (IllegalArgumentException exception) {
            throw invalidImage(exception);
        }
        if (source.length > MAX_BYTES) throw tooLarge();

        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage(null);
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw invalidImage(null);
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_INPUT_SIDE || height > MAX_INPUT_SIDE) {
                    throw new InvalidLogoException("El logo debe medir como máximo 2048 × 2048 píxeles.", null);
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw invalidImage(null);
                return png(decoded, width, height);
            } finally {
                reader.dispose();
            }
        } catch (InvalidLogoException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalidImage(exception);
        }
    }

    private static byte[] png(BufferedImage source, int width, int height) throws IOException {
        double scale = Math.min(1, (double) MAX_OUTPUT_SIDE / Math.max(width, height));
        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        var image = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
        } finally {
            graphics.dispose();
        }
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder unavailable");
        return output.toByteArray();
    }

    private static IllegalArgumentException tooLarge() {
        return new IllegalArgumentException("El logo debe pesar como máximo 512 KB.");
    }

    private static IllegalArgumentException invalidImage(Exception cause) {
        return new InvalidLogoException("Seleccioná una imagen PNG o JPG válida.", cause);
    }

    private static final class InvalidLogoException extends IllegalArgumentException {
        private InvalidLogoException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
