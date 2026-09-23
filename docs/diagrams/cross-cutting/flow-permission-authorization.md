# Autorizacion y ownership

```mermaid
flowchart TD
    A[Request con JWT] --> B[Validar token]
    B --> C{Token valido?}
    C -->|No| D[401 y limpiar sesion]
    C -->|Si| E[Resolver permisos]
    E --> F{Operacion administrativa?}
    F -->|Si| G{Tiene ADMIN_ALL?}
    G -->|No| H[403]
    G -->|Si| I[Permitir]
    F -->|No| J[Resolver ownership seller]
    J --> K{Recurso permitido?}
    K -->|No| H
    K -->|Si| I
```
