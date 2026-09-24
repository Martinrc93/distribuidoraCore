package com.distribuidora.catalog.api;

import com.distribuidora.catalog.application.ProductPriceValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ProductCommandController.class)
public class CatalogExceptionHandler {

    @ExceptionHandler(ProductPriceValidationException.class)
    ResponseEntity<ProblemDetail> handleProductPriceValidation(
        ProductPriceValidationException exception,
        HttpServletRequest request
    ) {
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
}
