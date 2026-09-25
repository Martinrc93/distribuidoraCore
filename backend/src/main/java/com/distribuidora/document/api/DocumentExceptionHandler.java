package com.distribuidora.document.api;

import com.distribuidora.document.application.SaleDocumentConflictException;
import com.distribuidora.document.application.SaleDocumentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentExceptionHandler {

    @ExceptionHandler(SaleDocumentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(
        SaleDocumentNotFoundException exception,
        HttpServletRequest request
    ) {
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
        return ResponseEntity.status(status).body(problem);
    }
}
