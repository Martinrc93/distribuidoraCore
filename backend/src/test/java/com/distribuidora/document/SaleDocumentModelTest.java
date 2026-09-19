package com.distribuidora.document;

import com.distribuidora.document.rendering.SaleDocumentModel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SaleDocumentModelTest {

    @Test
    void derivesPendingBalanceWithoutAllowingNegativeValues() {
        SaleDocumentModel model = modelWith(new BigDecimal("100000.00"), new BigDecimal("40000.00"));

        assertEquals(new BigDecimal("60000.00"), model.pendingBalance());
        assertEquals(BigDecimal.ZERO,
                modelWith(BigDecimal.valueOf(100), BigDecimal.valueOf(150)).pendingBalance());
    }

    @Test
    void keepsLineAndPaymentListsImmutable() {
        List<SaleDocumentModel.Line> lines = new ArrayList<>();
        lines.add(new SaleDocumentModel.Line("Product", BigDecimal.ONE, new BigDecimal("10.00"),
                BigDecimal.ZERO.setScale(2), new BigDecimal("10.00")));
        List<SaleDocumentModel.Payment> payments = new ArrayList<>();
        payments.add(new SaleDocumentModel.Payment("CASH", new BigDecimal("10.00")));
        SaleDocumentModel model = new SaleDocumentModel("V-1", LocalDate.of(2026, 9, 19), "Customer", "Seller",
                lines, payments, new BigDecimal("10.00"), new BigDecimal("10.00"));

        assertThrows(UnsupportedOperationException.class,
                () -> model.lines().add(lines.get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> model.payments().add(payments.get(0)));
        lines.clear();
        payments.clear();
        assertEquals(1, model.lines().size());
        assertEquals(1, model.payments().size());
    }

    @Test
    void preservesPersistedDecimalPrecisionAcrossDocumentValues() {
        SaleDocumentModel.Line line = new SaleDocumentModel.Line("Precise product", new BigDecimal("1.0000"),
                new BigDecimal("10.1234"), new BigDecimal("12.3456"), new BigDecimal("10.1111"));
        SaleDocumentModel.Payment payment = new SaleDocumentModel.Payment("CASH", new BigDecimal("40.0123"));
        SaleDocumentModel model = new SaleDocumentModel("V-2", LocalDate.of(2026, 9, 19), "Customer", "Seller",
                List.of(line), List.of(payment), new BigDecimal("100.1234"), new BigDecimal("40.0123"));

        assertEquals(new BigDecimal("10.1234"), model.lines().get(0).unitPrice());
        assertEquals(new BigDecimal("12.3456"), model.lines().get(0).discount());
        assertEquals(new BigDecimal("10.1111"), model.lines().get(0).subtotal());
        assertEquals(new BigDecimal("40.0123"), model.payments().get(0).amount());
        assertEquals(new BigDecimal("100.1234"), model.total());
        assertEquals(new BigDecimal("40.0123"), model.paid());
        assertEquals(new BigDecimal("60.1111"), model.pendingBalance());
        assertEquals(BigDecimal.ZERO, modelWith(new BigDecimal("100.1234"), new BigDecimal("100.1235"))
                .pendingBalance());
    }

    private SaleDocumentModel modelWith(BigDecimal total, BigDecimal paid) {
        return new SaleDocumentModel("V-1", LocalDate.of(2026, 9, 19), "Customer", "Seller",
                List.of(), List.of(), total, paid);
    }
}
