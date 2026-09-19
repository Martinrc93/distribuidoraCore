# CRUD De Clientes Y Productos: Diseño

## Objetivo

Agregar escritura real para clientes y productos sobre PostgreSQL y conectar
los formularios principales del frontend sin reintroducir mocks ni borrar datos
históricos físicamente.

## Alcance

Incluye:

- Crear, consultar, actualizar y desactivar clientes.
- Crear, consultar, actualizar y desactivar productos.
- Validación de datos y respuestas HTTP consistentes.
- Auditoría de altas, modificaciones y desactivaciones.
- Formularios frontend para clientes y productos.

No incluye todavía confirmación de pedidos, ajustes de stock ni registro manual
de pagos. Esas escrituras se implementarán después de estabilizar este CRUD.

## Contratos

```text
POST   /api/customers
PUT    /api/customers/{id}
PATCH  /api/customers/{id}/status
POST   /api/products
PUT    /api/products/{id}
PATCH  /api/products/{id}/status
```

Los listados existentes permanecen paginados. Los bodies no contienen IDs de
usuario ni campos de auditoría; esos valores salen del JWT y del backend.

Cliente:

```json
{
  "businessName": "Nuevo cliente",
  "taxId": "30-71234567-1",
  "sellerId": "uuid-opcional"
}
```

Producto:

```json
{
  "sku": "SKU-1001",
  "name": "Producto nuevo",
  "category": "Bebidas",
  "presentation": "Unidad",
  "cost": 1000,
  "price": 1500
}
```

## Reglas

- `businessName`, `taxId`, SKU, nombre, categoría y presentación son obligatorios.
- `taxId` y SKU son únicos.
- `cost` y `price` no pueden ser negativos.
- La desactivación es lógica y cambia `status` a `INACTIVE`.
- No se eliminan clientes con pedidos/ventas ni productos con movimientos.
- Los cambios importantes generan `audit.audit_events`.
- Solo `ADMIN_ALL` puede desactivar productos; vendedores pueden crear clientes
  asignados a sí mismos cuando exista ese permiso.

## Backend

Cada módulo tiene un command service y un controller. Los services usan
`JdbcTemplate` con parámetros y devuelven DTOs. Las operaciones se ejecutan en
transacciones cortas; la auditoría usa una transacción independiente para no
perderse si la operación de negocio falla.

## Frontend

Clientes y productos tendrán formularios controlados que llaman al API, cierran
el formulario cuando la mutación es exitosa e invalidan la query del listado.
Los errores de validación se muestran junto al formulario; los errores de red
se muestran como mensaje general. Las tablas siguen consultando PostgreSQL.

## Testing

- Tests unitarios de validación.
- Tests de controller para create/update/deactivate y códigos HTTP.
- Tests de integración con PostgreSQL para unicidad y borrado lógico.
- Tests frontend para submit exitoso y error de validación.
- `mvn test`, `mvn package -DskipTests`, `npm test` y `npm run build`.
