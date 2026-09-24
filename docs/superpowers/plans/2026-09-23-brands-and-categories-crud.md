# Implementation Plan: CRUD Completo de Marcas y Categorías

- **Date:** 2026-09-23
- **Branch:** `feature/backend`
- **Goal:** Implementar el catálogo de marcas (`catalog.brands`) y categorías (`catalog.categories`) como entidades administrables con su ciclo de vida (`ACTIVE`/`INACTIVE`), validaciones de unicidad, auditoría append-only y endpoints REST protegidos.

---

## 1. Contexto y Esquema de Base de Datos

- **Migración Flyway `V14__create_brands_and_categories_tables.sql`:**
  - `catalog.categories`: `id`, `name`, `code`, `status`, `created_at`.
  - `catalog.brands`: `id`, `name`, `code`, `status`, `created_at`.
  - Índices únicos insensibles a mayúsculas sobre `name` y `code`.
  - Columnas opcionales `brand_id` y `category_id` en `catalog.products` con claves foráneas e índices.

---

## 2. Pasos de Implementación (TDD)

### Paso 1: Test de Contrato de Migración (RED)
- Crear `BrandsAndCategoriesMigrationContractTest.java` en `com.distribuidora.db.migration`.
- Verificar existencia de tablas `catalog.brands` y `catalog.categories`, índices y columnas en `catalog.products`.

### Paso 2: Tests Unitarios de Servicio (RED)
- Crear `BrandServiceTest.java` y `CategoryServiceTest.java` en `com.distribuidora.catalog`.
- Casos de prueba:
  - Creación con validaciones de nombre no vacío, código único y nombre único.
  - Actualización de nombre y código con verificación de duplicados.
  - Modificación de estado (`ACTIVE`/`INACTIVE`), `activate` y `deactivate`.
  - Consulta detallada por ID y listado con filtros.
  - Registro de auditoría append-only (`BRAND_CREATE`, `CATEGORY_CREATE`, etc.).

### Paso 3: Tests Unitarios de Controladores (RED)
- Crear `BrandControllerTest.java` y `CategoryControllerTest.java`.
- Verificar permisos `@PreAuthorize("hasAuthority('ADMIN_ALL')")` en mutaciones.
- Verificar códigos de respuesta (201 Created, 204 No Content, 200 OK).

### Paso 4: Código Productivo (GREEN)
- Crear migración `V14__create_brands_and_categories_tables.sql`.
- DTOs `CatalogAdminDtos.java` en `com.distribuidora.catalog.api`.
- Servicios `BrandService.java` y `CategoryService.java` en `com.distribuidora.catalog.application`.
- Controladores `BrandController.java` y `CategoryController.java` en `com.distribuidora.catalog.api`.

### Paso 5: Verificación Integral (REFACTOR)
- Ejecutar suite completa con `mvn test`.
- Confirmar 0 fallos, 0 errores.

### Paso 6: Documentación y Commit
- Actualizar `docs/development/implementation-checklist.md` y `docs/development/backend-checklist.md`.
- Actualizar `walkthrough.md`.
- Commit: `feat(catalog): implement brands and categories catalog management`.
- Push a `origin/feature/backend`.
