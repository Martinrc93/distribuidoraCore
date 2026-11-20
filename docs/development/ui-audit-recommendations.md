# Auditoría Integral de Interfaz (UI/UX) y Recomendaciones de Mejora — Distribuidora

> [!NOTE]
> Este documento registra el recorrido completo por las **26 pantallas** de la aplicación web **Distribuidora** (módulos de **Autenticación**, **Operación**, **Catálogo** y **Administración**), incluyendo pruebas en **desktop (`1280×800`)**, **modo oscuro (`[data-theme="dark"]`)** y **vista móvil (`390×844`)**. Todos los hallazgos fueron contrastados contra la [Guía de estilo de interfaz](ui-style-guide.md), el [Checklist de Frontend](frontend-checklist.md) y la hoja de estilos global [styles.css](../../frontend/src/styles.css).

---

## 1. Resumen Ejecutivo y Mapa de Prioridades

La interfaz posee una base visual sólida y coherente ("Cuaderno Mayor Operativo"): paleta semántica cálida en modo claro y oscuro, tipografía `Inter` con `tabular-nums`, componentes compartidos bien estructurados ([PageHeader.tsx](../../frontend/src/shared/components/PageHeader.tsx), [Panel.tsx](../../frontend/src/shared/components/Panel.tsx), [DataTable.tsx](../../frontend/src/shared/components/DataTable.tsx), [FormModal.tsx](../../frontend/src/shared/components/FormModal.tsx), [EntitySelect.tsx](../../frontend/src/shared/components/EntitySelect.tsx)) y conversión automática de tablas a tarjetas en móvil (`< 720px`).

Sin embargo, la auditoría visual detectó **8 categorías de problemas** que afectan la usabilidad diaria en resoluciones estándar de escritorio (`1280px`), la consistencia del idioma y formato, y la accesibilidad de diálogos heredados.

### Matriz de Hallazgos Priorizados

