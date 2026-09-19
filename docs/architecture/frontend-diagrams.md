# Diagramas del frontend

## Propósito

Este documento reúne los diagramas técnicos y funcionales del frontend. Sirve
como referencia para desarrollar nuevas features sin mezclar server state,
form state, URL state, sesión y estado visual.

La implementación debe respetar también:

- [`frontend-architecture.md`](./frontend-architecture.md)
- [`module-boundaries.md`](./module-boundaries.md)
- [`api-guidelines.md`](../api/api-guidelines.md)
- [`functionalities.md`](../domain/functionalities.md)

## 1. Arquitectura técnica

```mermaid
flowchart TB
    Browser[Browser]

    subgraph SPA[React SPA]
        Main[main.tsx]
        Providers[App Providers]
        Router[React Router]
        Shell[Authenticated App Shell]

        subgraph Features[Feature Modules]
            Auth[auth]
            Users[users]
            Sellers[sellers]
            Customers[customers]
            Catalog[catalog]
            Inventory[inventory]
            Orders[orders]
            Sales[sales]
            Payments[payments]
            Documents[documents]
            Audit[audit]
            Settings[settings]
        end

        subgraph Shared[Shared UI and Infrastructure]
            UI[Shared UI Components]
            Tables[Paginated Tables]
            Forms[Form Components]
            Permissions[Permission Guards]
            Http[HTTP Client]
            Errors[Error Mapping]
        end

        Query[TanStack Query Cache]
        AuthState[Auth Session Context]
        UrlState[URL State]
        FormState[React Hook Form State]
    end

    API[Spring Boot REST API]

    Browser --> Main
    Main --> Providers
    Providers --> Router
    Router --> Shell
    Router --> Auth
    Shell --> Features

    Features --> UI
    Features --> Tables
    Features --> Forms
    Features --> Permissions
    Features --> Http

    Http --> Errors
    Http --> API
    Features --> Query
    Auth --> AuthState
    Features --> UrlState
    Forms --> FormState
```

## 2. Dependencias internas

```mermaid
flowchart LR
    App[app] --> Features[features]
    App --> Shared[shared]
    Features --> Shared
    Features --> Query[TanStack Query]
    Features --> Http[HTTP Client]
    Shared --> Http
    Shared --> UI[UI primitives]

    Auth[auth] --> Session[Auth Session]
    Orders[orders] --> CustomerContract[customer API contract]
    Orders --> CatalogContract[catalog API contract]
    Orders --> PaymentContract[payments API contract]

    Customers[customers] -. no feature internals .-> Orders
    Catalog[products] -. no feature internals .-> Orders
    Payments[payments] -. no feature internals .-> Orders
```

Reglas:

- `app` configura la aplicación, pero no contiene reglas comerciales.
- Una feature puede consumir contratos públicos de otra feature, no sus
  componentes, hooks o repositories internos.
- `shared` no puede importar una feature concreta.
- El cliente HTTP es el único lugar para headers, JWT, errores y correlation ID.
- TanStack Query administra datos del backend; no se replica todo en un store.

## 3. Navegación y rutas

```mermaid
flowchart TD
    Root[/]

    Root --> Public[Public Layout]
    Public --> Login[/login]
    Public --> Activate[/activate]
    Public --> Recover[/password-recovery]
    Public --> Reset[/password-reset]

    Root --> Protected{Authenticated Route}
    Protected --> AppShell[App Shell]

    AppShell --> Orders[/orders]
    AppShell --> OrderCreate[/orders/new]
    AppShell --> OrderDetail[/orders/:orderId]
    AppShell --> Sales[/sales]
    AppShell --> SaleDetail[/sales/:saleId]
    AppShell --> Customers[/customers]
    AppShell --> CustomerDetail[/customers/:customerId]
    AppShell --> CustomerAccount[/customers/:customerId/account]
    AppShell --> Products[/products]
    AppShell --> ProductDetail[/products/:productId]
    AppShell --> PriceLists[/price-lists]
    AppShell --> Inventory[/inventory]
    AppShell --> Movements[/inventory/movements]

    AppShell --> Admin[Admin Area]
    Admin --> Users[/admin/users]
    Admin --> Sellers[/admin/sellers]
    Admin --> Roles[/admin/roles]
    Admin --> Audit[/admin/audit]
    Admin --> Settings[/admin/settings]

    AppShell --> Unauthorized[/403]
    Root --> NotFound[/404]
```

Reglas:

- Rutas públicas no requieren sesión.
- Rutas protegidas requieren access token válido.
- La renovación fallida de sesión devuelve al usuario a `/login`.
- El acceso sin permiso muestra `/403`.
- Filtros, búsqueda, orden y paginación viven en query params.
- `/orders/new` mantiene el formulario local y no crea un borrador backend.

## 4. Estado de la aplicación

