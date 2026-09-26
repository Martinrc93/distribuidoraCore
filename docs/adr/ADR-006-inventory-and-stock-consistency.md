# ADR-006: Inventario basado en movimientos y saldo transaccional

## Status

Accepted

Actualización (2026-09-24): la extensión multi-depósito prevista inicialmente
se implementó con V23. Actualización (2026-09-25): V24 reemplazó esa extensión
por un saldo único por producto y consolidó los balances existentes. Se
conservan los principios de movimientos append-only y modificación transaccional
de stock. El contrato vigente está en
[`docs/api/inventory.md`](../api/inventory.md).

### Reevaluación pendiente

Se debe evaluar si conviene retirar inventario por completo del sistema. La
evaluación debe cubrir el módulo y su API, los saldos y movimientos persistidos,
los ajustes manuales, la auditoría asociada y los vínculos con pedidos,
cancelaciones, devoluciones e informes. Hasta que se tome y documente esa
decisión, se conserva el comportamiento vigente. La pantalla de gestión de
stock y movimientos está integrada en Productos; esto no implica que el dominio
ni sus dependencias hayan sido eliminados.

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
TRANSFER_OUT
TRANSFER_IN
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

La decisión inicial preparaba la evolución de:

```text
productId
```

a:

```text
warehouseId + productId
```

La extensión multi-depósito fue implementada en V23 y posteriormente reemplazada
por stock único con V24. Las transferencias y la ubicación por pedido/venta que
se describen en este ADR son comportamiento histórico, no parte del modelo
vigente.

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
- El diseño original ofrecía saldos independientes por depósito y producto; V24
  reemplazó ese comportamiento por un único saldo consolidado por producto.

### Negativas

- Más registros y lógica que un campo simple.
- Se deben monitorear inconsistencias entre balance y movimientos.
- El locking puede reducir concurrencia sobre productos muy demandados.
