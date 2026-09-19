# ADR-002: PostgreSQL como persistencia principal

## Status

Accepted

## Context

El sistema requiere transacciones consistentes para confirmar pedidos, crear
ventas, registrar pagos y actualizar inventario. También necesita constraints,
auditoría, consultas paginadas y evolución controlada del esquema.

## Decision

Se utilizará PostgreSQL como base de datos transaccional principal y Flyway
como mecanismo exclusivo de migraciones.

Se utilizará un schema PostgreSQL por módulo, con ownership claro de tablas.
Las referencias entre módulos se manejarán preferentemente por identificadores
y contratos de aplicación, evitando compartir entidades JPA.

Reglas de persistencia:

- UUID v7 como identificador de entidades.
- `timestamptz` para timestamps.
- Zona de negocio `America/Argentina/Buenos_Aires`.
- Java `BigDecimal` para importes.
- PostgreSQL `NUMERIC(19,4)` para importes monetarios.
- Cantidades de stock con escala suficiente para múltiplos de `0.5`.
- `created_at`, `updated_at`, `created_by` y `updated_by` cuando corresponda.
- `version` para optimistic locking en entidades editables.
- `NOT NULL`, `CHECK`, `UNIQUE` e índices definidos explícitamente.
- `ddl-auto=validate`; Hibernate no generará el esquema en producción.
- Los movimientos de stock, pagos y auditoría serán append-only.

El borrado físico no se aplicará a pedidos, ventas, pagos, movimientos ni
auditoría. Usuarios, vendedores, productos, clientes y listas se desactivarán
mediante estado.

## Alternatives considered

### PostgreSQL sin separación de schemas

Más simple inicialmente, pero menos explícito para ownership y permisos de
datos entre módulos.

### Una base por módulo

Descartada inicialmente porque complica transacciones y operación sin una
necesidad de aislamiento independiente.

### BLOBs para documentos

Descartado. Los PDFs serán temporales y, si se persisten en el futuro, el
archivo se almacenará fuera de PostgreSQL y la metadata dentro de la base.

## Consequences

### Positivas

- Transacciones ACID para el flujo comercial principal.
- Constraints e índices robustos.
- Buen soporte para locking y consultas de auditoría.
- Migraciones reproducibles mediante Flyway.

### Negativas

- La separación por schemas exige configuración y convenciones consistentes.
- Se deben definir cuidadosamente precisión, escala y redondeo.
- Los backups y restauraciones pasan a ser una responsabilidad operativa.

## Pending decisions

- Precisión final de cantidades si aparecen unidades fraccionarias adicionales.
- Política de backups externos.
- Retención de auditoría.
- Alta disponibilidad, postergada para una fase posterior.
