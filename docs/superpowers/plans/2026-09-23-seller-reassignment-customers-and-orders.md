# Implementation Plan: Reasignación Masiva de Clientes y Pedidos

- **Date:** 2026-09-23
- **Branch:** `feature/backend`
- **Goal:** Implementar la reasignación masiva de clientes y pedidos entre vendedores (`seller.seller_profiles`), validando estado activo del destino, preservando el historial de pedidos finalizados, y registrando auditoría append-only.

---

## 1. Contexto y Diagrama de Negocio

De acuerdo a [`flow-reassign-customers.md`](file:///c:/Users/Mateo/OneDrive/Documents/Dev/distribuidoraCore/docs/diagrams/seller/flow-reassign-customers.md):
- El administrador selecciona el vendedor origen (`sourceSellerId`) y el vendedor destino activo (`targetSellerId`).
- Puede seleccionar un conjunto específico de clientes o reasignar la cartera completa del origen.
- Se valida que el vendedor destino exista, esté en estado `ACTIVE`, y sea distinto al origen.
- Se actualizan las asignaciones de clientes (`customer.customers.seller_id`).
- Se reasignan los pedidos pendientes (`status = 'CONFIRMED'`) si corresponde.
- Se conserva el historial de pedidos finalizados (`status IN ('DELIVERED', 'CANCELLED')`), manteniendo la atribución original.
- Se audita la reasignación mediante `AuditService`.

---

## 2. Pasos de Implementación (TDD)

### Paso 1: Pruebas Unitarias de Servicio (RED)
- Agregar pruebas en `SellerCommandServiceTest.java`:
  - `reassignCustomers_rejectsNullRequest`
  - `reassignCustomers_rejectsSameSourceAndTarget`
  - `reassignCustomers_rejectsNonExistentSourceSeller`
  - `reassignCustomers_rejectsNonExistentTargetSeller`
  - `reassignCustomers_rejectsInactiveTargetSeller`
  - `reassignCustomers_reassignsAllCustomersWhenListEmpty_andPreservesDeliveredOrdersHistory`
  - `reassignCustomers_reassignsSpecificCustomers_andPendingOrders`
  - `reassignOrders_rejectsInactiveTarget`
  - `reassignOrders_reassignsOnlyConfirmedOrders`

### Paso 2: Pruebas Unitarias de Controlador (RED)
- Agregar pruebas en `SellerCommandControllerTest.java`:
  - Verificación de `@PreAuthorize("hasAuthority('ADMIN_ALL')")` en `reassignCustomers` y `reassignOrders`.
  - `POST /api/sellers/reassign-customers` retorna 200 OK con conteos.
  - `POST /api/sellers/reassign-orders` retorna 200 OK con conteos.

### Paso 3: Código Productivo (GREEN)
- Actualizar `SellerDtos.java` con DTOs de reasignación.
- Implementar `reassignCustomers` y `reassignOrders` en `SellerCommandService.java`.
- Exponer endpoints en `SellerCommandController.java`.

### Paso 4: Verificación Integral (REFACTOR)
- Ejecutar suite completa con `mvn test`.
- Confirmar 0 fallos, 0 errores.

### Paso 5: Documentación y Commit
- Actualizar `docs/development/implementation-checklist.md` y `docs/development/backend-checklist.md`.
- Actualizar `walkthrough.md`.
- Commit: `feat(seller): implement bulk customer and order reassignment`.
- Push a `origin/feature/backend`.
