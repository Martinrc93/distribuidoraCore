# Transferencia entre depósitos

```mermaid
sequenceDiagram
    actor Admin
    participant API as Inventory API
    participant Command as InventoryCommandService
    participant Stock as InventoryMovementService
    participant DB as PostgreSQL
    participant Audit

    Admin->>API: POST /api/inventory/transfers
    API->>API: requiere STOCK_ADJUST y valida el payload
    API->>Command: transferir origen, destino, producto y cantidad
    Command->>Stock: transfer(...)
    Stock->>DB: valida producto y depósitos activos
    Stock->>DB: crea balances faltantes en orden de depósito
    Stock->>DB: bloquea ambos balances en orden determinista
    Stock->>Stock: comprueba saldo suficiente en origen
    Stock->>DB: actualiza origen y destino
    Stock->>DB: inserta TRANSFER_OUT y TRANSFER_IN con el mismo transferId
    Command->>Audit: audita dentro de la transacción
    DB-->>API: commit conjunto
    API-->>Admin: 201 Created + transferId

    Note over Stock,DB: Cualquier error revierte los dos saldos y ambos movimientos.
```

Los ajustes pueden dejar un saldo negativo según el comportamiento de inventario
existente; una transferencia, en cambio, no puede retirar más de lo disponible
en el depósito de origen. Una devolución o cancelación repone el depósito
asociado al movimiento comercial original.
