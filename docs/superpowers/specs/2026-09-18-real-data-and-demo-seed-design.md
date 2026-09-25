# Datos Reales Y Semilla Demo: Diseño

## Objetivo

Reemplazar los arrays mock del frontend por consultas paginadas a PostgreSQL a
través del backend y proveer una semilla de desarrollo reproducible con datos
comerciales suficientes para revisar la aplicación completa.

## Alcance

La primera entrega cubre lectura de dashboard, clientes, productos, inventario,
pedidos, ventas, pagos y usuarios. También crea los datos mínimos relacionados
para que esas lecturas sean coherentes: vendedores, líneas de venta, saldos,
movimientos de stock y pagos.

Quedan fuera de esta entrega las operaciones de escritura del flujo comercial,
activación de usuarios por email, refresh tokens y documentos PDF. Se mantienen
como fases posteriores del roadmap de ADR.

## Arquitectura

Se mantiene el monolito modular. Cada módulo es dueño de sus tablas y expone
un caso de uso de lectura mediante un controller REST. El frontend solo conoce
DTOs HTTP y no accede directamente a PostgreSQL.

```text
React/TanStack Query
        |
        v
GET /api/{resource}?page=&size=&search=
        |
        v
Spring Boot query service
        |
        v
PostgreSQL schemas + Flyway
```

Los listados usan paginación basada en `page`, `size` y `totalElements`. La
búsqueda se ejecuta en backend usando parámetros y no concatenación SQL.

## Modelo de datos

Se agregan migraciones para:

- `seller.seller_profiles`: perfil comercial y vínculo opcional con usuario.
- `customer.customers`: cliente, identificación fiscal, vendedor asignado,
  estado y saldo de cuenta corriente.
- `catalog.products`: producto, categoría, presentación, costo, estado y stock inicial derivado.
- `catalog.product_prices`: precios de venta asociados por lista de precios.
- `inventory.inventory_balances`: saldo actual por producto.
- `inventory.stock_movements`: historial append-only.
- `orders.orders` y `orders.order_items`: pedidos confirmados y snapshots.
- `sale.sales` y `sale.sale_items`: ventas y snapshots de precio.
- `payment.payments`: pagos aplicados a ventas.

Los importes se persisten como `NUMERIC(19,4)`, las cantidades como
`NUMERIC(19,4)` y timestamps como `TIMESTAMPTZ`. Los IDs son UUID generados por
la aplicación. Las tablas de movimientos, ventas y pagos no tienen operaciones
de update/delete en los casos de uso expuestos.

## Contratos REST

Todos los endpoints requieren Bearer JWT, salvo health y login.

```text
GET /api/dashboard
GET /api/customers?page=0&size=20&search=
GET /api/products?page=0&size=20&search=
GET /api/inventory?page=0&size=20&search=
GET /api/orders?page=0&size=20&search=&status=
GET /api/sales?page=0&size=20&search=
GET /api/payments?page=0&size=20&search=
GET /api/users?page=0&size=20&search=
```

Respuesta de listado:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

Los DTOs de lectura entregan valores numéricos sin formato monetario. El
frontend es responsable de formato, locale y badges.

## Semilla de desarrollo

La semilla se ejecuta únicamente cuando `SEED_DEMO=true`. Es idempotente usando
un registro de ejecución o una consulta de existencia por email/producto. Una
segunda ejecución no duplica usuarios, productos, clientes ni ventas.

Volumen fijo:

- 2 usuarios administradores.
- 3 usuarios vendedores y sus perfiles comerciales.
- 500 productos.
- 300 clientes asignados entre vendedores.
- 1.000 pedidos confirmados.
- 1.000 ventas con 2 a 5 líneas cada una.
- Pagos completos, parciales y saldos en cuenta corriente.
- Movimientos de stock consistentes con las ventas.

Las credenciales se toman de variables de entorno. La semilla no incluye
passwords reales ni secretos versionados.

## Frontend

Se crea un cliente HTTP pequeño y hooks TanStack Query por recurso. Las páginas
dejan de declarar filas locales y consumen `data.content`. Loading muestra un
estado de carga, errores muestran un `EmptyState` accionable y respuestas sin
resultados muestran estado vacío sin datos de reemplazo.

El dashboard consume sus métricas desde `/api/dashboard`; no calcula métricas
desde filas parciales del frontend.

## Seguridad

- Los endpoints de lectura no se exponen sin JWT.
- La semilla de demo no se ejecuta en el profile `test`.
- Los passwords se hashean con el `PasswordEncoder` existente.
- Las búsquedas usan Spring Data `Pageable` y parámetros.
- Los controllers no devuelven entidades JPA directamente.

## Testing

- Tests de repositorio/query para búsqueda y paginación.
- Tests de controller para status, shape de respuesta y autenticación.
- Test del seeder para idempotencia y conteos.
- Test de frontend para renderizar datos de API, loading, error y vacío.
- `mvn test`, `mvn package -DskipTests`, `npm test` y `npm run build` deben pasar.
