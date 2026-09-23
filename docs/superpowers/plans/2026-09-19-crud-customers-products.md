# CRUD Customers And Products Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar escritura real de clientes y productos sobre PostgreSQL y conectar los formularios frontend.

**Architecture:** Cada módulo tendrá un command service y controller con DTOs explícitos. Las operaciones usarán `JdbcTemplate` parametrizado, transacciones cortas y `AuditService` en una transacción independiente; la desactivación será lógica.

**Tech Stack:** Spring Boot, Spring JDBC, PostgreSQL, React, TanStack Query, Vitest.

## Global Constraints

- `tax_id`, cuando se informa, y `sku` permanecen únicos.
- `cost` y `price` no pueden ser negativos.
- No se borran físicamente clientes ni productos.
- Todos los commands requieren JWT.
- Los cambios generan auditoría append-only.

---

### Task 1: Backend customer/product commands

**Files:**
- Create: `backend/src/main/java/com/distribuidora/customer/application/CustomerCommandService.java`
- Create: `backend/src/main/java/com/distribuidora/catalog/application/ProductCommandService.java`
- Create: `backend/src/main/java/com/distribuidora/customer/api/CustomerCommandController.java`
- Create: `backend/src/main/java/com/distribuidora/catalog/api/ProductCommandController.java`
- Modify: `backend/src/main/java/com/distribuidora/dashboard/application/ReadQueryService.java`
- Create: `backend/src/test/java/com/distribuidora/customer/CustomerCommandServiceTest.java`
- Create: `backend/src/test/java/com/distribuidora/catalog/ProductCommandServiceTest.java`

- [ ] Escribir tests fallidos para create, update, deactivate y validación de importes/unicidad.
- [ ] Verificar que fallan por falta de servicios.
- [ ] Implementar commands transaccionales con SQL parametrizado y `AuditService`.
- [ ] Implementar controllers con `201`, `200`, `204`, `400`, `404` y `409`.
- [ ] Ejecutar `mvn test`.

### Task 2: Frontend forms and mutations

**Files:**
- Modify: `frontend/src/shared/api/client.ts`
- Modify: `frontend/src/app/App.tsx`
- Modify: `frontend/src/app/App.test.tsx`
- Create: `frontend/src/shared/api/client.test.ts`

- [ ] Escribir test fallido para `apiPost`/`apiPut` y error HTTP.
- [ ] Implementar helpers HTTP de mutación.
- [ ] Reemplazar botones de nuevo cliente/producto por formularios controlados.
- [ ] Invalidar queries después de guardar y mostrar errores de validación.
- [ ] Ejecutar `npm test` y `npm run build`.

### Task 3: PostgreSQL integration and containers

**Files:**
- Modify: `backend/README.md`
- Modify: `docs/development/backend-implementation-roadmap.md`

- [ ] Reconstruir imágenes y reiniciar Compose.
- [ ] Probar create/update/deactivate con JWT contra PostgreSQL real.
- [ ] Confirmar que los conteos de la semilla no cambian salvo los nuevos registros.
- [ ] Verificar `mvn package -DskipTests`, healthcheck y proxy frontend.