```mermaid
flowchart TB
    subgraph Server[Server State]
        Customers[Clientes]
        Products[Productos]
        PriceLists[Listas de precios]
        Inventory[Stock]
        Orders[Pedidos]
        Sales[Ventas]
        Payments[Pagos y deuda]
        Users[Usuarios]
    end

    subgraph Query[TanStack Query]
        Cache[Query Cache]
        Mutations[Mutations]
        Invalidation[Invalidation]
    end

    subgraph Client[Client State]
        Session[Sesión actual]
        Layout[Estado del layout]
        Dialogs[Modales y confirmaciones]
    end

    subgraph Forms[Form State]
        OrderForm[Formulario de pedido]
        CustomerForm[Formulario de cliente]
        PaymentForm[Formulario de pago]
        UserForm[Formulario de usuario]
    end

    subgraph URL[URL State]
        Filters[Filtros]
        Search[Búsqueda]
        Sort[Orden]
        Pagination[Paginación]
        Resource[Recurso seleccionado]
    end

    Server --> Cache
    Cache --> Mutations
    Mutations --> Invalidation
    Invalidation --> Server
    Session --> Client
    Forms --> Mutations
    URL --> Cache
    Client --> UI[Rendered UI]
    Cache --> UI
    Forms --> UI
```

| Estado | Responsable | Ejemplos |
|---|---|---|
| Server state | TanStack Query | productos, stock, pedidos, deuda |
| Form state | React Hook Form | pedido, cliente, usuario, pago |
| URL state | React Router | filtros, página, orden, búsqueda |
| Auth state | Auth context | usuario actual y sesión |
| UI state | estado local | sidebar, modal, confirmación |

No se debe guardar una copia global completa de productos, clientes, pedidos o
ventas.

## 5. Activación y login

```mermaid
sequenceDiagram
    actor Admin as Administrador
    actor User as Usuario
    participant UI as Frontend
    participant API as REST API
    participant Mail as Email Provider

    Admin->>UI: Crear usuario
    UI->>API: POST /users
    API->>API: Crear usuario INVITED
    API->>Mail: Enviar link de activación
    User->>UI: Abrir link
    UI->>API: Validar token
    User->>UI: Definir contraseña
    UI->>API: Activar usuario
    API-->>UI: Usuario ACTIVE
    User->>UI: Ingresar credenciales
    UI->>API: Login
    API-->>UI: Access JWT + refresh token
```

Estados visuales relevantes:

```text
INVITED → ACTIVATING → ACTIVE
ACTIVE → LOCKED
LOCKED → ACTIVE
ACTIVE → INACTIVE
```

El frontend nunca recibe ni muestra contraseñas temporales. La activación usa un
link de un solo uso con expiración.

## 6. Permisos y navegación contextual

```mermaid
flowchart TD
    Session[Sesión JWT] --> Authorities[Permisos efectivos]

    Authorities --> AdminAll{ADMIN_ALL}
    Authorities --> SellerPermissions[Permisos de vendedor]

    AdminAll --> AdminUI[Todas las áreas y acciones]
    SellerPermissions --> AssignedCustomers[Clientes asignados]
    SellerPermissions --> CreateOrder[Crear y confirmar pedido]
    SellerPermissions --> RegisterPayment[Registrar pagos]
    SellerPermissions --> Deliver[Marcar entregado]
    SellerPermissions --> Documents[Generar documentos]

    AdminUI --> UserManagement[Usuarios y vendedores]
    AdminUI --> PriceManagement[Listas y precios]
    AdminUI --> StockAdjustment[Ajustar stock]
    AdminUI --> Discounts[Aplicar descuentos]
    AdminUI --> CancelSale[Cancelar venta no pagada]
    AdminUI --> Audit[Ver auditoría]
    AdminUI --> Settings[Configuración]
```

El vendedor no visualiza acciones para modificar pedidos, precios, descuentos,
stock, listas, auditoría o usuarios. El backend siempre vuelve a validar el
permiso.

## 7. Creación y confirmación de pedido

```mermaid
stateDiagram-v2
    [*] --> EmptyForm
    EmptyForm --> CustomerSelected: seleccionar cliente
    CustomerSelected --> LinesEditing: cargar productos
    LinesEditing --> PriceResolved: resolver lista y precios
    PriceResolved --> DiscountsEditing: aplicar descuentos permitidos
    DiscountsEditing --> ReadyToConfirm: validación local correcta

    ReadyToConfirm --> Confirming: enviar confirmación
    Confirming --> Confirmed: respuesta exitosa
    Confirming --> FormError: error de validación o negocio
    Confirming --> NetworkError: error de red

    FormError --> ReadyToConfirm: corregir datos
    NetworkError --> ReadyToConfirm: reintentar
    Confirmed --> OrderDetail
    OrderDetail --> [*]
```

```mermaid
sequenceDiagram
    actor User as Usuario
    participant Form as Order Form
    participant Query as TanStack Query
    participant API as HTTP Client
    participant Backend as REST API

    User->>Form: Selecciona cliente y productos
    Form->>Form: Valida campos locales
    User->>Form: Presiona Confirmar
    Form->>Query: Ejecuta mutation
    Query->>API: POST /orders/confirmation
    API->>Backend: JWT + payload + Idempotency-Key
    Backend-->>API: Pedido, venta, pagos y saldo
    API-->>Query: Resultado confirmado
    Query->>Query: Invalida orders, sales, inventory y account
    Query-->>Form: Éxito
    Form->>User: Muestra resumen y acciones
```

