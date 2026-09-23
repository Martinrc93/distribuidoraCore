# Ciclo de vida del producto

```mermaid
flowchart TD
    A[Producto activo] --> B{Accion administrativa}
    B -->|Editar| C[Validar cambios]
    C --> A
    B -->|Desactivar| D[Producto inactivo]
    D -->|Reactivar| A
    D --> E[No ofrecer en nuevos pedidos]
```
