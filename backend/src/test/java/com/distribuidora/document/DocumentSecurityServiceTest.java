package com.distribuidora.document;

import com.distribuidora.document.application.AccountStatementDocumentService;
import com.distribuidora.document.application.DocumentNotFoundException;
import com.distribuidora.document.application.PriceListDocumentService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentSecurityServiceTest {

    @Test
    void accountStatementChecksCustomerScopeBeforeReadingCustomerRecord() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserAccess currentUser = mock(CurrentUserAccess.class);
        UUID foreignCustomerId = UUID.randomUUID();
        when(currentUser.isAdmin()).thenReturn(false);
        doThrow(new EmptyResultDataAccessException(1)).when(currentUser).requireCustomerAccess(foreignCustomerId);

        AccountStatementDocumentService service = new AccountStatementDocumentService(jdbc, currentUser);

        assertThatThrownBy(() -> service.load(foreignCustomerId))
            .isInstanceOf(DocumentNotFoundException.class)
            .hasMessage("Cliente inexistente");
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void priceListDocumentHidesInactiveListFromNonAdminUsers() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserAccess currentUser = mock(CurrentUserAccess.class);
        UUID listId = UUID.randomUUID();
        when(currentUser.isAdmin()).thenReturn(false);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), eq(listId)))
            .thenAnswer(invocation -> {
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getString("name")).thenReturn("Inactiva");
                when(rs.getString("code")).thenReturn("INACT");
                when(rs.getString("status")).thenReturn("INACTIVE");
                return ((RowMapper<?>) invocation.getArgument(1)).mapRow(rs, 0);
            });

        PriceListDocumentService service = new PriceListDocumentService(jdbc, currentUser);

        assertThatThrownBy(() -> service.load(listId, null, null, null))
            .isInstanceOf(DocumentNotFoundException.class)
            .hasMessage("Lista de precios inexistente");
        verify(currentUser).isAdmin();
    }
}
