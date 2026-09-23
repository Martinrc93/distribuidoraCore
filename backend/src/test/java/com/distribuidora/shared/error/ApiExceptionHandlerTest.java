package com.distribuidora.shared.error;

import com.distribuidora.catalog.application.ProductPriceValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final ExceptionHandlerMethodResolver resolver =
        new ExceptionHandlerMethodResolver(ApiExceptionHandler.class);

    @Test
    void mapsAccessDeniedToForbiddenProblemDetail() throws Exception {
        AccessDeniedException exception = new AccessDeniedException("Missing STOCK_ADJUST authority");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/inventory/product/adjustments");

        Method method = resolver.resolveMethodByThrowable(exception);
        method.setAccessible(true);
        ResponseEntity<?> response = (ResponseEntity<?>) method.invoke(handler, exception, request);
        ProblemDetail problem = (ProblemDetail) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getTitle()).isEqualTo("Forbidden");
        assertThat(problem.getDetail()).isEqualTo("No tiene permisos para realizar esta operación");
        assertThat(problem.getProperties()).containsEntry("code", "FORBIDDEN");
        assertThat(problem.getProperties()).containsEntry("instance", request.getRequestURI());
    }

    @Test
    void mapsIllegalStateExceptionToConflictProblemDetail() throws Exception {
        ResponseEntity<?> response = invoke(new IllegalStateException("Business conflict"), "/api/orders/confirm");
        ProblemDetail problem = (ProblemDetail) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getProperties()).containsEntry("code", "CONFLICT");
    }

    @Test
    void mapsUnhandledDataIntegrityViolationToInternalServerError() throws Exception {
        ResponseEntity<?> response = invoke(
            new DataIntegrityViolationException("Unexpected database failure"),
            "/api/orders/confirm"
        );
        ProblemDetail problem = (ProblemDetail) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Internal server error");
        assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    }

    @Test
    void mapsBeanValidationFailureToBadRequestProblemDetail() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "quantity", "no puede ser nulo"));
        MethodParameter parameter = new MethodParameter(
            ApiExceptionHandlerTest.class.getDeclaredMethod("validationParameter", BigDecimal.class), 0);
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<?> response = invoke(exception, "/api/inventory/id/adjustments");
        ProblemDetail problem = (ProblemDetail) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Invalid request");
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
        assertThat(problem.getProperties()).containsKey("fieldErrors");
    }

    @Test
    void mapsMalformedJsonToBadRequestProblemDetail() throws Exception {
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException("Malformed JSON", (Throwable) null);

        ResponseEntity<?> response = invoke(exception, "/api/inventory/id/adjustments");
        ProblemDetail problem = (ProblemDetail) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
    }

    @Test
    void mapsInvalidUuidPathVariableToBadRequestProblemDetail() throws Exception {
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
            "not-a-uuid", UUID.class, "productId", null, new IllegalArgumentException("Invalid UUID"));

        ResponseEntity<?> response = invoke(exception, "/api/inventory/not-a-uuid/adjustments");
        ProblemDetail problem = (ProblemDetail) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem).isNotNull();
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
    }

    @Test
    void mapsProductPriceValidationExceptionToBadRequestWithAffectedPriceLists() throws Exception {
        UUID listId = UUID.randomUUID();
        ProductPriceValidationException exception = new ProductPriceValidationException(
            "El nuevo costo supera listas activas",
            java.util.List.of(new ProductPriceValidationException.AffectedPriceList(listId, "GENERAL", BigDecimal.valueOf(100)))
        );

        ResponseEntity<?> response = invoke(exception, "/api/products/123");
        ProblemDetail problem = (ProblemDetail) response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(problem).isNotNull();
        assertThat(problem.getTitle()).isEqualTo("Invalid product prices");
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_PRODUCT_PRICES");
        assertThat(problem.getProperties()).containsKey("affectedPriceLists");
        assertThat(problem.getProperties().get("affectedPriceLists")).isEqualTo(exception.getAffectedPriceLists());
    }

    private ResponseEntity<?> invoke(Exception exception, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        Method method = resolver.resolveMethodByThrowable(exception);
        method.setAccessible(true);
        if (exception instanceof MethodArgumentNotValidException) {
            return (ResponseEntity<?>) method.invoke(handler, exception, request);
        }
        return (ResponseEntity<?>) method.invoke(handler, exception, request);
    }

    @SuppressWarnings("unused")
    private void validationParameter(BigDecimal quantity) { }
}
