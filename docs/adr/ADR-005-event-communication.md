# ADR-005: Comunicación interna y Transactional Outbox

## Status

Accepted

## Context

El flujo de confirmación necesita consistencia inmediata entre pedido, venta,
stock, pagos y cuenta corriente. En cambio, la generación de documentos, email
y WhatsApp son efectos secundarios que no deben hacer fallar una venta válida.

No existe inicialmente una necesidad de Kafka o RabbitMQ.

## Decision

Se utilizarán llamadas síncronas para invariantes del negocio y eventos para
hechos de negocio o efectos secundarios.

Confirmar un pedido será una única transacción local:

```text
validar pedido
→ calcular precios y descuentos
→ registrar salida de stock
→ crear venta
→ registrar pagos o deuda
→ persistir pedido CONFIRMED
→ registrar auditoría
→ guardar eventos outbox
→ commit
```

La outbox se persistirá en la misma transacción y será procesada por un worker
del monolito.

Eventos iniciales:

```text
OrderConfirmed
SaleCreated
SaleUpdated
SaleCancelled
StockChanged
PaymentRegistered
DocumentRequested
WhatsAppDeliveryRequested
```

Los eventos deben incluir identificadores y datos necesarios para el consumidor,
sin exponer entidades internas del módulo productor.

## Alternatives considered

### Kafka o RabbitMQ desde el inicio

Descartados por complejidad operacional y ausencia de una necesidad real de
distribución o volumen.

### Llamadas síncronas para todo

Descartadas porque un proveedor externo caído no debe afectar la confirmación
de una venta.

### Publicar eventos directamente después del commit

Riesgoso porque un fallo entre el commit y la publicación puede perder el
evento. La outbox resuelve esa ventana.

## Consequences

### Positivas

- Consistencia para operaciones comerciales críticas.
- Integraciones externas desacopladas.
- Reintentos e idempotencia.
- Evolución futura hacia un broker sin cambiar el dominio.

### Negativas

- Se debe operar y monitorear la outbox.
- Los efectos secundarios no son inmediatos necesariamente.
- Cada consumidor debe implementar idempotencia.
