# Modificacion de cliente

```mermaid
flowchart TD
    A[Seleccionar cliente] --> B[Cargar datos actuales]
    B --> C[Editar contacto o direccion]
    C --> D[Validar identificacion fiscal]
    D --> E{Datos validos?}
    E -->|No| F[Mostrar errores]
    E -->|Si| G[Persistir cambios]
    G --> H[Auditar modificacion]
```
