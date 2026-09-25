# Inventario y depósitos

PostgreSQL conserva un saldo por producto y depósito. La migración V23 creó el
depósito `CENTRAL` como predeterminado y asignó allí los saldos, movimientos,
pedidos y ventas existentes. Los endpoints anteriores siguen usando ese
depósito cuando no reciben un `depotId`.

## Depósitos

Todos los endpoints de administración de depósitos requieren `ADMIN_ALL`.

| Método | Ruta | Uso |
|---|---|---|
| `GET` | `/api/inventory/depots` | Lista depósitos y señala cuál es el predeterminado. |
| `POST` | `/api/inventory/depots` | Crea un depósito activo con `code` y `name`; responde `201`. |
| `PATCH` | `/api/inventory/depots/{depotId}/status` | Activa/desactiva con `{"active":true}` o `false`. |
| `GET` | `/api/inventory/depots/{depotId}/balances?page=0&size=20&search=` | Lista productos y sus saldos para ese depósito. |

El código se normaliza a mayúsculas y admite letras, números, guion y guion
bajo. El nombre admite hasta 120 caracteres. El depósito predeterminado no se
puede desactivar. Los saldos históricos se consultan aunque el depósito esté
inactivo; las nuevas ventas, transferencias y correcciones requieren depósitos
activos. Las devoluciones y reversas históricas pueden reponer un depósito
inactivo.

## Ajustes y transferencias

`POST /api/inventory/{productId}/adjustments` conserva su permiso `STOCK_ADJUST`
y respuesta `204`. El cuerpo admite `depotId` opcional:

```json
{"depotId":"uuid-opcional","quantity":2.5,"reason":"Conteo físico"}
```

Si se omite `depotId`, el ajuste se aplica al depósito predeterminado. La
cantidad puede ser positiva o negativa, distinta de cero, y debe ser múltiplo
de `0.5`; el motivo admite hasta 500 caracteres.

`POST /api/inventory/transfers` requiere `STOCK_ADJUST` y responde `201` con un
`transferId`:

```json
{
  "fromDepotId":"uuid-origen",
  "toDepotId":"uuid-destino",
  "productId":"uuid-producto",
  "quantity":1.5,
  "reason":"Reposición entre sucursales"
}
```

El origen y el destino deben ser distintos y estar activos. La cantidad debe
ser múltiplo positivo de `0.5`, y el origen debe tener saldo suficiente. El
servicio bloquea ambos balances en orden determinista y persiste `TRANSFER_OUT`
y `TRANSFER_IN` en la misma transacción; si falla una parte, no queda saldo ni
movimiento parcial. Las dos filas comparten el `transferId` y se audita la
operación.

## Pedidos y consultas

La confirmación de pedido acepta `depotId` opcional. Si no se envía, usa
`CENTRAL`. El depósito elegido queda persistido en pedido y venta; la clave de
idempotencia incluye ese dato. En `POST /api/orders/confirm`, agregue el campo
`"depotId":"<UUID>"` al cuerpo para seleccionar otro depósito; si se omite o
vale `null`, se usa `CENTRAL`. Editar un pedido confirmado conserva su depósito
original. La devolución y la cancelación reponen el stock donde se descontó,
agrupando los movimientos por depósito y producto.

`GET /api/inventory` mantiene su contrato paginado y calcula el stock total del
producto sumando todos los depósitos. El historial
`GET /api/inventory/{productId}/movements` incluye `depotId` y `depotCode` en
cada movimiento. `GET /api/inventory/depots/{depotId}/balances` permite
consultar cada saldo por separado.

## Auditoría y persistencia

Los ajustes, transferencias y cambios administrativos de depósito se auditan.
V23 introduce `inventory.depots`, convierte el balance a clave compuesta
`(depot_id, product_id)` y vincula pedidos y ventas al depósito utilizado. El
detalle del esquema se encuentra en
[`data-architecture.md`](../architecture/data-architecture.md) y el flujo de
transferencia en [`flow-depot-transfer.md`](../diagrams/inventory/flow-depot-transfer.md).
