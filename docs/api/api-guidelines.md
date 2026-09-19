# Guía de API REST

## Base

```text
/api/v1
```

La versión forma parte de la URL. Cambios incompatibles requieren una nueva
versión.

## Recursos iniciales

```text
/auth
/users
/roles
/permissions
/sellers
/customers
/brands
/categories
/products
/price-lists
/inventory
/inventory/movements
/orders
/sales
/payments
/customers/{id}/account
/documents
/notifications
```

## Convenciones

- Recursos en plural.
- JSON en `camelCase`.
- Fechas en ISO-8601.
- Timestamps expresados en UTC.
- `GET` no cambia estado.
- `POST` crea recursos o ejecuta comandos explícitos.
- `PATCH` modifica recursos editables.
- `DELETE` se reserva para recursos sin valor histórico; preferir desactivar.
- El backend recalcula importes comerciales.
- No se aceptan totales calculados únicamente por el frontend.

## Comandos de negocio

Los cambios de estado importantes se expresan con endpoints explícitos:

```text
POST /api/v1/orders/confirmation
POST /api/v1/orders/{id}/delivery
POST /api/v1/orders/{id}/delivery-attempts
POST /api/v1/sales/{id}/cancellation
POST /api/v1/payments
POST /api/v1/customers/{id}/account/payments
POST /api/v1/documents/sales/{id}/render
POST /api/v1/notifications/whatsapp
```

La confirmación de pedido crea el pedido persistido, venta, stock y pago/deuda
en una sola operación transaccional. Debe soportar idempotency key.

## Paginación

Toda colección se pagina:

```text
GET /api/v1/products?page=0&size=20&sort=name,asc
```

Valores:

```text
page = 0
size = 20
maxSize = 100
```

La respuesta debe incluir metadata:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

## Filtros y orden

Cada endpoint debe declarar explícitamente sus filtros y campos ordenables. No
se aceptan nombres de columnas arbitrarios provenientes del cliente.

Ejemplo:

```text
GET /api/v1/orders?status=CONFIRMED&customerId={id}&page=0&size=20
```

## Códigos HTTP

| Código | Uso |
|---|---|
| `200` | Consulta o modificación con representación |
| `201` | Creación de recurso |
| `204` | Operación exitosa sin representación |
| `400` | Request mal formado |
| `401` | No autenticado |
| `403` | Sin permiso |
| `404` | Recurso inexistente |
| `409` | Conflicto de estado, idempotencia o concurrencia |
| `422` | Regla de negocio inválida |
| `429` | Rate limit excedido |
| `500` | Error inesperado |

## Errores

Formato estándar:

```json
{
  "type": "https://api.example.com/problems/order-not-confirmable",
  "title": "Order cannot be confirmed",
  "status": 409,
  "code": "ORDER_NOT_CONFIRMABLE",
  "detail": "The order cannot be confirmed in its current state",
  "instance": "/api/v1/orders/123",
  "traceId": "...",
  "fieldErrors": []
}
```

No se exponen stack traces ni detalles internos de SQL.

## Idempotencia

Los comandos que pueden repetirse por reintentos de red deben aceptar
`Idempotency-Key`:

- Confirmación de pedido.
- Registro de pago.
- Cancelación.
- Marcado de entrega.
- Solicitud de WhatsApp.

Una misma key con distinto payload debe responder conflicto.

## Seguridad

- Todos los endpoints requieren autenticación salvo login, activación y
  recuperación.
- La autorización se valida en backend mediante permisos.
- CORS solo permite el frontend configurado.
- No se envían tokens sensibles en URLs.
- Los endpoints de Actuator no forman parte del API público.

## OpenAPI

La especificación OpenAPI debe generarse desde el backend y publicarse por
ambiente solo cuando corresponda. Los ejemplos deben reflejar reglas reales de
validación, paginación y errores.
