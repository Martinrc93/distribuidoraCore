# Administracion de listas de precios

```mermaid
flowchart TD
    A[Administrador gestiona listas] --> B{Accion}
    B -->|Crear| C{Código disponible}
    C -->|No| D[Rechazar código duplicado]
    C -->|Sí| E[Crear lista]
    B -->|Renombrar| F[Actualizar nombre]
    B -->|Dar de baja| G[Marcar inactiva]
    B -->|Reactivar| H[Marcar activa]
    E --> I[Auditar cambio]
    F --> I
    G --> I
    H --> I
```
