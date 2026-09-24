# Outbox de notificaciones

```mermaid
flowchart TD
    A[Operacion comercial] --> B[Guardar cambios de dominio]
    B --> C[Guardar ORDER_CONFIRMED en outbox dentro de la transaccion]
    C --> D[Commit]
    D --> E[Worker reclama lote con SKIP LOCKED y lease]
    E --> F[Despachar a consumidores con event ID estable]
    F -->|Exito| G[Marcar PROCESSED]
    F -->|Fallo| H[Programar reintento con backoff]
    H --> E
    H -->|8 intentos| I[Marcar RETRY_EXHAUSTED]
```

El despacho es al menos una vez. Los consumidores deduplican usando el ID del
evento. La integración de canales externos se agrega en la tarea de tickets y
notificaciones.
