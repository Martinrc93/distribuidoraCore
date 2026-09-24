package com.distribuidora.catalog;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.catalog.application.ProductCommandService.ProductInput;
import com.distribuidora.catalog.application.ProductCommandService.ProductPriceInput;
import com.distribuidora.catalog.application.ProductPriceValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductCommandServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private ProductCommandService service;

    @BeforeEach
    void setUp() {
        service = new ProductCommandService(jdbc, audit);
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> ProductCommandService.validate(
            new ProductInput("SKU-1", "Producto", "Bebidas", "Unidad", BigDecimal.ONE,
                List.of(new ProductPriceInput(UUID.randomUUID(), BigDecimal.valueOf(-1))))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsCostOnlyWhenNoActiveListPriceIsBelowIt() {
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where id = ?"), eq(Boolean.class), eq(productId))).thenReturn(true);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where sku = ? and id <> ?"), eq(Boolean.class), eq("SKU-1"), eq(productId))).thenReturn(false);

        UUID listId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(productId)))
            .thenReturn(List.of(new ProductCommandService.ActiveListPrice(listId, "GENERAL", BigDecimal.valueOf(150))));

        ProductInput input = new ProductInput("SKU-1", "Prod", "Cat", "Pres", BigDecimal.valueOf(120), List.of());
        service.update(productId, input);

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(updateSql.capture(), eq("SKU-1"), eq("Prod"), eq("Cat"), eq("Pres"), eq(BigDecimal.valueOf(120)), eq(productId));
        assertThat(updateSql.getValue()).contains("update catalog.products set sku = ?, name = ?, category = ?, presentation = ?, cost = ? where id = ?");
        assertThat(updateSql.getValue()).doesNotContain("price = ?");
    }

    @Test
    void rejectsCostWhenAffectedListPriceIsMissing() {
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where id = ?"), eq(Boolean.class), eq(productId))).thenReturn(true);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where sku = ? and id <> ?"), eq(Boolean.class), eq("SKU-1"), eq(productId))).thenReturn(false);

        UUID listId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(productId)))
            .thenReturn(List.of(new ProductCommandService.ActiveListPrice(listId, "GENERAL", BigDecimal.valueOf(100))));

        ProductInput input = new ProductInput("SKU-1", "Prod", "Cat", "Pres", BigDecimal.valueOf(110), List.of());

        assertThatThrownBy(() -> service.update(productId, input))
            .isInstanceOf(ProductPriceValidationException.class)
            .satisfies(ex -> {
                ProductPriceValidationException exception = (ProductPriceValidationException) ex;
                assertThat(exception.getAffectedPriceLists()).hasSize(1);
                assertThat(exception.getAffectedPriceLists().get(0).code()).isEqualTo("GENERAL");
                assertThat(exception.getAffectedPriceLists().get(0).priceListId()).isEqualTo(listId);
            });
    }

    @Test
    void rejectsAffectedPriceBelowNewCost() {
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where id = ?"), eq(Boolean.class), eq(productId))).thenReturn(true);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where sku = ? and id <> ?"), eq(Boolean.class), eq("SKU-1"), eq(productId))).thenReturn(false);

        UUID listId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(productId)))
            .thenReturn(List.of(new ProductCommandService.ActiveListPrice(listId, "GENERAL", BigDecimal.valueOf(100))));

        ProductInput input = new ProductInput("SKU-1", "Prod", "Cat", "Pres", BigDecimal.valueOf(110),
            List.of(new ProductPriceInput(listId, BigDecimal.valueOf(105))));

        assertThatThrownBy(() -> service.update(productId, input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no puede ser menor al costo");
    }

    @Test
    void updatesCostAndAllAffectedPricesTogether() {
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where id = ?"), eq(Boolean.class), eq(productId))).thenReturn(true);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where sku = ? and id <> ?"), eq(Boolean.class), eq("SKU-1"), eq(productId))).thenReturn(false);

        UUID list1Id = UUID.randomUUID();
        UUID list2Id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(productId)))
            .thenReturn(List.of(
                new ProductCommandService.ActiveListPrice(list1Id, "GENERAL", BigDecimal.valueOf(100)),
                new ProductCommandService.ActiveListPrice(list2Id, "LISTA_2", BigDecimal.valueOf(105))
            ));

        ProductInput input = new ProductInput("SKU-1", "Prod", "Cat", "Pres", BigDecimal.valueOf(120),
            List.of(
                new ProductPriceInput(list1Id, BigDecimal.valueOf(130)),
                new ProductPriceInput(list2Id, BigDecimal.valueOf(135))
            ));

        service.update(productId, input);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("update catalog.products set sku = ?"),
            eq("SKU-1"), eq("Prod"), eq("Cat"), eq("Pres"), eq(BigDecimal.valueOf(120)), eq(productId));

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("catalog.product_prices"),
            eq(list1Id), eq(productId), eq(BigDecimal.valueOf(130)), any(), any());
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("catalog.product_prices"),
            eq(list2Id), eq(productId), eq(BigDecimal.valueOf(135)), any(), any());
    }

    @Test
    void doesNotRequireUnaffectedListPrices() {
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where id = ?"), eq(Boolean.class), eq(productId))).thenReturn(true);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("where sku = ? and id <> ?"), eq(Boolean.class), eq("SKU-1"), eq(productId))).thenReturn(false);

        UUID list1Id = UUID.randomUUID();
        UUID list2Id = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(productId)))
            .thenReturn(List.of(
                new ProductCommandService.ActiveListPrice(list1Id, "GENERAL", BigDecimal.valueOf(100)),
                new ProductCommandService.ActiveListPrice(list2Id, "LISTA_2", BigDecimal.valueOf(200))
            ));

        ProductInput input = new ProductInput("SKU-1", "Prod", "Cat", "Pres", BigDecimal.valueOf(110),
            List.of(new ProductPriceInput(list1Id, BigDecimal.valueOf(125))));

        service.update(productId, input);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("update catalog.products set sku = ?"),
            eq("SKU-1"), eq("Prod"), eq("Cat"), eq("Pres"), eq(BigDecimal.valueOf(110)), eq(productId));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("catalog.product_prices"),
            eq(list1Id), eq(productId), eq(BigDecimal.valueOf(125)), any(), any());
    }

    @Test
    void validatesInitialPricesAgainstCost() {
        UUID listId = UUID.randomUUID();
        ProductInput input = new ProductInput("SKU-1", "Prod", "Cat", "Pres", BigDecimal.valueOf(100),
            List.of(new ProductPriceInput(listId, BigDecimal.valueOf(90))));

        assertThatThrownBy(() -> ProductCommandService.validate(input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no puede ser menor al costo");
    }

    @Test
    void createsProductWithActiveBrandAndCategoryReferences() {
        UUID categoryId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(categoryId))).thenReturn(List.of("Bebidas"));
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("catalog.brands"), eq(Boolean.class), eq(brandId)))
            .thenReturn(true);

        ProductInput input = new ProductInput("SKU-REF", "Producto", "Texto legado", "Unidad",
            BigDecimal.TEN, List.of(), categoryId, brandId);
        service.create(input);

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("category_id, brand_id"),
            any(UUID.class), eq("SKU-REF"), eq("Producto"), eq("Bebidas"), eq("Unidad"),
            eq(BigDecimal.TEN), any(), eq(categoryId), eq(brandId));
    }

    @Test
    void rejectsInactiveOrMissingCategoryReference() {
        UUID categoryId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(categoryId))).thenReturn(List.of());

        ProductInput input = new ProductInput("SKU-REF", "Producto", "Bebidas", "Unidad",
            BigDecimal.TEN, List.of(), categoryId, null);

        assertThatThrownBy(() -> service.create(input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("categoría seleccionada");
    }
}
