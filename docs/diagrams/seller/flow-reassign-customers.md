# Reasignacion masiva de clientes

```mermaid
flowchart TD
    A[Administrador inicia reasignacion] --> B[Seleccionar seller origen]
    B --> C[Seleccionar seller destino activo]
    C --> D[Seleccionar clientes]
    D --> E{Destino valido?}
    E -->|No| F[Mostrar error]
    E -->|Si| G[Actualizar asignaciones]
    G --> H[Conservar historial de pedidos]
    H --> I[Auditar reasignacion]
```
