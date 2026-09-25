package com.distribuidora.identity;

import com.distribuidora.identity.api.RoleAdminController;
import com.distribuidora.identity.api.RoleAdminDtos;
import com.distribuidora.identity.application.RoleAdminService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RoleAdminControllerTest {
    private final RoleAdminService service = mock(RoleAdminService.class);
    private final RoleAdminController controller = new RoleAdminController(service);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void listRolesReturnsPermissionsByRole() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.listRoles()).thenReturn(List.of(
            new RoleAdminService.RoleView(id, "ADMIN", "Administrador", List.of("ADMIN_ALL"))
        ));

        mockMvc.perform(get("/api/roles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("ADMIN"))
            .andExpect(jsonPath("$[0].permissions[0]").value("ADMIN_ALL"));

        verify(service).listRoles();
    }

    @Test
    void listPermissionsReturnsCatalog() throws Exception {
        when(service.listPermissions()).thenReturn(List.of(
            new RoleAdminService.PermissionView("ORDER_CREATE", "Crear pedidos")
        ));

        mockMvc.perform(get("/api/permissions"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].code").value("ORDER_CREATE"));

        verify(service).listPermissions();
    }

    @Test
    void replacePermissionsReturnsUpdatedRole() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.replacePermissions("SELLER", Set.of("ORDER_CREATE")))
            .thenReturn(new RoleAdminService.RoleView(id, "SELLER", "Vendedor", List.of("ORDER_CREATE")));

        mockMvc.perform(put("/api/roles/SELLER/permissions")
                .contentType(APPLICATION_JSON)
                .content("{\"permissionCodes\":[\"ORDER_CREATE\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("SELLER"))
            .andExpect(jsonPath("$.permissions[0]").value("ORDER_CREATE"));

        verify(service).replacePermissions("SELLER", Set.of("ORDER_CREATE"));
    }

    @Test
    void roleAndPermissionEndpointsRequireAdminAll() throws Exception {
        assertEquals("hasAuthority('ADMIN_ALL')", RoleAdminController.class
            .getMethod("listRoles").getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAuthority('ADMIN_ALL')", RoleAdminController.class
            .getMethod("listPermissions").getAnnotation(PreAuthorize.class).value());
        assertEquals("hasAuthority('ADMIN_ALL')", RoleAdminController.class
            .getMethod("replaceRolePermissions", String.class, RoleAdminDtos.ReplaceRolePermissionsRequest.class)
            .getAnnotation(PreAuthorize.class).value());
    }
}
