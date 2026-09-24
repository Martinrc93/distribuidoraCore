# Resolucion de precio

> Los precios se obtienen siempre desde una lista; el producto no guarda precios fuera de las listas.

```mermaid
flowchart TD
    A[Producto en pedido] --> B{Cliente tiene lista?}
    B -->|Si| C[Usar lista del cliente]
    B -->|No| D[Usar lista predeterminada GENERAL]
    C --> E{Usuario cambia lista?}
    D --> E
    E -->|Si| F[Usar lista seleccionada]
    E -->|No| G[Conservar lista resuelta]
    F --> H[Obtener precio]
    G --> H
    H --> I{Precio disponible?}
    I -->|Si| J[Guardar snapshot en pedido]
    I -->|No| K[Buscar lista activa anterior]
    K --> L{Existe lista anterior?}
    L -->|Si| M[Intentar precio en lista anterior]
    M --> I
    L -->|No| N[Mostrar error]
```
