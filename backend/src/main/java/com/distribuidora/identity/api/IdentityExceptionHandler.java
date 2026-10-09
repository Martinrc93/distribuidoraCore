package com.distribuidora.identity.api;

import com.distribuidora.identity.application.InvalidActivationTokenException;
import com.distribuidora.identity.application.InvalidRefreshTokenException;
import com.distribuidora.identity.application.InvitationEmailUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AuthController.class, UserAdminController.class})
public class IdentityExceptionHandler {

    @ExceptionHandler(InvitationEmailUnavailableException.class)
    ResponseEntity<ProblemDetail> handleInvitationUnavailable(
        InvitationEmailUnavailableException exception, HttpServletRequest request
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Email unavailable");
        problem.setProperty("code", "INVITATION_EMAIL_UNAVAILABLE");
        problem.setProperty("instance", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

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
