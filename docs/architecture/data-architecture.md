# Arquitectura de datos

## PostgreSQL

PostgreSQL es la fuente de verdad transaccional. Se utilizará un schema por
módulo y Flyway administrará todas las migraciones.

Ejemplo conceptual:

```text
identity.users
seller.seller_profiles
customer.customers
catalog.products
catalog.price_lists
catalog.product_prices
inventory.balances
inventory.stock_movements
order.orders
sale.sales
payment.payments
payment.account_ledger
document.documents
audit.entries
```

## Identificadores y fechas

- UUID v7 para entidades.
- `timestamptz` en PostgreSQL.
- `Instant` en Java.
- Zona de presentación: `America/Argentina/Buenos_Aires`.
- Fechas almacenadas en UTC.

## Importes y cantidades

- Java: `BigDecimal`.
- PostgreSQL: `NUMERIC`.
- Importes iniciales: `NUMERIC(19,4)`.
- Cantidades de stock: escala suficiente para múltiplos de `0.5`.
- Nunca usar `float` o `double` para dinero.
- Los cálculos deben aplicar un rounding mode explícito.

## Integridad

Se utilizarán constraints para reglas estructurales:

- `NOT NULL` para datos requeridos.
- `UNIQUE` para username, email y códigos de negocio.
- `CHECK` para costos (`cost >= 0`), precios (`price >= 0`), cantidades y estados válidos.
- Precios de venta normalizados exclusivamente en `catalog.product_prices` por lista (`catalog.products` solo almacena el costo).
- Índices para búsquedas, estados, fechas y referencias.
- Optimistic locking mediante `version` en entidades editables.
- Locking pesimista para balances de stock.

Las reglas que dependen de varios módulos se validan en casos de uso y no se
resuelven únicamente con foreign keys.

## Historial

Son append-only:

- Movimientos de stock.
- Pagos.
- Aplicaciones de pagos.
- Auditoría.
- Intentos de entrega.

Las correcciones se expresan mediante reversas, ajustes o movimientos nuevos.

## Borrado lógico

Se desactivan usuarios, vendedores, productos, clientes y listas de precios.
No se eliminan físicamente pedidos, ventas, pagos, movimientos ni auditoría.

## Migraciones

- Flyway es la única fuente de evolución del esquema.
- Las migraciones deben ser pequeñas y revisables.
- No se usa `ddl-auto=update`.
- El pipeline ejecuta migraciones antes de validar la aplicación.
- Las migraciones destructivas requieren revisión explícita y estrategia de
  compatibilidad.

## Backups

La retención inicial es de dos backups diarios y uno mensual. El almacenamiento
inicial es interno, con evolución futura a un segundo destino externo.
