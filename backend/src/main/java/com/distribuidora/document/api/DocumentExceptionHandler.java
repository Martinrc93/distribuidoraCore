package com.distribuidora.document.api;

import com.distribuidora.document.application.DocumentNotFoundException;
import com.distribuidora.document.application.SaleDocumentConflictException;
import com.distribuidora.document.application.SaleDocumentNotFoundException;
import com.distribuidora.document.rendering.DocumentRenderingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
    DocumentController.class, ExportDocumentController.class, CustomerPurchasesDocumentController.class,
    SupplierOrderDocumentController.class
})
public class DocumentExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(DocumentExceptionHandler.class);

    @ExceptionHandler(DocumentRenderingException.class)
    ResponseEntity<ProblemDetail> handleRenderingFailure(
        DocumentRenderingException exception, HttpServletRequest request, HttpServletResponse response
    ) {
        log.error("Unable to render PDF for {}", request.getRequestURI(), exception);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, null);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "DOCUMENT_RENDERING_FAILED",
            "No se pudo generar el PDF.", request);
    }

    @ExceptionHandler(SaleDocumentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(
        SaleDocumentNotFoundException exception,
        HttpServletRequest request
    ) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleDocumentNotFound(DocumentNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(SaleDocumentConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(
        SaleDocumentConflictException exception,
        HttpServletRequest request
    ) {
        return problem(HttpStatus.CONFLICT, "Conflict", "CONFLICT", exception.getMessage(), request);
    }

    private ResponseEntity<ProblemDetail> problem(
        HttpStatus status,
        String title,
        String code,
        String detail,
        HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
