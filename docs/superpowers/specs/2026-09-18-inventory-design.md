# Diseño: Inventario transaccional

> Estado actualizado (2026-09-25): este documento registra el alcance inicial.
> Los movimientos automáticos de venta/cancelación y el soporte multi-depósito
> se implementaron posteriormente; V24 reemplazó el modelo multi-depósito por
> un saldo único por producto. El contrato vigente está en
> [`docs/api/inventory.md`](../../api/inventory.md) y el diseño en
> [`2026-09-25-single-inventory-no-depots-design.md`](2026-09-25-single-inventory-no-depots-design.md).

## Objetivo

Implementar la primera vertical real del módulo `inventory`, permitiendo ajustes manuales auditables y consultas de saldo e historial, sin alterar el modelo append-only de movimientos.

## Alcance

Incluye:

- Ajustes manuales de stock por producto.
- Saldos negativos permitidos.
- Cantidades válidas en múltiplos de `0.5`.
- Lock pesimista del saldo durante la operación.
- Movimientos append-only.
- Auditoría de cada ajuste.
- Consulta paginada de saldos e historial por producto.
- Autorización administrativa mediante `STOCK_ADJUST`.

No incluía en su alcance inicial:

- Movimientos automáticos por confirmación o cancelación de ventas.
- Múltiples depósitos.
- Reservas de stock.
- Outbox o procesamiento asíncrono.

## Arquitectura

Se utilizará `JdbcTemplate`, siguiendo el estilo actual de los commands de clientes y productos. El servicio de comandos será el único punto que modifique balances y movimientos.

### Ajuste manual

La operación `adjust` ejecutará una única transacción:

1. Validar `productId`, cantidad absoluta positiva y múltiplo de `0.5`.
2. Verificar que el producto exista y esté `ACTIVE`.
3. Bloquear el balance con `SELECT ... FOR UPDATE`.
4. Actualizar `inventory.inventory_balances.quantity` sumando el delta.
5. Insertar un registro inmutable en `inventory.stock_movements` con tipo `MANUAL_ADJUSTMENT`.
6. Registrar auditoría con actor, producto, delta y motivo.
7. Confirmar la transacción.

La cantidad recibida se expresará como delta firmado: positiva para ingreso y negativa para egreso. El motivo será obligatorio.

### Modelo existente

La migración V3 ya contiene:

- `inventory.inventory_balances(product_id, quantity, updated_at)`.
- `inventory.stock_movements(id, product_id, movement_type, quantity, reference_type, reference_id, created_at)`.

La migración de inventario agregará `reason VARCHAR(500) NOT NULL` para conservar el motivo obligatorio de cada ajuste. Los registros existentes recibirán temporalmente `Migración inicial` durante la migración y la columna quedará sin valor por defecto.

Se conservarán estas tablas y se agregarán índices o restricciones únicamente si la implementación demuestra que son necesarios.

## API

### Ajustar stock

`POST /api/inventory/{productId}/adjustments`

Request:

```json
{
  "quantity": "2.5",
  "reason": "Conteo físico"
}
```

Response: `204 No Content`.

Requiere autoridad `STOCK_ADJUST`.

### Consultar saldos

`GET /api/inventory?page=0&size=20`

Mantiene el endpoint paginado existente y expone el saldo actual por producto.

### Consultar movimientos

`GET /api/inventory/{productId}/movements?page=0&size=20`

Devuelve movimientos ordenados por `created_at DESC`, sin permitir edición ni eliminación.

## Errores

- `400 INVALID_REQUEST`: cantidad cero, cantidad no múltiplo de `0.5`, motivo vacío o formato inválido.
- `401 UNAUTHORIZED`: ausencia de JWT.
- `403 FORBIDDEN`: JWT sin `STOCK_ADJUST` para ajustes.
- `404 NOT_FOUND`: producto o balance inexistente.
- `409 CONFLICT`: producto inactivo o conflicto de integridad.

## Seguridad y auditoría

- Los comandos requieren JWT.
- Solo administradores con `STOCK_ADJUST` ajustan stock.
- Cada ajuste genera un evento de auditoría append-only.
- El historial de movimientos no se expone como operación mutable.

## Pruebas

Se cubrirán:

- Validación de cantidades positivas, negativas y múltiplos de `0.5`.
- Rechazo de producto inactivo.
- Actualización atómica de balance y movimiento.
- Uso del locking pesimista.
- Rechazo por autoridad insuficiente.
- Auditoría del ajuste.
- Consulta ordenada de movimientos.
- Prueba HTTP contra PostgreSQL real en Compose.

## Decisiones y límites

- Se usa `JdbcTemplate` porque el backend ya implementa los commands comerciales con SQL explícito.
- No se crea una entidad JPA paralela para evitar dos modelos de persistencia sobre las mismas tablas.
- La integración con ventas se implementará en una fase posterior, reutilizando el mismo servicio transaccional para los tipos `SALE` y `SALE_CANCELLATION`.
