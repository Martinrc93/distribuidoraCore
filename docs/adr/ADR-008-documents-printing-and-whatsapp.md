# ADR-008: Documentos bajo demanda, impresión y WhatsApp

## Status

Accepted

## Context

Los documentos no se necesitan para todas las ventas. El usuario puede
descargar, imprimir o enviar por WhatsApp una representación de la venta. La
confirmación de la venta no debe depender de la generación del PDF ni de un
proveedor externo.

## Decision

Se utilizará el módulo `document`, con un submódulo de rendering/printing:

```text
document/
├── rendering/
├── printing/
│   ├── a4/
│   ├── ticket-58/
│   └── ticket-88/
├── storage/
└── delivery/
```

El módulo será responsable de:

- Construir modelos de documento desde una venta inmutable.
- Generar A4 bajo demanda.
- Generar tickets de 58 mm y 88 mm bajo demanda.
- Mantener plantillas separadas de la lógica comercial.
- Preparar evolución a impresoras térmicas o ESC/POS.

Las plantillas iniciales serán mantenidas por desarrollo. No se implementará
todavía un editor visual de plantillas.

Archivos:

- Se generan bajo demanda.
- Se guardan en un directorio temporal no público.
- Se eliminan después de descargar o enviar.
- Tendrán una retención máxima recomendada de una hora.
- Un proceso programado eliminará archivos huérfanos.
- No se guardarán documentos enviados permanentemente en la primera fase.

WhatsApp se activará solo por acción explícita del usuario. El proveedor estará
detrás de un puerto `WhatsAppProvider` y la venta no dependerá de su
disponibilidad.

Para desarrollo y pruebas se utilizará un entorno gratuito de Meta Cloud API o
Twilio Sandbox. No se asumirá que exista un envío productivo ilimitado y
gratuito. En producción se deberá utilizar una API oficial o BSP oficial.

Email transaccional:

- Se recomienda Brevo Free inicialmente.
- Se utilizará un adapter `EmailSender`.
- Se configurarán SPF, DKIM y DMARC.

## Alternatives considered

### Generar y guardar PDF en cada venta

Descartado porque la mayoría de las ventas no necesitan un archivo permanente.

### Guardar PDFs como BLOB en PostgreSQL

Descartado por crecimiento y costo de backup.

### Automatización de WhatsApp Web

Descartada por falta de soporte oficial, riesgo de bloqueo y fragilidad.

### Editor visual de tickets desde el primer día

Postergado por sobreingeniería. Las plantillas serán versionadas en código.

## Consequences

### Positivas

- Venta independiente de documentos y WhatsApp.
- Menor uso de almacenamiento.
- A4 y tickets evolucionan sin tocar el dominio.
- Proveedores externos intercambiables.

### Negativas

- La generación requiere disponibilidad del renderer cuando el usuario la pide.
- La integración productiva de WhatsApp puede tener costos.
- Las plantillas de impresión requieren validación física posterior.
