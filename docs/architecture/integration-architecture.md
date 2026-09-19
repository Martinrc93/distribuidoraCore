# Arquitectura de integraciones

## Principio

Las integraciones externas se encapsulan mediante ports/adapters. El dominio
no conoce SDKs, URLs, tokens ni modelos de proveedores.

```mermaid
flowchart LR
    APP[Application use case] --> PORT[Integration port]
    PORT --> EMAIL[Email adapter]
    PORT --> WA[WhatsApp adapter]
    PORT --> STORAGE[Temporary storage adapter]
    EMAIL --> EMAIL_EXT[Brevo Free / future provider]
    WA --> WA_EXT[Meta test / Twilio Sandbox / future official provider]
    STORAGE --> FS[Internal temporary filesystem]
```

## Email

Uso inicial:

- Activación de usuarios.
- Recuperación de contraseña.
- Alertas operativas.

Proveedor inicial recomendado: Brevo Free. El adapter debe permitir reemplazarlo
sin modificar `identity` ni `audit`.

Requisitos:

- SPF, DKIM y DMARC.
- Templates versionados.
- Estados de envío y errores sanitizados.
- Reintentos controlados.
- No registrar tokens ni contenido sensible en logs.

## WhatsApp

El envío es opcional y manual. La confirmación de una venta no depende de
WhatsApp.

Para desarrollo se puede utilizar Meta Cloud API de prueba o Twilio Sandbox.
La producción requiere una API oficial o BSP oficial. No se usarán
automatizaciones de WhatsApp Web.

El flujo es:

```text
Usuario solicita envío
→ generar documento temporal
→ crear delivery request
→ enviar mediante adapter
→ actualizar estado y respuesta
→ eliminar documento temporal
```

Estados externos recomendados:

```text
PENDING
PROCESSING
SENT
FAILED
RETRY_EXHAUSTED
```

Cada envío debe tener idempotency key para evitar duplicados.

## Documentos y almacenamiento

Los documentos se generan bajo demanda para descargar, imprimir o enviar.
Inicialmente se almacenan temporalmente en filesystem interno no público, con
retención recomendada de una hora y limpieza programada.

La persistencia permanente de PDFs no forma parte del alcance inicial. Si se
agrega, se utilizará un adapter compatible con filesystem, MinIO o S3 y
PostgreSQL conservará únicamente metadata.

## Outbox

La outbox se escribe en la misma transacción de la venta, stock, pagos y
auditoría. Un worker del monolito procesa los eventos pendientes y aplica
reintentos.

La falla de email, WhatsApp o almacenamiento no revierte la confirmación del
pedido.

## Timeouts y resiliencia

Cada cliente externo debe tener:

- Timeout de conexión.
- Timeout de respuesta.
- Reintentos limitados.
- Backoff.
- Idempotencia.
- Registro de error sin secretos.
- Estado visible para el usuario interno.
