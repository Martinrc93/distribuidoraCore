# Estado Actual Del Programa

Estado relevado: 2026-11-25. El esquema llega a Flyway V65.

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
    Backend -->|webhook configurado| External[Proveedor WhatsApp / Email]

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
    Document[Document<br/>PDF A4 y ticket 80 mm]
    Audit[Audit<br/>trazabilidad]
    Notification[Notification<br/>outbox, solicitudes, auditoría y reintentos]
    Purchasing[Compras a proveedores]
    Expense[Gastos]
    Settings[Configuración y planes de comisión]

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
    Document --> Notification
    Purchasing --> Inventory
    Settings --> Sale

    classDef done fill:#d9f7e8,stroke:#18794e,color:#123524
    classDef pending fill:#fff1cc,stroke:#9a6700,color:#4d3800
    class Identity,Seller,Customer,Catalog,Inventory,Order,Sale,Payment,Document,Audit,Notification,Purchasing,Expense,Settings done
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
    Note over Order,Stock: Confirmar deja preparación PENDING, sin mover stock
    Order->>Sale: Crea venta y snapshots
    Order->>Payment: Registra pagos o cuenta corriente
    Order->>Audit: Registra operación
    Order->>DB: Confirma una transacción única
    DB-->>Order: Commit
    Order-->>UI: Pedido, venta, pagos y saldo
    UI->>Order: Preparar (o entregar desde PENDING)
    Order->>Stock: Bloquea balances y registra SALE una vez
    Order->>Audit: ORDER_PREPARE con usuario y fecha
```

## Lifecycle Y Documentos

```mermaid
stateDiagram-v2
    [*] --> CONFIRMED: Confirmar pedido
    CONFIRMED --> CONFIRMED: Editar líneas / ADMIN_ALL
    CONFIRMED --> CONFIRMED: Intento FAILED
    CONFIRMED --> DELIVERED: Intento DELIVERED
    CONFIRMED --> CANCELLED: Cancelar sin pagos / ADMIN_ALL
    DELIVERED --> [*]
    CANCELLED --> [*]

    note right of CANCELLED
      Revierte el neto de SALE y SALE_CANCELLATION
      y registra CREDIT en cuenta corriente
    end note
```

```mermaid
flowchart LR
    Sale[Venta CONFIRMED / DELIVERED / CANCELLED] --> Access{ORDER_CREATE<br/>o ADMIN_ALL}
    Access -->|Permitido| Pdf[Generar PDF A4 o ticket bajo demanda]
    Access -->|Denegado| Forbidden[403 / ownership rechazado]
    Pdf --> Browser[Descarga o impresión]
    Pdf -. no almacena archivos .-> DB[(PostgreSQL snapshots)]
    Pdf -->|solicitud explícita| Outbox[Webhook email / WhatsApp por outbox]
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

## Calidad Estática, Refactor Y Pendientes Post-Fases

- **Backend (`V65`, Java 21 / Spring Boot):** gates Maven en `verify` con
  Surefire (`*Test`), Failsafe (`*IT`), Checkstyle, SpotBugs, JaCoCo y
  ArchUnit (`ModuleBoundaryTest`). Zona horaria comercial centralizada en
  `BusinessTime`/`TimeConfig`, resolución de actor en `CurrentUserAccess` y
  servicios grandes descompuestos (`ReadQueryService` en servicios por
  dominio, `OrderEditService`, `OrderCancellationService`, `SellerQueryService`).
- **Frontend (React + TypeScript + Tailwind v4):** `Pagination` compartido,
  `shared/format.ts` y `shared/dates.ts`, `queryKeys` jerárquicas con
  invalidación por dominio, lazy loading de rutas, descomposición de
  `OrderForm`/`PriceListsPage`/`ProductsPage` y retiro de pantallas legadas
  (`PaymentsPage`, `InventoryPage`).
- **Pendientes diferidos fuera de código:** credenciales externas de
  webhooks WhatsApp/Email por entorno, definición funcional de `DOM-M1`
  (notas de crédito en devoluciones de ventas pagadas) y auditoría previa en
  producción antes de planificar una migración `V66`.

## Leyenda

- Verde: implementado y probado.
- Azul: acceso administrativo global.
- Violeta: ownership seller.
- Amarillo: pendiente o previsto.
- Flecha continua: dependencia o flujo implementado.
- Flecha punteada: integración o funcionalidad pendiente.
