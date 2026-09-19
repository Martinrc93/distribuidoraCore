package com.distribuidora.order;

import com.distribuidora.order.api.OrderConfirmationDtos;
import com.distribuidora.order.application.OrderCalculationService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderCalculationServiceTest {
    private final OrderCalculationService service = new OrderCalculationService();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void calculatesLineTotalWithHalfUpScaleFourDiscount() {
        OrderCalculationService.CalculatedLine line = line("2.0", "10.0000", "10");

        OrderCalculationService.OrderCalculation calculation = service.calculate(List.of(line), percent("0"));

        assertThat(calculation.lines()).singleElement().satisfies(calculated -> {
            assertThat(calculated.base()).isEqualByComparingTo("20.0000");
            assertThat(calculated.lineDiscount()).isEqualByComparingTo("2.0000");
            assertThat(calculated.lineTotal()).isEqualByComparingTo("18.0000");
        });
        assertThat(calculation.subtotal()).isEqualByComparingTo("20.0000");
        assertThat(calculation.lineDiscount()).isEqualByComparingTo("2.0000");
        assertThat(calculation.orderDiscount()).isEqualByComparingTo("0.0000");
        assertThat(calculation.total()).isEqualByComparingTo("18.0000");
        assertThat(calculation.paid()).isEqualByComparingTo("0.0000");
        assertThat(calculation.balance()).isEqualByComparingTo("18.0000");
    }

    @Test
    void appliesOrderDiscountAfterLineDiscountsWithScaleFourRounding() {
        List<OrderCalculationService.CalculatedLine> lines = List.of(
            line("1.0", "10.0050", "0"),
            line("3.0", "2.3333", "5")
        );

        OrderCalculationService.OrderCalculation calculation = service.calculate(lines, percent("5"));

        assertThat(calculation.subtotal()).isEqualByComparingTo("17.0049");
        assertThat(calculation.lineDiscount()).isEqualByComparingTo("0.3500");
        assertThat(calculation.orderDiscount()).isEqualByComparingTo("0.8327");
        assertThat(calculation.total()).isEqualByComparingTo("15.8222");
    }

    @Test
    void calculatesPaidAndBalanceAndRejectsPaymentsOverTotal() {
        List<OrderCalculationService.CalculatedLine> lines = List.of(line("2.0", "10", "0"));
        List<OrderConfirmationDtos.PaymentRequest> payments = List.of(
            new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("7.25")),
            new OrderConfirmationDtos.PaymentRequest("BANK_TRANSFER", new BigDecimal("2.75"))
        );

        OrderCalculationService.OrderCalculation calculation = service.calculate(lines, percent("0"), payments);

        assertThat(calculation.paid()).isEqualByComparingTo("10.0000");
        assertThat(calculation.balance()).isEqualByComparingTo("10.0000");

        assertThatThrownBy(() -> service.calculate(lines, percent("0"), List.of(
            new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("20.0001"))
        ))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void excludesCustomerAccountRowsFromMonetaryPaidButIncludesThemInOverpaymentValidation() {
        OrderCalculationService.OrderCalculation calculation = service.calculate(
            List.of(line("1.0", "10", "0")), percent("0"), List.of(
                new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("5")),
                new OrderConfirmationDtos.PaymentRequest("CUSTOMER_ACCOUNT", new BigDecimal("5"))));

        assertThat(calculation.paid()).isEqualByComparingTo("5.0000");
        assertThat(calculation.balance()).isEqualByComparingTo("5.0000");

        assertThatThrownBy(() -> service.calculate(
            List.of(line("1.0", "10", "0")), percent("0"), List.of(
                new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("5")),
                new OrderConfirmationDtos.PaymentRequest("CUSTOMER_ACCOUNT", new BigDecimal("10")))))
            .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "CHEQUE"})
    void rejectsUnsupportedPaymentMethods(String method) {
        OrderConfirmationDtos.PaymentRequest payment =
            new OrderConfirmationDtos.PaymentRequest(method, new BigDecimal("1"));

        assertThat(validator.validate(payment))
            .anyMatch(violation -> violation.getPropertyPath().toString().equals("method"));
    }

    @Test
    void rejectsZeroNegativeAndNonHalfUnitQuantities() {
        for (String quantity : List.of("0", "-0.5", "0.25")) {
            assertThatThrownBy(() -> service.calculate(
                List.of(line(quantity, "10", "0")), percent("0")))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDiscountsOutsideZeroToOneHundred() {
        for (String discount : List.of("-0.0001", "100.0001", "101")) {
            assertThatThrownBy(() -> service.calculate(
                List.of(line("1", "10", discount)), percent("0")))
                .isInstanceOf(IllegalArgumentException.class);
        }

        assertThatThrownBy(() -> service.calculate(
            List.of(line("1", "10", "0")), percent("100.0001")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRecordsExposeValidationMetadataForLaterControllerUse() throws Exception {
        assertThat(OrderConfirmationDtos.ConfirmationRequest.class
            .getDeclaredConstructor(String.class, UUID.class, UUID.class, List.class, BigDecimal.class, List.class))
            .isNotNull();
        assertThat(OrderConfirmationDtos.LineRequest.class.getRecordComponents()).hasSize(4);
        assertThat(OrderConfirmationDtos.PaymentRequest.class.getRecordComponents()).hasSize(2);
    }

    private OrderCalculationService.CalculatedLine line(String quantity, String price, String discount) {
        return new OrderCalculationService.CalculatedLine(
            UUID.randomUUID(), new BigDecimal(quantity), new BigDecimal(price), new BigDecimal(discount));
    }

    private BigDecimal percent(String value) {
        return new BigDecimal(value);
    }
}
