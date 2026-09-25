package com.distribuidora.identity.api;

import com.distribuidora.identity.application.InvalidActivationTokenException;
import com.distribuidora.identity.application.InvalidRefreshTokenException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AuthController.class)
public class IdentityExceptionHandler {

    @ExceptionHandler(InvalidRefreshTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidRefreshToken(
        InvalidRefreshTokenException exception,
        HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setTitle("Unauthorized");
        problem.setProperty("code", "INVALID_REFRESH_TOKEN");
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(InvalidActivationTokenException.class)
    ResponseEntity<ProblemDetail> handleInvalidActivationToken(
        InvalidActivationTokenException exception,
        HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid activation token");
        problem.setProperty("code", "INVALID_ACTIVATION_TOKEN");
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.badRequest().body(problem);
    }
}
