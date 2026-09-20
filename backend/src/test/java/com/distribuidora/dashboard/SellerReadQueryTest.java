package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.ReadQueryController;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerReadQueryTest {
    @Test
    void returnsSellerProjectionUsingParameterizedJoin() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ReadQueryService service = new ReadQueryService(jdbc);
        Map<String, Object> seller = Map.of("id", "seller-1", "displayName", "Lucía", "email", "lucia@test");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(seller));
        when(jdbc.queryForObject(eq("select count(*) from seller.seller_profiles sp join identity.users u on u.id = sp.user_id"), eq(Number.class)))
            .thenReturn(1);

        PageResponse<Map<String, Object>> response = service.sellers(0, 100);

        assertThat(response.content()).containsExactly(seller);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), parameters.capture());
        assertThat(sql.getValue()).contains(
            "sp.id",
            "sp.display_name as \"displayName\"",
            "u.email",
            "from seller.seller_profiles sp",
            "join identity.users u on u.id = sp.user_id",
            "order by sp.display_name",
            "limit ? offset ?"
        );
        assertThat(parameters.getValue()).containsExactly(100, 0);
    }

    @Test
    void sellerEndpointRequiresAdminAuthority() throws Exception {
        PreAuthorize authorization = ReadQueryController.class
            .getDeclaredMethod("sellers", int.class, int.class)
            .getAnnotation(PreAuthorize.class);

        assertThat(authorization.value()).isEqualTo("hasAuthority('ADMIN_ALL')");
    }
}