La confirmación no persiste borradores. Si falla, el formulario conserva los
datos locales para permitir reintentar.

## 8. Flujo de pagos y cuenta corriente

```mermaid
flowchart TD
    Account[Cuenta corriente del cliente] --> Balance[Saldo actual]
    Account --> Debts[Deudas pendientes]
    Account --> History[Historial de movimientos]

    Debts --> Specific[Seleccionar deuda específica]
    Debts --> FIFO[Aplicar al saldo más antiguo]
    Specific --> PaymentForm[Formulario de pago]
    FIFO --> PaymentForm

    PaymentForm --> Method{Método}
    Method --> Cash[Efectivo]
    Method --> Transfer[Transferencia]
    Method --> CustomerAccount[Cuenta corriente]
    Transfer --> TransferData[Número y nombre opcionales]
    Cash --> Amount[Monto]
    TransferData --> Amount
    CustomerAccount --> Amount

    Amount --> Validate{¿Monto <= saldo?}
    Validate -->|No| Error[Mostrar error]
    Validate -->|Sí| Submit[Registrar mutation]
    Submit --> Success[Actualizar cuenta y ventas]
```

Después de registrar un pago se invalidan las queries de cuenta corriente, deuda,
venta y resumen del cliente.

## 9. Entrega e intentos

```mermaid
stateDiagram-v2
    [*] --> CONFIRMED
    CONFIRMED --> DeliveryAttempt: iniciar intento
    DeliveryAttempt --> CONFIRMED: no entregado + observación
    DeliveryAttempt --> DELIVERED: entrega exitosa
    DELIVERED --> [*]
```

La fecha y hora provienen del backend. La observación es obligatoria cuando el
resultado es no entregado. El intento fallido no cambia el estado principal.

## 10. Documentos, impresión y WhatsApp

```mermaid
flowchart TD
    Sale[Venta confirmada] --> Action{Acción del usuario}
    Action --> Download[Descargar]
    Action --> A4[Imprimir A4]
    Action --> Ticket58[Imprimir ticket 58 mm]
    Action --> Ticket88[Imprimir ticket 88 mm]
    Action --> WhatsApp[Enviar WhatsApp]

    Download --> Render[Renderizar bajo demanda]
    A4 --> Render
    Ticket58 --> Render
    Ticket88 --> Render
    WhatsApp --> Render

    Render --> Temporary[Archivo temporal]
    Temporary --> Output[Descarga, impresión o envío]
    Output --> Cleanup[Eliminar temporal]
```

Las acciones documentales no se ejecutan automáticamente al confirmar una
venta. Un error de renderer o WhatsApp no modifica la venta.

## 11. Administración

```mermaid
flowchart TD
    Admin[Administrador] --> Users[Usuarios]
    Admin --> Sellers[Vendedores]
    Admin --> Customers[Clientes]
    Admin --> Catalog[Productos y categorías]
    Admin --> PriceLists[Listas de precios]
    Admin --> Stock[Ajustes de stock]
    Admin --> Audit[Auditoría]
    Admin --> Settings[Configuración]

    Users --> Activate[Activar o inhabilitar]
    Users --> Roles[Roles y permisos]
    Users --> Sessions[Revocar sesiones]

    Sellers --> Profile[Crear SellerProfile]
    Sellers --> Assign[Asignar vendedor a cliente]

    PriceLists --> Rename[Renombrar]
    PriceLists --> Update[Modificar precios]
    PriceLists --> Disable[Dar de baja o reactivar]

    Stock --> Reason[Motivo obligatorio]
    Settings --> CreditLimit[Límite de crédito global]
```

## 12. Estados visuales comunes

Cada pantalla de consulta o mutación debe contemplar:

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Loading: iniciar consulta
    Loading --> Success: respuesta válida
    Loading --> Empty: colección sin resultados
    Loading --> Error: error técnico o de negocio
    Loading --> Unauthorized: 401/403
    Error --> Loading: reintentar
    Unauthorized --> Login: sesión inválida
    Success --> Loading: refetch
    Empty --> Loading: cambiar filtro
```

Los errores de validación se muestran junto al campo. Los errores de negocio se
muestran como mensajes accionables y los errores técnicos ofrecen reintento.

## 13. Reglas responsive

- Las tablas deben tener una representación usable en mobile.
- Los formularios de pedido deben priorizar búsqueda y carga de líneas.
- Las acciones irreversibles deben permanecer visibles y confirmables.
- El layout administrativo puede usar sidebar en desktop y drawer en mobile.
- Los filtros deben poder abrirse como panel en pantallas pequeñas.
- Los modales no deben contener workflows largos; usar páginas cuando el flujo
  sea complejo.