| Prioridad | Hallazgo | Pantallas afectadas | Referencia en código |
| :--- | :--- | :--- | :--- |
| **P0 · Crítico** | Renderizado literal de **`undefined`** en columna *"Clientes asignados"* | Vendedores (`/admin/sellers`) | [SellersPage.tsx:153](../../frontend/src/features/admin/SellersPage.tsx#L153) |
| **P0 · Crítico** | Fila de 4 campos en contenedor de `608px` trunca valores (`"Usar vendec..."`, `"Seleccionar lista..."`) | Nuevo pedido (`/orders/new`) | [OrderForm.tsx:560-568](../../frontend/src/features/orders/OrderForm.tsx#L560-L568), [styles.css:725](../../frontend/src/styles.css#L725) |
| **P0 · Crítico** | Uso de `.form-grid` (`1fr 1fr`) desarma el layout del formulario y estira el botón `"Guardar límite"` a `449px` | Configuración (`/admin/settings`) | [CreditLimitPage.tsx:55-61](../../frontend/src/features/admin/CreditLimitPage.tsx#L55-L61) |
| **P1 · Alto** | Sobrecarga de acciones por fila (hasta **8 botones / `780px`** en Clientes, **5 botones** en Stock y **4** en Pedidos) provocando scroll horizontal en `1280px` | Clientes (`/customers`), Stock (`/products`), Pedidos (`/orders`) | [CustomersPage.tsx:270](../../frontend/src/features/customers/CustomersPage.tsx#L270), [ProductsPage.tsx:475-491](../../frontend/src/features/products/ProductsPage.tsx#L475-L491), [OrdersPage.tsx:163](../../frontend/src/features/orders/OrdersPage.tsx#L163) |
| **P1 · Alto** | Tabla de Auditoría muestra UUIDs completos (`36` caracteres) en *Recurso* y *Request ID*, forzando `1290px` de ancho y ocultando columnas | Auditoría (`/admin/audit`) | [AuditPage.tsx:32-48](../../frontend/src/features/admin/AuditPage.tsx#L32-L48) |
| **P1 · Alto** | Botones de acción en `.panel-header` se parten en 2 líneas (`"Ver\nventas"`, `"Ver\ndeudores"`, `"Ver todos los\npendientes"`) por falta de `shrink-0 whitespace-nowrap` | Resumen (`/dashboard`), Dashboard (`/analytics`) | [styles.css:168-190](../../frontend/src/styles.css#L168-L190) |
| **P1 · Alto** | En `.toolbar`, `grid-cols-[repeat(auto-fit,minmax(180px,max-content))]` comprime los `.search-input` a `~184px`, truncando placeholders como `"Operación, recurso, ID o resu"` | Auditoría, Usuarios, Pedidos, Ventas, Clientes, Gastos, Stock | [styles.css:202-209](../../frontend/src/styles.css#L202-L209), [AuditPage.tsx:55](../../frontend/src/features/admin/AuditPage.tsx#L55) |
| **P2 · Medio** | Códigos de estado/operación crudos en inglés (`CONFIRMED`, `DELIVERED`, `LOGIN`, `TOKEN_REFRESH`, `SUCCESS`) en badges y celdas | Pedidos, Detalle de pedido, Vendedores, Auditoría, Movimientos de stock | [OrdersPage.tsx:162](../../frontend/src/features/orders/OrdersPage.tsx#L162), [OrderDetailPage.tsx:115](../../frontend/src/features/orders/OrderDetailPage.tsx#L115), [AuditPage.tsx:44-46](../../frontend/src/features/admin/AuditPage.tsx#L44-L46) |
| **P2 · Medio** | `.orders-date-filters` con `ml-auto` salta a la segunda fila dejando un hueco vacío de `~550px` en el centro de la barra de filtros | Pedidos (`/orders`), Ventas (`/sales`) | [styles.css:211-213](../../frontend/src/styles.css#L211-L213) |
| **P2 · Medio** | Buscador de Proveedores sin `<label className="field">` ni `.search-input` se estira a `912px`; campos de fecha/cantidad en Nueva compra se estiran a `449px` / `311px` | Proveedores (`/suppliers`), Nueva compra (`/supplier-orders/new`) | [SuppliersPage.tsx:99](../../frontend/src/features/suppliers/SuppliersPage.tsx#L99), [SupplierOrderForm.tsx:220-232](../../frontend/src/features/purchasing/SupplierOrderForm.tsx#L220-L232) |
| **P2 · Medio** | Inconsistencias de formato: fecha ISO `2026-11-19` en Listas de precios, saldo sin decimales (`$ 12`) en Clientes, y etiquetas `Fecha mín./máx.` vs `Desde/Hasta` en Ventas | Listas de precios, Clientes, Ventas | [PriceListsPage.tsx:432](../../frontend/src/features/pricing/PriceListsPage.tsx#L432), [CustomersPage.tsx:30](../../frontend/src/features/customers/CustomersPage.tsx#L30), [SalesPage.tsx:149](../../frontend/src/features/sales/SalesPage.tsx#L149) |
| **P2 · Medio** | Tabla de Roles renderiza una lista vertical de **29 viñetas** (`>700px` de alto por fila); Comisiones oculta el selector de plan sin aviso cuando no hay vendedor elegido | Roles y permisos (`/admin/roles`), Comisiones (`/admin/settings/commissions`) | [RolesPage.tsx:96](../../frontend/src/features/admin/RolesPage.tsx#L96), [CommissionPlansPage.tsx:207](../../frontend/src/features/admin/CommissionPlansPage.tsx#L207) |
| **P3 · Medio-Bajo** | 6 diálogos heredados usan `<div role="dialog" className="modal-backdrop">` en vez de `<dialog>` nativo ([FormModal.tsx](../../frontend/src/shared/components/FormModal.tsx) / [ConfirmationDialog.tsx](../../frontend/src/shared/components/ConfirmationDialog.tsx)), y 3 pantallas abren paneles inline arriba | Ventas, Stock, Marcas y categorías, Usuarios, Vendedores, Zonas, Proveedores | [SalesPage.tsx:152](../../frontend/src/features/sales/SalesPage.tsx#L152), [ProductsPage.tsx:526](../../frontend/src/features/products/ProductsPage.tsx#L526), [CatalogAdminPage.tsx:166](../../frontend/src/features/catalog/CatalogAdminPage.tsx#L166), [UsersPage.tsx:166](../../frontend/src/features/admin/UsersPage.tsx#L166) |
| **P3 · Medio-Bajo** | Gráfico `SalesTrend` sin marcadores de puntos ni eje Y intermedio; `EmptyState` usa el mismo ícono `—` y `min-h-[220px]` para carga, error y vacío | Dashboard (`/analytics`), Detalle de pedido (`/orders/:id`) | [AnalyticsDashboardPage.tsx:58-76](../../frontend/src/features/dashboard/AnalyticsDashboardPage.tsx#L58-L76), [EmptyState.tsx:5](../../frontend/src/shared/components/EmptyState.tsx#L5) |

---

## 2. Análisis Detallado por Módulo y Pantalla

### 2.1. Autenticación y Shell Global

1. **Login (`/login`, [LoginPage.tsx](../../frontend/src/features/auth/LoginPage.tsx))**:
   - **Mejoras**: Incluir `TextSizeToggle` y `ThemeToggle` en la esquina superior de las pantallas públicas (`/login`, `/activate-account`) y añadir botón de mostrar/ocultar contraseña.
2. **Shell y Topbar ([AppShell.tsx](../../frontend/src/app/AppShell.tsx))**:
   - **Mejoras**: El indicador `<span className="status-pill">Sistema operativo</span>` ([AppShell.tsx:171](../../frontend/src/app/AppShell.tsx#L171)) es estático; conectarlo al estado real o reemplazarlo por el identificador del usuario activo (`email`), y mostrar el email del usuario en el bloque `"Sesión activa"` del sidebar.

---

### 2.2. Módulo de Operación

1. **Resumen Operativo (`/dashboard`, [DashboardPage.tsx](../../frontend/src/features/dashboard/DashboardPage.tsx))**:
   - **Mejoras**:
     - Evitar que `"Ver todos los pendientes"` se parta en dos líneas agregando `shrink-0 whitespace-nowrap` a `.button` en [styles.css:178](../../frontend/src/styles.css#L178).
     - Diferenciar el nombre de navegación entre `"Resumen"` (`/dashboard`) y `"Dashboard"` (`/analytics`).
     - Jerarquizar las 7 tarjetas KPI separando volumen de pedidos (2 tarjetas) de métricas monetarias (5 tarjetas).

2. **Dashboard Analítico (`/analytics`, [AnalyticsDashboardPage.tsx](../../frontend/src/features/dashboard/AnalyticsDashboardPage.tsx))**:
   - **Mejoras**:
     - Corregir el quiebre en 2 líneas de `"Ver\nventas"` y `"Ver\ndeudores"` en `.panel-header`.
     - En `SalesTrend` ([AnalyticsDashboardPage.tsx:58-76](../../frontend/src/features/dashboard/AnalyticsDashboardPage.tsx#L58-L76)), renderizar `<circle>` con `<title>` en cada punto con ventas (`point.total > 0`) para identificar la fecha exacta del pico sin confusión con la etiqueta final del eje X.
     - Eliminar el punto duplicado al final de `Actualizado ${formatDateTime(data.generatedAt)}.` ([AnalyticsDashboardPage.tsx:266](../../frontend/src/features/dashboard/AnalyticsDashboardPage.tsx#L266)).

3. **Pedidos (`/orders`, [OrdersPage.tsx](../../frontend/src/features/orders/OrdersPage.tsx))**:
   - **Mejoras**:
     - Traducir el badge de la columna *Estado* (`CONFIRMED` → `Confirmado`, `DELIVERED` → `Entregado`, `CANCELLED` → `Cancelado`) en [OrdersPage.tsx:162](../../frontend/src/features/orders/OrdersPage.tsx#L162).
     - Eliminar `ml-auto` de `.orders-date-filters` cuando se produce salto de línea y agregar botón `"Todas las fechas"` / `"Limpiar filtros"`.
     - Compactar la tabla en desktop (por ejemplo, combinando *Fecha* y *Cargado por* o acortando acciones en fila) para evitar el scroll horizontal a `1280px`.

4. **Nuevo Pedido (`/orders/new`, [OrderForm.tsx](../../frontend/src/features/orders/OrderForm.tsx))**:
   - **Mejoras**:
     - Cambiar `.order-context-row` ([styles.css:737](../../frontend/src/styles.css#L737)) a grilla de 2 columnas (`2×2`) para que `Vendedor` y `Lista de precios` no se trunquen a `114px` dentro de la columna izquierda de `608px`.
     - Redistribuir `.order-line-entry` ([styles.css:745](../../frontend/src/styles.css#L745)) para dar más ancho al selector de `Artículo`.

5. **Detalle de Pedido (`/orders/:id`, [OrderDetailPage.tsx](../../frontend/src/features/orders/OrderDetailPage.tsx))**:
   - **Mejoras**:
     - Traducir el badge `CONFIRMED` en [OrderDetailPage.tsx:115](../../frontend/src/features/orders/OrderDetailPage.tsx#L115).
     - Agrupar las acciones documentales (`Imprimir`, `Descargar`, `Enviar por email`) separadas de las acciones de flujo (`Preparar pedido`, `Pasar a entregado`) en el `PageHeader`.
     - Usar una variante compacta de `EmptyState` en *"Pagos registrados"* cuando se muestra en par con *"Cuenta corriente"*.

6. **Ventas (`/sales`, [SalesPage.tsx](../../frontend/src/features/sales/SalesPage.tsx)) y Artículos Vendidos (`/sales/products`, [SoldProductsPage.tsx](../../frontend/src/features/sales/SoldProductsPage.tsx))**:
   - **Mejoras**:
     - Unificar etiquetas `Fecha mín.` / `Fecha máx.` → `Desde` / `Hasta` en [SalesPage.tsx:149](../../frontend/src/features/sales/SalesPage.tsx#L149).
     - Migrar el modal de *Detalle de venta* ([SalesPage.tsx:152](../../frontend/src/features/sales/SalesPage.tsx#L152)) a `<dialog>` nativo (`FormModal`).

7. **Compras (`/supplier-orders`), Nueva Compra (`/supplier-orders/new`) y Detalle (`/supplier-orders/:id`)**:
   - **Mejoras**:
     - En [SupplierOrderForm.tsx:220-232](../../frontend/src/features/purchasing/SupplierOrderForm.tsx#L220-L232), limitar el ancho de `Fecha de la compra` (`max-w-[200px]`) y de `Cantidad` (`max-w-[140px]`), o adoptar el layout de 2 columnas con panel de resumen lateral como en *Nuevo pedido*.

8. **Clientes (`/customers`), Ficha (`/customers/:id`) y Compras del Cliente (`/customers/:id/purchases`)**:
   - **Mejoras**:
     - Reducir las **8 acciones por fila** en [CustomersPage.tsx:270](../../frontend/src/features/customers/CustomersPage.tsx#L270) a las 3 principales (`Ver cliente`, `Registrar pago`, `Editar`), moviendo las acciones administrativas/documentales secundarias a la *Ficha del cliente* o a un menú compacto.
     - Formatear el saldo con 2 decimales (`$ 12,00`) en [CustomersPage.tsx:30](../../frontend/src/features/customers/CustomersPage.tsx#L30).

9. **Gastos (`/expenses`, [ExpensesPage.tsx](../../frontend/src/features/expenses/ExpensesPage.tsx))**:
   - **Mejoras**: Ampliar el ancho mínimo de `.search-input` dentro de `.toolbar` a `240px`.

---

### 2.3. Módulo de Catálogo

1. **Stock (`/products`, [ProductsPage.tsx](../../frontend/src/features/products/ProductsPage.tsx)) y Mermas (`/products/shrinkages`, [ShrinkagesPage.tsx](../../frontend/src/features/inventory/ShrinkagesPage.tsx))**:
   - **Mejoras**:
     - Separar `Estado` y `Acciones` en dos columnas independientes en `ProductsPage.tsx`, eliminar `min-w-[1040px]` para evitar scroll horizontal a `1280px`, migrar el modal de *Movimientos* a `FormModal` y traducir todos los tipos de movimiento de stock.

2. **Marcas y Categorías (`/catalog`, [CatalogAdminPage.tsx](../../frontend/src/features/catalog/CatalogAdminPage.tsx))**:
   - **Mejoras**: Reemplazar los dos `<div className="modal-backdrop">` ([CatalogAdminPage.tsx:166,194](../../frontend/src/features/catalog/CatalogAdminPage.tsx#L166-L194)) por `FormModal` y `ConfirmationDialog`.

3. **Listas de Precios (`/price-lists`, [PriceListsPage.tsx](../../frontend/src/features/pricing/PriceListsPage.tsx))**:
   - **Mejoras**: Formatear `"Vigente desde"` ([PriceListsPage.tsx:432](../../frontend/src/features/pricing/PriceListsPage.tsx#L432)) como `19/11/2026` en lugar de `2026-11-19`.

4. **Proveedores (`/suppliers`, [SuppliersPage.tsx](../../frontend/src/features/suppliers/SuppliersPage.tsx))**:
   - **Mejoras**: Envolver el buscador en `<label className="field"><span>Buscar proveedores</span><input className="input search-input" ... /></label>` y migrar el panel inline de alta/edición a `FormModal`.

---

### 2.4. Módulo de Administración

1. **Usuarios (`/admin/users`, [UsersPage.tsx](../../frontend/src/features/admin/UsersPage.tsx))**:
   - **Mejoras**: Migrar la confirmación de bloqueo/revocación ([UsersPage.tsx:166](../../frontend/src/features/admin/UsersPage.tsx#L166)) a `ConfirmationDialog`.

2. **Roles y Permisos (`/admin/roles`, [RolesPage.tsx](../../frontend/src/features/admin/RolesPage.tsx))**:
   - **Mejoras**: Disponer `.permission-list` en grilla multicolumna (`grid-cols-1 md:grid-cols-2 gap-x-4`) o chips agrupados con colapso para evitar filas de `700px` de alto.

3. **Vendedores (`/admin/sellers`, [SellersPage.tsx](../../frontend/src/features/admin/SellersPage.tsx))**:
   - **Mejoras**: Corregir `String(seller.assignedCustomersCount ?? 0)` en [SellersPage.tsx:153](../../frontend/src/features/admin/SellersPage.tsx#L153), migrar los 3 paneles inline (*Nuevo/Editar*, *Reasignar clientes*, *Reasignar pedidos*) a `FormModal` y sus confirmaciones a `ConfirmationDialog`, y traducir `order.status` en la lista de pedidos a reasignar.

4. **Zonas (`/admin/zones`, [ZonesPage.tsx](../../frontend/src/features/admin/ZonesPage.tsx))**:
   - **Mejoras**: Homogeneizar el estilo de los botones `"Editar"` y `"Eliminar"` en la tabla, y usar `FormModal` + `ConfirmationDialog` en lugar de edición/eliminación inline.

5. **Configuración (`/admin/settings`, [CreditLimitPage.tsx](../../frontend/src/features/admin/CreditLimitPage.tsx)) y Comisiones (`/admin/settings/commissions`, [CommissionPlansPage.tsx](../../frontend/src/features/admin/CommissionPlansPage.tsx))**:
   - **Mejoras**:
     - En `CreditLimitPage.tsx`, reemplazar `.form-grid` por layout vertical con botón en `.page-actions`.
     - En `CommissionPlansPage.tsx`, mostrar siempre el campo `"Plan de comisión"` (deshabilitado con texto de ayuda si aún no se seleccionó vendedor/revendedor o si no hay planes creados).

6. **Auditoría (`/admin/audit`, [AuditPage.tsx](../../frontend/src/features/admin/AuditPage.tsx))**:
   - **Mejoras**: Ampliar el input `"Filtrar eventos"` (`min-w-[260px]`), truncar visualmente los UUIDs de `Recurso` y `Request ID` con tooltip del valor completo, y traducir los códigos de `Operación` y `Resultado`.
