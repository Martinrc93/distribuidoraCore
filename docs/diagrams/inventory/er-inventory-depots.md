# Modelo de inventario multi-depósito

```mermaid
erDiagram
    DEPOTS ||--o{ INVENTORY_BALANCES : contiene
    PRODUCTS ||--o{ INVENTORY_BALANCES : saldo
    DEPOTS ||--o{ STOCK_MOVEMENTS : registra
    PRODUCTS ||--o{ STOCK_MOVEMENTS : movimiento
    DEPOTS ||--o{ ORDERS : selecciona
    DEPOTS ||--o{ SALES : conserva

    DEPOTS {
        uuid id PK
        string code UK
        string name
        string status
        boolean is_default
    }
    INVENTORY_BALANCES {
        uuid depot_id PK,FK
        uuid product_id PK,FK
        decimal quantity
        timestamp updated_at
    }
    STOCK_MOVEMENTS {
        uuid id PK
        uuid depot_id FK
        uuid product_id FK
        string movement_type
        decimal quantity
        uuid reference_id
        timestamp created_at
    }
    ORDERS {
        uuid id PK
        uuid depot_id FK
    }
    SALES {
        uuid id PK
        uuid depot_id FK
    }
    PRODUCTS {
        uuid id PK
        string sku
    }
```

`inventory_balances` usa clave primaria compuesta `(depot_id, product_id)`. Los
movimientos son append-only. V23 asigna balances, movimientos, pedidos y ventas
preexistentes al depósito `CENTRAL`.
