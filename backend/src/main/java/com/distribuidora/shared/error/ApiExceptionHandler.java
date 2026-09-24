package com.distribuidora.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.distribuidora.order.application.IdempotencyConflictException;
import com.distribuidora.document.application.SaleDocumentConflictException;
import com.distribuidora.document.application.SaleDocumentNotFoundException;

import com.distribuidora.catalog.application.ProductPriceValidationException;
import com.distribuidora.identity.application.InvalidRefreshTokenException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductPriceValidationException.class)
    ResponseEntity<ProblemDetail> handleProductPriceValidation(ProductPriceValidationException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            exception.getMessage()
        );
        problem.setTitle("Invalid product prices");
        problem.setProperty("code", "INVALID_PRODUCT_PRICES");
        problem.setProperty("instance", request.getRequestURI());
        problem.setProperty("affectedPriceLists", exception.getAffectedPriceLists());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> handleConflict(IllegalStateException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", "CONFLICT", "El recurso ya existe o no puede modificarse", request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", "IDEMPOTENCY_CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(SaleDocumentNotFoundException.class)
    ResponseEntity<ProblemDetail> handleSaleDocumentNotFound(SaleDocumentNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(SaleDocumentConflictException.class)
    ResponseEntity<ProblemDetail> handleSaleDocumentConflict(SaleDocumentConflictException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", "CONFLICT", exception.getMessage(), request);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    ResponseEntity<ProblemDetail> handleNotFound(EmptyResultDataAccessException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "NOT_FOUND", "El recurso no existe", request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> handleBadCredentials(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            "Credenciales inválidas"
        );
        problem.setTitle("Unauthorized");
        problem.setProperty("code", "INVALID_CREDENTIALS");
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidRefreshToken(InvalidRefreshTokenException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            exception.getMessage()
        );
        problem.setTitle("Unauthorized");
        problem.setProperty("code", "INVALID_REFRESH_TOKEN");
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "FORBIDDEN", "No tiene permisos para realizar esta operación", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
        MethodArgumentNotValidException exception,
        HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "La solicitud contiene datos inválidos"
        );
        problem.setTitle("Invalid request");
        problem.setProperty("code", "INVALID_REQUEST");
        problem.setProperty("instance", request.getRequestURI());
        problem.setProperty("fieldErrors", exception.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                error -> error.getField(),
                error -> error.getDefaultMessage() == null ? "Valor inválido" : error.getDefaultMessage(),
                (first, second) -> first
            )));
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "INVALID_REQUEST",
            "La solicitud contiene datos inválidos", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(
        Exception exception,
        HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Ocurrió un error inesperado"
        );
        problem.setTitle("Internal server error");
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.internalServerError().body(problem);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.status(status).body(problem);
    }
}
