package com.distribuidora.settings;

import com.distribuidora.settings.application.BusinessLogoImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BusinessLogoImageTest {
    @Test
    void normalizesPngAndJpegWhilePreservingAspectRatioAndPngTransparency() throws IOException {
        var png = normalize(768, 384, BufferedImage.TYPE_INT_ARGB, "png");
        assertThat(png.getWidth()).isEqualTo(384);
        assertThat(png.getHeight()).isEqualTo(192);
        assertThat(png.getRGB(0, 0) >>> 24).isZero();

        var jpeg = normalize(120, 60, BufferedImage.TYPE_INT_RGB, "jpeg");
        assertThat(jpeg.getWidth()).isEqualTo(120);
        assertThat(jpeg.getHeight()).isEqualTo(60);
    }

    @Test
    void rejectsOtherFormatsAndChecksSizeBeforeDecoding() throws IOException {
        assertThatIllegalArgumentException().isThrownBy(() -> BusinessLogoImage.normalize("!invalid"))
            .withMessageContaining("PNG o JPG");
        String svg = Base64.getEncoder().encodeToString("<svg/>".getBytes(StandardCharsets.UTF_8));
        assertThatIllegalArgumentException().isThrownBy(() -> BusinessLogoImage.normalize(svg))
            .withMessageContaining("PNG o JPG");
        String oversized = Base64.getEncoder().encodeToString(new byte[512 * 1024 + 1]);
        assertThatIllegalArgumentException().isThrownBy(() -> BusinessLogoImage.normalize(oversized))
            .withMessageContaining("512 KB");
        String wide = image(2049, 1, BufferedImage.TYPE_INT_ARGB, "png");
        assertThatIllegalArgumentException().isThrownBy(() -> BusinessLogoImage.normalize(wide))
            .withMessageContaining("2048");
    }

    private BufferedImage normalize(int width, int height, int type, String format) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(BusinessLogoImage.normalize(image(width, height, type, format))));
    }

    private String image(int width, int height, int type, String format) throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, type), format, output);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
