# Acciones de documentos

```mermaid
flowchart TD
    A[Venta confirmada] --> B[Usuario elige accion]
    B -->|Descargar| C[Generar PDF]
    B -->|Imprimir A4| D[Generar formato A4]
    B -->|Ticket 58 mm| E[Generar ticket 58]
    B -->|Ticket 88 mm| F[Generar ticket 88]
    B -->|WhatsApp| G[Generar documento para envio]
    C --> H[Entregar archivo]
    D --> H
    E --> H
    F --> H
    G --> I[Enviar mediante adapter]
```
