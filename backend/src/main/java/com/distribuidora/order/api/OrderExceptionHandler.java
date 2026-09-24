package com.distribuidora.order.api;

import com.distribuidora.order.application.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OrderConfirmationController.class)
public class OrderExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict(
        IdempotencyConflictException exception,
        HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Conflict");
        problem.setProperty("code", "IDEMPOTENCY_CONFLICT");
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
