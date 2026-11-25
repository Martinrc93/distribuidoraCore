# ADR-005: Comunicación interna y Transactional Outbox

## Status

Accepted

## Context

El flujo de confirmación necesita consistencia inmediata entre pedido, venta,
pagos y cuenta corriente. La preparación posterior registra stock. El envío de
documentos por email y WhatsApp se procesa de forma asíncrona.

No existe inicialmente una necesidad de Kafka o RabbitMQ.

## Decision

Se utilizarán llamadas síncronas para invariantes del negocio y eventos para
hechos de negocio o efectos secundarios.

Confirmar un pedido será una única transacción local:

```text
validar pedido
→ calcular precios y descuentos
→ crear venta
→ registrar pagos o deuda
→ persistir pedido CONFIRMED con preparación PENDING
→ registrar auditoría
→ guardar eventos outbox
→ commit
```

La outbox se persiste en la transacción del comando que publica el evento. Un
worker del monolito procesa los eventos pendientes y registra sus reintentos.

Eventos iniciales:

```text
ORDER_CONFIRMED
NOTIFICATION_DELIVERY_REQUESTED
USER_INVITATION_EMAIL_REQUESTED
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
