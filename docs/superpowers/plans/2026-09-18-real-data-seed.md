# Real Data And Demo Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reemplazar mocks del frontend por lecturas reales y cargar una semilla demo controlada para revisar la aplicación con PostgreSQL.

**Architecture:** El backend expone DTOs paginados por módulo y PostgreSQL es la única fuente de datos. Un `DemoDataSeeder` transaccional se activa solo con `SEED_DEMO=true`; el frontend consume esos endpoints con TanStack Query y muestra loading/error/empty sin fallback mock.

**Tech Stack:** Spring Boot, Spring Data JPA, Flyway, PostgreSQL, React, TanStack Query, Docker Compose.

## Global Constraints

- No se mantienen arrays de negocio en `frontend/src/app/App.tsx`.
- La semilla crea 2 admins, 3 vendedores, 500 productos, 300 clientes y 1.000 ventas.
- La semilla no corre en tests ni producción por defecto.
- Los controllers devuelven DTOs, nunca entidades JPA.
- Los listados son paginados y las búsquedas usan parámetros.

---

### Task 1: Crear contenedores

**Files:** `compose.yaml`, `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`

- [ ] Verificar `docker compose config`.
- [ ] Ejecutar `docker compose up --build -d`.
- [ ] Verificar `docker compose ps` y `/actuator/health`.

### Task 2: Crear esquema comercial

**Files:** `backend/src/main/resources/db/migration/V3__create_commercial_tables.sql`

- [ ] Crear tablas seller, customer, catalog, inventory, orders, sale y payment con constraints, índices, `NUMERIC(19,4)` y timestamps.
- [ ] Ejecutar backend contra PostgreSQL y verificar Flyway V3.

### Task 3: Implementar lecturas backend

**Files:** módulos `seller`, `customer`, `catalog`, `inventory`, `order`, `sale`, `payment`; `dashboard` compartido.

- [ ] Crear entidades/read repositories/projections y DTOs paginados.
- [ ] Exponer `GET /api/dashboard`, `/customers`, `/products`, `/inventory`, `/orders`, `/sales`, `/payments` y `/users`.
- [ ] Agregar tests de paginación, búsqueda y autenticación.

### Task 4: Implementar semilla demo

**Files:** `backend/src/main/java/com/distribuidora/demo/DemoDataSeeder.java`, configuración `application.yml` y Compose.

- [ ] Escribir test de idempotencia y conteos.
- [ ] Implementar generación determinista, relaciones, ventas, líneas, pagos, stock y usuarios.
- [ ] Activar exclusivamente con `SEED_DEMO=true` y passwords por variables de entorno.

### Task 5: Conectar frontend a API

**Files:** `frontend/src/shared/api/*`, `frontend/src/app/App.tsx`, tests frontend.

- [ ] Crear cliente HTTP y hooks TanStack Query.
- [ ] Reemplazar filas mock de dashboard, clientes, productos, inventario, pedidos, ventas, pagos y usuarios.
- [ ] Agregar estados loading, error y vacío.
- [ ] Ejecutar `npm test` y `npm run build`.
