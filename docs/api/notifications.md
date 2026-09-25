# Tickets y solicitudes de notificación

## Descargar ticket

`GET /api/orders/{orderId}/documents/ticket` requiere `ORDER_CREATE` o
`ADMIN_ALL` y ownership sobre el pedido. Genera un PDF con ancho de 80 mm desde
los snapshots persistidos de la venta; no cambia el estado comercial.

## Solicitar envío

`POST /api/orders/{orderId}/notifications` requiere `ORDER_CREATE` o
`ADMIN_ALL` y ownership. La llamada crea una solicitud idempotente y encola su
despacho. El envío ocurre de forma asíncrona.

```json
{
  "channel": "EMAIL",
  "recipient": "compras@example.com",
  "format": "TICKET",
  "idempotencyKey": "ticket-venta-2026-09-24-a1"
}
```

Canales permitidos: `EMAIL` y `WHATSAPP`. Formatos: `A4` y `TICKET`. El número
WhatsApp debe estar en formato E.164 (`+549...`). Reutilizar una clave con los
mismos datos devuelve la solicitud existente; con datos diferentes se rechaza.
La respuesta contiene `requestId` y estado `QUEUED`.

## Consultar estado

`GET /api/orders/{orderId}/notifications/{requestId}` aplica la misma regla de
ownership y devuelve el estado `QUEUED`, `SENDING`, `SENT`, `FAILED` o
`RETRY_EXHAUSTED`, cantidad de intentos, fechas y tipo de último error.

Las solicitudes y cada intento quedan en auditoría. La auditoría enmascara el
destinatario; la tabla operativa lo conserva para poder completar el envío y
purga solicitudes terminales a los 90 días por defecto.

## Configurar proveedores

Los proveedores se conectan mediante webhooks HTTPS configurados en el entorno
(se permite HTTP solo en localhost para pruebas);
no se guardan secretos en PostgreSQL:

- Email: `EMAIL_WEBHOOK_URL` y opcionalmente `EMAIL_WEBHOOK_TOKEN`.
- WhatsApp: `WHATSAPP_WEBHOOK_URL` y opcionalmente `WHATSAPP_WEBHOOK_TOKEN`.
- Timeout común: `NOTIFICATION_PROVIDER_TIMEOUT_MS` (entre 500 y 60000 ms).

El backend realiza `POST` con `Content-Type: application/json` y
`Idempotency-Key: <outboxEventId>`. Si se configura un token, envía
`Authorization: Bearer <token>`. El cuerpo incluye `channel`, `recipient`,
`subject`, `filename`, `contentType` y `attachmentBase64`. El proveedor debe
deduplicar por `Idempotency-Key`; el despacho es al menos una vez. Toda respuesta
HTTP 2xx se considera aceptada. Si el canal no tiene URL válida configurada, la
solicitud se rechaza con conflicto y no se encola.

Las credenciales y la integración concreta del proveedor quedan a cargo de la
configuración de despliegue. La venta nunca espera al envío ni se revierte por
un fallo externo; los fallos se reintentan mediante la outbox.
