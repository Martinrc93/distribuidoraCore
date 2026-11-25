# Guía de API REST

## Base y autorización

La API usa `/api`. Las instancias de desarrollo pueden anteponer
`/a` o `/b` en el proxy. El cliente agrega `Authorization: Bearer <token>`
a las operaciones autenticadas. El backend controla permisos y alcance de
datos, incluso al volver a ejecutar un comando idempotente.

Los endpoints de sesión son `POST /api/auth/login`, `/refresh`, `/logout`
y `/activate`. El refresh token se envía en el JSON de renovación o cierre
de sesión. Login, renovación y activación tienen límites de frecuencia.

El frontend y la API comparten origen en Compose. Para clientes de otros
orígenes, declarar una lista explícita en `CORS_ALLOWED_ORIGINS`.

## Recursos y comandos

Los recursos usan JSON en `camelCase`. Las fechas de negocio usan
`YYYY-MM-DD` y los timestamps, ISO 8601. El backend calcula importes
a partir de precios, unidades, descuentos y reglas vigentes.

| Operación | Endpoint |
| --- | --- |
| Consultar pedidos | `GET /api/orders` |
| Confirmar un pedido | `POST /api/orders/confirm` |
| Editar un pedido confirmado | `PUT /api/orders/{id}` |
| Preparar un pedido | `POST /api/orders/{id}/prepare` |
| Registrar un intento de entrega | `POST /api/orders/{id}/delivery-attempts` |
| Cancelar o reactivar | `POST /api/orders/{id}/cancel` o `/reactivate` |
| Cobrar cuenta corriente | `POST /api/customers/{id}/account-payments` o `/settlement` |
| Descargar comprobante | `GET /api/orders/{id}/documents/a4` o `/ticket` |
| Solicitar un envío | `POST /api/orders/{id}/notifications` |

Confirmar un pedido registra venta y pagos o deuda en una transacción. El
pedido queda pendiente de preparación. Prepararlo registra los movimientos de
stock correspondientes. Las consultas y exportaciones no modifican el estado.

## Idempotencia

Los contratos de confirmación de pedido, alta de compra, gasto, merma y
solicitud de notificación usan el campo JSON `idempotencyKey`. Ante un
resultado de red incierto, conservar la clave y los datos originales al
reintentar. El backend responde con un conflicto cuando una clave ya registrada
se reutiliza con datos distintos en los comandos que verifican esa equivalencia.

## Paginación

Los listados paginados aceptan `page` desde cero y `size`. Los límites y
filtros aplicables se documentan por recurso.

```http
GET /api/orders?page=0&size=20&search=&status=CONFIRMED
```

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}
```

## Respuestas y errores

| Estado | Uso |
| --- | --- |
| `200`, `201`, `202`, `204` | Operación exitosa, creación, trabajo aceptado o respuesta sin contenido |
| `400` | Solicitud inválida |
| `401`, `403` | Sesión ausente o acción no autorizada |
| `404` | Recurso inexistente o fuera del alcance visible |
| `409` | Conflicto de estado o de idempotencia |
| `429` | Límite de frecuencia excedido |
| `500` | Error inesperado |

Los errores de aplicación usan Problem Details con `status`, `title`,
`detail`, `instance` y `code`. Los errores de validación pueden incluir
`fieldErrors`, un objeto con un mensaje por campo. Usar `X-Request-Id` para
correlacionar una respuesta con los logs.

## OpenAPI y operación

Swagger y OpenAPI se habilitan con el perfil `local` en `/swagger-ui.html` y
`/v3/api-docs`. En el backend, `/actuator/health/liveness` y
`/actuator/health/readiness` exponen el estado de los probes. Los demás
endpoints de Actuator requieren `ADMIN_ALL`. El frontend devuelve `404`
para estas rutas operativas y de documentación.
