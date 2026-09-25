# Actualizacion de costo y precios por lista

> Regla de negocio implementada y verificada en el backend; la adaptación del formulario en el frontend para mostrar listas afectadas está en desarrollo.

```mermaid
flowchart TD
    A[Editar costo del producto] --> B[Consultar precios de listas activas]
    B --> C{El nuevo costo supera alguna lista?}
    C -->|No| D[Actualizar solo el costo]
    C -->|Si| E[Identificar listas afectadas]
    E --> F[Solicitar nuevos precios para cada lista afectada]
    F --> G{Todos los nuevos precios son mayores o iguales al costo?}
    G -->|No| H[Rechazar actualizacion e informar listas]
    G -->|Si| I[Actualizar costo y precios en una transaccion]
    D --> J[Auditar cambio]
    I --> J
```
