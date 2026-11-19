package com.distribuidora.customer.api;

import com.distribuidora.customer.application.ResellerConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = CustomerCommandController.class)
public class CustomerExceptionHandler {

    @ExceptionHandler(ResellerConflictException.class)
    ResponseEntity<ProblemDetail> handleResellerConflict(ResellerConflictException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Conflict");
        problem.setProperty("code", "RESELLER_CONFLICT");
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
