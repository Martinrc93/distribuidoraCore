# Fallo de generacion documental

```mermaid
flowchart TD
    A[Solicitar documento] --> B[Renderizar desde snapshot]
    B --> C{Renderer exitoso?}
    C -->|Si| D[Entregar documento]
    C -->|No| E[Registrar error tecnico]
    E --> F[Mostrar accion de reintento]
    F --> G[Conservar venta sin cambios]
```
