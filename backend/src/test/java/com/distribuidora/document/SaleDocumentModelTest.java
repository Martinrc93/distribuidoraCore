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
        SaleDocumentModel model = modelWith(BigDecimal.valueOf(100000), BigDecimal.valueOf(40000));

        assertEquals(new BigDecimal("60000.00"), model.pendingBalance());
        assertEquals(BigDecimal.ZERO.setScale(2),
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

    private SaleDocumentModel modelWith(BigDecimal total, BigDecimal paid) {
        return new SaleDocumentModel("V-1", LocalDate.of(2026, 9, 19), "Customer", "Seller",
                List.of(), List.of(), total, paid);
    }
}
