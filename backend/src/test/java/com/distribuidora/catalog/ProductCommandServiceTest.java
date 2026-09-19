package com.distribuidora.catalog;

import com.distribuidora.catalog.application.ProductCommandService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductCommandServiceTest {
    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> ProductCommandService.validate(
            new ProductCommandService.ProductInput("SKU-1", "Producto", "Bebidas", "Unidad", BigDecimal.ONE, BigDecimal.valueOf(-1))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
