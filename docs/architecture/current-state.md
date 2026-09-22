# Estado Actual Del Programa

Estado relevado: 2026-09-20.

Este documento usa Mermaid para mostrar cómo quedó el sistema después de los
bloques implementados. Las líneas punteadas representan capacidades previstas
pero todavía no implementadas.

## Despliegue

```mermaid
flowchart LR
    Browser[Navegador] --> Frontend[Frontend React + Vite]
    Frontend -->|/api proxy| Backend[Spring Boot REST API]
    Backend --> Flyway[Flyway migrations]
    Flyway --> DB[(PostgreSQL 16)]
    Backend --> Actuator[Actuator health/readiness]
    Backend -.-> External[WhatsApp / Email]

    subgraph Compose[Docker Compose]
        Frontend
        Backend
        DB
    end

    classDef done fill:#d9f7e8,stroke:#18794e,color:#123524
    classDef pending fill:#fff1cc,stroke:#9a6700,color:#4d3800
    class Frontend,Backend,Flyway,DB,Actuator done
    class External pending
```

## Módulos Implementados

```mermaid
flowchart TD
    Identity[Identity<br/>login, JWT, roles, permisos]
    Seller[Seller<br/>perfiles y ownership]
    Customer[Customer<br/>clientes y asignaciones]
    Catalog[Catalog<br/>productos, listas y precios]
    Inventory[Inventory<br/>balances y movimientos]
    Order[Order<br/>confirmación y lifecycle]
    Sale[Sale<br/>venta y snapshots]
    Payment[Payment<br/>pagos y cuenta corriente]
    Document[Document<br/>PDF A4]
    Audit[Audit<br/>trazabilidad]
    Notification[Notification<br/>pendiente]

    Identity --> Seller
    Identity --> Customer
    Identity --> Catalog
    Identity --> Order
    Identity --> Document
    Seller --> Customer
    Customer --> Order
    Catalog --> Order
    Order --> Inventory
    Order --> Sale
    Order --> Payment
    Order --> Audit
    Order --> Document
    Sale --> Document
    Payment --> Customer
    Document -.-> Notification

    classDef done fill:#d9f7e8,stroke:#18794e,color:#123524
    classDef pending fill:#fff1cc,stroke:#9a6700,color:#4d3800
    class Identity,Seller,Customer,Catalog,Inventory,Order,Sale,Payment,Document,Audit done
    class Notification pending
```

## Confirmación Y Venta

```mermaid
sequenceDiagram
    actor User as Usuario
    participant UI as React SPA
    participant Auth as Identity / JWT
    participant Order as Order service
    participant Catalog as Catalog / Pricing
    participant Stock as Inventory
    participant Sale as Sale
    participant Payment as Payment / Ledger
    participant Audit as Audit
    participant DB as PostgreSQL

    User->>UI: Completa cliente, líneas y pagos
    UI->>Auth: Envía Bearer JWT
    UI->>Order: POST /api/orders/confirm
    Order->>Auth: Valida ORDER_CREATE o ADMIN_ALL
    Order->>Catalog: Resuelve productos y precios
    Order->>Stock: Bloquea balances y registra SALE
    Order->>Sale: Crea venta y snapshots
    Order->>Payment: Registra pagos o cuenta corriente
    Order->>Audit: Registra operación
    Order->>DB: Confirma una transacción única
    DB-->>Order: Commit
    Order-->>UI: Pedido, venta, pagos y saldo
```

## Lifecycle Y Documentos

```mermaid
stateDiagram-v2
    [*] --> CONFIRMED: Confirmar pedido
    CONFIRMED --> CONFIRMED: Intento FAILED
    CONFIRMED --> DELIVERED: Intento DELIVERED
    CONFIRMED --> CANCELLED: Cancelar sin pagos / ADMIN_ALL
    DELIVERED --> [*]
    CANCELLED --> [*]

    note right of CANCELLED
      Revierte SALE con SALE_CANCELLATION
      y registra CREDIT en cuenta corriente
    end note
```

```mermaid
flowchart LR
    Sale[Venta CONFIRMED / DELIVERED / CANCELLED] --> Access{ORDER_CREATE<br/>o ADMIN_ALL}
    Access -->|Permitido| Pdf[Generar PDF A4 bajo demanda]
    Access -->|Denegado| Forbidden[403 / ownership rechazado]
    Pdf --> Browser[Descarga o impresión]
    Pdf -. no almacena archivos .-> DB[(PostgreSQL snapshots)]
```

## Autorización Y Ownership

```mermaid
flowchart TD
    Login[Login] --> JWT[JWT con authorities]
    JWT --> Admin{ADMIN_ALL}
    JWT --> SellerRole{SELLER / ownership}

    Admin --> Global[Acceso global y mutaciones administrativas]
    SellerRole --> Profile[CurrentUserAccess]
    Profile --> CustomerScope[Clientes asignados]
    Profile --> OrderScope[Pedidos propios o del cliente asignado]
    Profile --> PaymentScope[Ventas y pagos relacionados]
    Profile --> DocumentScope[PDF del pedido autorizado]

    SellerRole -.->|sin permiso| AdminOnly[Usuarios, inventario global,<br/>pricing y comandos administrativos]

    classDef admin fill:#dbeafe,stroke:#2563eb,color:#172554
    classDef seller fill:#ede9fe,stroke:#7c3aed,color:#2e1065
    classDef pending fill:#fff1cc,stroke:#9a6700,color:#4d3800
    class Admin,Global admin
    class SellerRole,Profile,CustomerScope,OrderScope,PaymentScope,DocumentScope seller
    class AdminOnly pending
```

## Pendiente

```mermaid
flowchart LR
    IdentityPending[Invitaciones,<br/>refresh y revocación]
    SellerPending[CRUD completo de vendedores]
    OrderPending[Edición de pedidos,<br/>devoluciones y FIFO]
    NotificationPending[Outbox, email,<br/>WhatsApp y tickets]
    OpsPending[CI, métricas,<br/>backups y restore]

    IdentityPending -.-> Identity[Identity]
    SellerPending -.-> Seller[Seller]
    OrderPending -.-> Order[Order / Payment]
    NotificationPending -.-> Notification[Notification]
    OpsPending -.-> Platform[Operación]

    classDef pending fill:#fff1cc,stroke:#9a6700,color:#4d3800
    class IdentityPending,SellerPending,OrderPending,NotificationPending,OpsPending pending
```

## Leyenda

- Verde: implementado y probado.
- Azul: acceso administrativo global.
- Violeta: ownership seller.
- Amarillo: pendiente o previsto.
- Flecha continua: dependencia o flujo implementado.
- Flecha punteada: integración o funcionalidad pendiente.
