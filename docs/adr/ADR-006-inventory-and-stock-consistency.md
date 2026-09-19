# ADR-006: Inventario basado en movimientos y saldo transaccional

## Status

Accepted

## Context

El stock requiere trazabilidad completa, permite valores negativos y puede ser
modificado por confirmaciones, ediciones, cancelaciones y ajustes. Guardar solo
un campo en `Product` perdería historial y dificultaría la auditoría.

## Decision

El módulo `inventory` tendrá dos conceptos principales:

```text
InventoryBalance
StockMovement
```

`InventoryBalance` representa el saldo actual para lectura rápida.
`StockMovement` es el historial append-only.

Tipos iniciales:

```text
SALE
SALE_CANCELLATION
MANUAL_ENTRY
MANUAL_ADJUSTMENT
RETURN
```

Reglas:

- El stock negativo está permitido.
- Las cantidades son decimales en múltiplos de `0.5`.
- Las salidas, entradas y ajustes ocurren dentro de una transacción.
- Editar un pedido confirmado calcula un delta y crea movimientos compensatorios.
- Cancelar crea una reversión de stock.
- Los movimientos nunca se editan ni eliminan.
- Solo el administrador puede ajustar stock.
- Todos los ajustes requieren auditoría.

Para actualizar un saldo se utilizará locking pesimista de la fila de balance,
equivalente a `SELECT ... FOR UPDATE`, dentro de la transacción de negocio.

La estructura quedará preparada para evolucionar de:

```text
productId
```

a:

```text
warehouseId + productId
```

sin incorporar depósitos antes de necesitarlos.

## Alternatives considered

### Campo `stock` en Product

Descartado por falta de trazabilidad y dificultad para explicar correcciones.

### Solo recalcular saldo desde movimientos

Descartado como estrategia única porque las consultas operativas serían más
costosas y se dificultaría el control transaccional del saldo actual.

### Optimistic locking como única estrategia

No elegido para stock porque el locking pesimista es más simple y predecible
para salidas concurrentes del mismo producto.

## Consequences

### Positivas

- Trazabilidad completa.
- Stock negativo explícito y auditable.
- Correcciones sin reescribir historia.
- Preparación para múltiples depósitos.

### Negativas

- Más registros y lógica que un campo simple.
- Se deben monitorear inconsistencias entre balance y movimientos.
- El locking puede reducir concurrencia sobre productos muy demandados.
