package com.distribuidora.document.api;

import com.distribuidora.document.rendering.DocumentRenderingException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.function.Consumer;

final class PdfDownload {
    private PdfDownload() { }

    static ResponseEntity<StreamingResponseBody> attachment(String filename, Consumer<OutputStream> render) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .body(response -> {
                // Finish rendering before committing the HTTP response, without keeping the PDF in heap.
                var file = Files.createTempFile("distribuidora-document-", ".pdf");
                try {
                    try (var output = Files.newOutputStream(file)) {
                        render.accept(output);
                    } catch (IOException | RuntimeException failure) {
                        throw new DocumentRenderingException("Unable to render PDF", failure);
                    }
                    Files.copy(file, response);
                } finally {
                    Files.deleteIfExists(file);
                }
            });
    }
}
