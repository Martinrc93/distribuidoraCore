package com.distribuidora.document;

import com.distribuidora.document.application.SaleDocumentService;
import com.distribuidora.document.rendering.SaleDocumentModel;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DocumentReadOnlyRegressionTest {
    @Test
    void loadingDocumentUsesOnlyReadQueriesAndLeavesPersistedStateUntouched() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID saleId = UUID.randomUUID();
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of(
            "sale_id", saleId,
            "sale_number", "SAL-READ-ONLY",
            "sale_date", Timestamp.valueOf(LocalDateTime.of(2026, 9, 19, 10, 15)),
            "customer_name", "Customer",
            "seller_name", "Seller",
            "total", new BigDecimal("30.00"),
            "paid", new BigDecimal("20.00")));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());

        SaleDocumentModel document = new SaleDocumentService(jdbc).load(UUID.randomUUID());

        assertThat(document.saleNumber()).isEqualTo("SAL-READ-ONLY");
        assertThat(document.pendingBalance()).isEqualByComparingTo("10.00");
        verify(jdbc).queryForMap(anyString(), any(Object[].class));
        verify(jdbc, org.mockito.Mockito.times(2))
            .query(anyString(), any(RowMapper.class), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }
}
