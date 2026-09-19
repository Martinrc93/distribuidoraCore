package com.distribuidora.document;

import com.distribuidora.document.application.SaleDocumentConflictException;
import com.distribuidora.document.application.SaleDocumentNotFoundException;
import com.distribuidora.document.application.SaleDocumentService;
import com.distribuidora.document.rendering.SaleDocumentModel;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SaleDocumentServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SaleDocumentService service = new SaleDocumentService(jdbc);

    @Test
    void mapsCompletePersistedSnapshotWithoutRecalculatingOrCallingPricing() {
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "sale_id", saleId, "sale_number", "SAL-1", "sale_date", Timestamp.valueOf(LocalDateTime.of(2026, 9, 19, 10, 15)),
                "customer_name", "Customer", "seller_name", "Seller", "total", new BigDecimal("30.1234"),
                "paid", new BigDecimal("20.0123")));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            RowMapper<?> mapper = invocation.getArgument(1, RowMapper.class);
            if (sql.contains("sale_items")) {
                return List.of(mapper.mapRow(row(Map.of(
                        "product_name", "Second", "quantity", new BigDecimal("2.0000"), "unit_price", new BigDecimal("5.1234"),
                        "line_discount_percent", new BigDecimal("1.2500"), "line_total", new BigDecimal("10.1234"), "id", UUID.randomUUID())), 0),
                    mapper.mapRow(row(Map.of(
                        "product_name", "First", "quantity", new BigDecimal("1.0000"), "unit_price", new BigDecimal("20.0000"),
                        "line_discount_percent", new BigDecimal("0.0000"), "line_total", new BigDecimal("20.0000"), "id", UUID.randomUUID())), 1));
            }
            return List.of(mapper.mapRow(row(Map.of("method", "CASH", "amount", new BigDecimal("20.0123"), "id", UUID.randomUUID())), 0));
        });

        SaleDocumentModel model = service.load(orderId);

        assertThat(model.saleNumber()).isEqualTo("SAL-1");
        assertThat(model.saleDate()).isEqualTo(LocalDate.of(2026, 9, 19));
        assertThat(model.customerName()).isEqualTo("Customer");
        assertThat(model.sellerName()).isEqualTo("Seller");
        assertThat(model.lines()).extracting(SaleDocumentModel.Line::description)
                .containsExactly("Second", "First");
        assertThat(model.lines().get(0).discount()).isEqualByComparingTo("1.2500");
        assertThat(model.payments()).extracting(SaleDocumentModel.Payment::method).containsExactly("CASH");
        assertThat(model.total()).isEqualByComparingTo("30.1234");
        assertThat(model.paid()).isEqualByComparingTo("20.0123");
        verify(jdbc).queryForMap(anyString(), any(Object[].class));
        verify(jdbc, org.mockito.Mockito.times(2)).query(anyString(), any(RowMapper.class), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void doesNotCallPricingOrCalculationServicesBecauseItOnlyReadsPersistedValues() {
        UUID orderId = UUID.randomUUID();
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
                "sale_id", UUID.randomUUID(), "sale_number", "SAL-2", "sale_date", Timestamp.valueOf("2026-09-19 00:00:00"),
                "customer_name", "Customer", "seller_name", "Seller", "total", BigDecimal.TEN, "paid", BigDecimal.ZERO));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        service.load(orderId);

        verify(jdbc).queryForMap(anyString(), any(Object[].class));
        verify(jdbc, org.mockito.Mockito.times(2)).query(anyString(), any(RowMapper.class), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void throwsNotFoundWhenOrderDoesNotExist() {
        UUID orderId = UUID.randomUUID();
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> service.load(orderId))
                .isInstanceOf(SaleDocumentNotFoundException.class);
    }

    @Test
    void throwsConflictWhenOrderExistsWithoutSale() {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> header = new HashMap<>();
        header.put("sale_id", null);
        header.put("sale_number", null);
        header.put("sale_date", null);
        header.put("customer_name", "Customer");
        header.put("seller_name", "Seller");
        header.put("total", BigDecimal.TEN);
        header.put("paid", BigDecimal.ZERO);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(header);

        assertThatThrownBy(() -> service.load(orderId))
                .isInstanceOf(SaleDocumentConflictException.class);
    }

    private ResultSet row(Map<String, Object> values) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof UUID uuid) {
                when(resultSet.getObject(entry.getKey(), UUID.class)).thenReturn(uuid);
            } else if (value instanceof BigDecimal decimal) {
                when(resultSet.getBigDecimal(entry.getKey())).thenReturn(decimal);
            } else {
                when(resultSet.getString(entry.getKey())).thenReturn((String) value);
            }
        }
        return resultSet;
    }
}
