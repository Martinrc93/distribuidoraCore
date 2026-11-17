# Plan de implementación y viabilidad — requerimientos Isma y Leo

Fecha: 2026-11-12. Rama base: `support`.

## Decisión de alcance

Se descarta la estrategia «base compartida + dos branches». **Todos los
requerimientos (compartidos, de Isma y de Leo) se implementan en una única base
de código para ambos clientes.** Consecuencias:

- No hay ramas por cliente ni código condicionado por cliente.
- Las diferencias de operación entre clientes, si aparecen, se resuelven con
  **permisos por rol** y **configuración en base de datos** de cada instancia
  (Sistema A / Sistema B ya tienen bases y secretos separados), nunca con `if
  cliente == ...`.
- Lo que antes era «exclusivo» pasa a ser comportamiento general: cada cambio que
  modifique una regla documentada (stock, listas, permisos del vendedor) debe
  actualizar `docs/domain/functionalities.md`, el contrato en `docs/api/` y, si
  cambia una decisión, el ADR correspondiente.

## Estado actual relevado (verificado en código)

| Área | Lo que ya existe | Brecha principal |
|---|---|---|
| Listas de precios | Crear lista (código + nombre), renombrar, activar/desactivar, precio por producto con vigencia futura e historial (`PricingCommandService`, `PriceListsPage`) | Una lista nueva nace vacía (`prices()` solo devuelve productos con precio). Edición de precio solo por modal, uno por uno. No se puede editar en listas inactivas. Solo se renombra (no se edita código). |
| Documentos | PDF A4 y ticket 80 mm **solo de pedidos/ventas** (`DocumentController`, OpenPDF). El frontend descarga con `apiGetBlob` (siempre descarga) | No hay impresión directa, ni documentos de lista de precios, estado de cuenta o compras. |
| Pagos | Pago a cuenta parcial FIFO o por venta (`POST /account-payments`), liquidación total (`/settlement`), cobro en entrega con deuda anterior | **El diálogo «Saldar cuenta» de Clientes exige pagar el saldo completo** («El pago debe cubrir el saldo completo»): causa muy probable del error de pagos parciales de Isma. La confirmación de pedido envía `payments: []`; `previousBalanceAmount` es solo informativo en el remito. |
| Stock | Ajuste por delta firmado con motivo obligatorio (`InventoryCommandService.adjust`), movimientos append-only | No hay ajuste a cantidad final, ni tipo de movimiento de merma. El selector de productos del pedido usa `includeStock=false`. |
| Compras | Pedidos a proveedor con snapshots, idempotencia y «último pedido» (`purchasing`, V35) | No se pueden editar. Sin recepción ni impacto en stock. |
| Pedidos | Estados `CONFIRMED/DELIVERED/CANCELLED`, intentos de entrega, `seller_id` | No se guarda quién creó el pedido (admin vs vendedor). No hay etapa de preparación. El vendedor recibe `403` si cambia la lista (regla de seguridad deliberada, commit `89dcd91`). |
| Permisos | Roles `ADMIN`/`SELLER`, permisos en DB, endpoints para reemplazar permisos por rol | 56 endpoints chequean `ADMIN_ALL` directamente: los permisos son gruesos. No hay alta de roles nuevos ni UI de edición. |
| Modales | `ConfirmationDialog`/`StockAdjustmentDialog` usan `<dialog>` nativo; el resto son `div.modal-backdrop` por pantalla | `.modal-backdrop .panel` no tiene `max-height` ni scroll interno: un modal más alto que la ventana queda cortado arriba y no se puede desplazar. Causa probable del reporte de Isma. |
| Tipografía | Tamaños fijos en px en `styles.css` (la guía exige valores exactos) | No hay una variable única de escala. |
| Reportes | Resumen y Dashboard admin (top 5 productos, deudores, etc.) | Sin historial de artículos vendidos ni total comprado por cliente en un rango. |

## Fases

Tamaños aproximados para una persona: **S** ≤ 1 día · **M** 2–4 días ·
**L** 1–2 semanas · **XL** > 2 semanas. Son órdenes de magnitud, no compromisos.

### Fase 0 — Reproducción y acuerdos (antes de codificar)

1. Reproducir con Isma: «edición de precios de productos asociados a listas» y
   «detalle de boletas». Sin el caso concreto no hay corrección verificable.
2. Confirmar con Leo el modelo de revendedor y la base de cálculo de comisiones
   (ver «Decisiones pendientes»).
3. Confirmar formatos por documento (tabla de la Fase 3).

### Fase 1 — Correcciones y mejoras rápidas (bajo riesgo)

**Estado (2026-11-12): implementada en `support`, sin commit.** Resumen:

- 1.1 `POST /settlement` acepta `partial: true`; «Registrar pago» en Clientes admite
  pagos parciales combinados (FIFO, una transacción).
- 1.2 Causa encontrada: el formulario de producto y los de crear/renombrar lista
  eran paneles insertados arriba de la página; al editar tras hacer scroll quedaban
  fuera de la vista. Nuevo `FormModal` (diálogo nativo) y límite de alto con scroll
  para `.modal-backdrop > .panel`.
- 1.3 Menú y títulos: «Stock» (ruta `/products`) y «Compras» (ruta `/supplier-orders`).
  Las rutas no cambiaron para no romper enlaces guardados.
- 1.4 Dashboard, Resumen y ficha: «Por cobrar» en lugar de «Deuda», sin rojo.
- 1.5 `targetQuantity` en ajustes (cantidad final por defecto en la UI); motivo opcional.
- 1.6 El selector de productos del pedido muestra «Stock: N» y el stock disponible
  del producto elegido (`includeStock=true`).
- 1.7 Preferencia «Texto grande» (zoom 1,125 en contenido y diálogos).
- 1.8 Etiquetas: «Editar precio»/«Ver historial», «Descargar remito A4»,
  «Descargar ticket 80 mm», «Enviar por email o WhatsApp».

Pendiente de verificar: E2E comercial con PostgreSQL real y prueba en la instancia
con datos de Isma/Leo (la reproducción original de los bugs no estuvo disponible).

| # | Cambio | Tamaño | Notas de implementación |
|---|---|---|---|
| 1.1 | Pagos parciales de cuenta | S | En modo `account`, usar `POST /account-payments` (FIFO o venta elegida) cuando el importe es menor al saldo; mantener `/settlement` para el total. Para varios medios en un pago parcial, extender el endpoint a una lista de pagos en una transacción (mismo patrón que `settlement`). Actualizar el texto de ayuda. |
| 1.2 | Modales con scroll | S–M | Agregar `max-h-[calc(100dvh_-_40px)] overflow-y-auto` al patrón base y crear un componente compartido `Modal` sobre `<dialog>` + portal (como `ConfirmationDialog`); migrar los modales por pantalla. Documentarlo en la guía de UI, que hoy dice que no existe un `Modal` compartido. |
| 1.3 | Renombres | S | «Productos» → «Stock»; «Pedidos a proveedores» → «Compras» (menú, títulos, rutas con redirección de las anteriores, tests E2E). |
| 1.4 | Deudas como «por cobrar» | S | Textos y signos en Clientes, ficha y dashboard: «Saldo por cobrar» en lugar de presentarlo como pérdida. Sin cambios contables. |
| 1.5 | Ajuste de stock a cantidad final | M | Nuevo campo `targetQuantity` en el ajuste: el backend bloquea el saldo, calcula el delta y registra `MANUAL_ADJUSTMENT` (el historial sigue append-only). Motivo opcional. Mantener compatibilidad con `quantity`. Actualizar `inventory.md` y `functionalities.md` («todo ajuste requiere motivo»). |
| 1.6 | Stock visible al cargar pedidos | S | Usar `includeStock=true` en el selector del pedido; mostrar saldo junto al producto. Medir el costo de la consulta en catálogos grandes. |
| 1.7 | Tipografía más grande | M | Preferencia «Tamaño de texto: Normal/Grande» persistida igual que el tema, aplicada como escala en la raíz. Requiere revisión visual en 320/640/760/1050 px porque los tamaños están en px fijos. Documentar en la guía. |
| 1.8 | Claridad de botones | S–M | Inventario de acciones ambiguas (p. ej. «Compartir comprobante», botones «Editar/Historial» de listas) y unificar etiquetas e íconos según la guía. |

### Fase 2 — Listas de precios

**Estado (2026-11-12):** 2.3 implementado con la regla acordada con el usuario
(V38 `price_list_follows`). Al crear o activar una lista, los productos toman el
precio de la General con confirmación previa; quedan «Sin revisar» / «Sin cambios»
y siguen a la General hasta tener precio propio; un aviso por producto y lista
antes de cambiar la General, que no se repite una vez confirmado. Como las
instancias ya tienen 10 listas, el camino real es activar una inactiva. En 2.1 se
corrigió el rechazo de importes con coma decimal («1250,50»), causa probable del
bug de Isma (sin confirmar con ella).

**Decisiones del usuario (2026-11-12):** 2.5 implementado (filtros por categoría,
marca y nombre en la tabla de precios). 2.6 implementado tras confirmar que lo
pidió Leo: permiso `ORDER_PRICE_LIST_CHANGE` (V39), otorgado a ADMIN y SELLER en
ambas instancias y revocable por rol. Descartados: el aumento masivo por % de 2.4
(«no se manejan de esa manera») y 2.2 (renombrar, activar y editar precios
alcanza; el código es un identificador interno). Queda abierto si se quiere la
edición en línea de precios de 2.4 sin el aumento por %.

| # | Cambio | Tamaño | Notas |
|---|---|---|---|
| 2.1 | Corregir edición de precios | S–M | Depende de la reproducción (Fase 0). Sospechas a verificar: listas inactivas rechazan la edición (`La lista de precios no está activa`), precio menor al costo, formato decimal con coma. |
| 2.2 | Editar lista después de crearla | S | Hoy solo se renombra. Unificar «Editar lista» (nombre y, si se acepta, código; el código `GENERAL` sigue protegido). |
| 2.3 | Lista nueva con todos los productos | M | Al crear, elegir **lista de origen** (por defecto `GENERAL`) y un **porcentaje de ajuste** opcional; copiar el precio vigente de todos los productos activos en la misma transacción, respetando `precio ≥ costo`. Alternativa sin copia: listar todos los productos aunque no tengan precio, marcados «sin precio». |
| 2.4 | Editar precios desde la lista | M | Edición en línea en la tabla (celda editable, Enter guarda y baja a la siguiente fila, Escape cancela), más «aumento masivo %» sobre la lista o un rubro. Nuevo patrón reutilizable → documentarlo en la guía. Endpoint batch `PUT /api/pricing/lists/{id}/prices` para no hacer N llamadas. |
| 2.5 | Filtro por rubro | S | Filtrar la lista por categoría/marca (hoy no tiene filtros). Base para imprimir por rubro. |
| 2.6 | Vendedor cambia la lista del pedido | S–M | Hoy se rechaza con `403` a propósito. Pasarlo a un permiso nuevo `ORDER_PRICE_LIST_CHANGE`, concedido o no por instancia. Actualizar ADR-007 y `read-projections.md`. |

### Fase 3 — Servicio de impresión y descarga

**Estado (2026-11-16): implementada.** `DocumentActions` (imprimir sin descargar /
descargar) en pedidos, listas de precios y ficha del cliente; PDF de lista de
precios filtrada por categoría, marca y nombre (`/api/documents/price-lists`);
estado de deuda del cliente A4 y 80 mm (`/api/documents/customers/{id}/statement`).
Contrato en `docs/api/documents.md`. Pendiente opcional: PDF de compra.

Backend: generalizar `document` con un modelo de documento por tipo y los
renderers A4/80 mm existentes. Frontend: `printDocument(path)` (blob → iframe
oculto → `print()`, con apertura en pestaña nueva como alternativa en móviles)
junto a `apiGetBlob`, y un componente `DocumentActions` (Imprimir · Descargar ·
Compartir) usado en todas las pantallas.

| Documento | A4 | 80 mm | Tamaño | Notas |
|---|---|---|---|---|
| Pedido / remito | ✔ existe | ✔ existe | S | Solo agregar impresión directa y acciones desde el listado y el detalle. |
| Lista de precios (filtrada por rubro) | ✔ | — | M | PDF descargable: cubre la consulta sin internet. |
| Estado de deuda del cliente | ✔ | ✔ | M–L | Requiere la proyección cronológica de cuenta (débitos, créditos, pagos) que `frontend-backend-gaps.md` marca como faltante. |
| Compra a proveedor | ✔ | — | S | Opcional; reutiliza el mismo servicio. |

«Sin internet» se resuelve con el PDF descargado. Una PWA con caché offline de
precios es una iniciativa aparte (L) y no se recomienda ahora.

### Fase 4 — Pedidos y carga ágil

**Estado (2026-11-16):** implementados 4.1 (carga con teclado; Enter ya no confirma
el pedido desde el selector), 4.2 (↑/↓ en el listado, ← / → y botones en el detalle),
4.3 (V41 `created_by_user_id`/`created_by_admin`, columna y filtro «Cargado por»),
4.4 (cobro opcional al confirmar: «Pago de este pedido» imputado solo a la venta
nueva y «Pago de deuda anterior» imputado por FIFO a deudas previas antes de crear
la venta; ambos en la huella de idempotencia), 4.6 (filtro «Pagados sin entregar»
y badge «Pagado») y 4.7 (acciones de documentos desde el pedido, en Fase 3).
4.5 implementada: V42 con preparación separada y stock al preparar, entrega implícita,
edición según preparación y revisión administrativa de pedidos creados por vendedores.
La revisión no bloquea operaciones: conserva un distintivo hasta la confirmación del administrador.

| # | Cambio | Tamaño | Notas |
|---|---|---|---|
| 4.1 | Teclado en la carga | M | Producto → cantidad → Enter agrega y vuelve al buscador; Tab ordenado; sin mouse. `SearchableSelect` ya maneja flechas y Enter. |
| 4.2 | Navegar pedidos con flechas | S–M | Flechas ↑/↓ en el listado y «anterior/siguiente» en el detalle, respetando filtros y página de la URL. |
| 4.3 | Admin vs vendedor | M | Migración: `orders.orders.created_by_user_id`. Históricos: completar desde auditoría si existe el actor; si no, «Sin dato». Badge y filtro en Pedidos. |
| 4.4 | Pago al generar el pedido + saldos anteriores | M–L | Habilitar pagos en la confirmación (hoy `payments: []`) reutilizando la imputación de la entrega: primero el pedido nuevo, luego deuda anterior por antigüedad. Separar en la UI «pago de este pedido» y «pago a deuda anterior» (punto 4 del documento original). Revisar idempotencia: el fingerprint debe incluir los pagos. |
| 4.5 | Preparación separada de la entrega | L | Implementada con V42: `PENDING` / `PREPARED`, fecha/usuario, stock al preparar y entrega desde pendiente en una transacción. Históricos preparados por equivalencia. `ORDER_PREPARE` para ADMIN/SELLER; revisión administrativa independiente, distintivo y panel en Resumen. No se agregó despacho porque la entrega ya tiene su estado comercial. |
| 4.6 | Pagados no entregados | S | Filtro en Pedidos: `CONFIRMED` con `paid >= total`. Ya es un estado posible hoy; falta visibilidad. |
| 4.7 | Acciones desde pedidos | S | Enviar, imprimir y descargar en la fila y el detalle (`DocumentActions`). |

### Fase 5 — Permisos configurables

`ORDER_PREPARE` y `ORDER_REVIEW` se incorporaron con 4.5 como permisos propios
permanentes (5.1 parcial). La UI de roles (5.2) está implementada: consulta y edición
de roles existentes, restricciones derivadas del backend, confirmación de diferencias,
protección de concurrencia y cierre de sesiones. 5.3 incorpora alta de roles
personalizados y su asignación a usuarios, limitados a datos asignados mediante
perfil seller y sin heredar permisos de SELLER. El 2026-11-17 comenzó 5.4 por
Pedidos y ventas: permisos independientes para editar, cancelar, reactivar,
devolver, modificar precios/descuentos y exportar/enviar comprobantes, manteniendo
el alcance asignado. Continúa con Clientes y cuentas corrientes: siete permisos
V44, altas asignadas al perfil propio, ficha/historial, saldo restringido y pagos
combinados independientes. Stock/catálogo, compras/proveedores y administración
siguen pendientes. Ninguna función nueva debe reutilizar un permiso ajeno como
solución temporal.

| # | Cambio | Tamaño | Notas |
|---|---|---|---|
| 5.1 | Permisos nuevos | M | Cada función nueva nace con su permiso (`ORDER_PRICE_LIST_CHANGE`, `STOCK_SHRINKAGE`, `COMMISSION_VIEW`, `REPORTS_VIEW`, `PURCHASE_EDIT`, …) en lugar de `ADMIN_ALL`. |
| 5.2 | UI de roles | M | Implementada: `/admin/roles`, solo administradores, catálogo completo, modal con permisos obligatorios/reservados, confirmación de altas/bajas, descarte y cierre de sesiones. `expectedPermissionCodes` evita sobrescribir cambios concurrentes; la API informa si se revocó la sesión propia. |
| 5.3 | Roles nuevos | M | Implementada: alta con catálogo de permisos y alcance asignado, selección en altas/invitaciones y cambio de rol conservando perfil e historial. Sin permisos administrativos ni acceso global. |
| 5.4 | Desacoplar `ADMIN_ALL` | XL | En curso por área. Pedidos y ventas: ocho permisos V43, controles de alcance, UI y conservación de snapshots. Clientes/cuentas: siete permisos V44, asignación propia, saldo/historial restringidos y pagos combinados. Revisión administrativa conserva ADMIN_ALL por decisión de Mateo. Stock/catálogo, compras/proveedores y administración siguen pendientes; no migrar todas las áreas de una vez. |

### Fase 6 — Stock y compras

| # | Cambio | Tamaño | Notas |
|---|---|---|---|
| 6.1 | Mermas | M | Movimiento `SHRINKAGE` con motivo obligatorio, permiso `STOCK_SHRINKAGE` (solo admins por defecto), reporte de mermas por período. Comparte `InventoryMovementService` con los ajustes, con operaciones diferenciadas. |
| 6.2 | Editar compras | M | `PUT /api/supplier-orders/{id}` con auditoría y nuevos snapshots. Hoy no impactan stock, así que no hay deltas. |
| 6.3 | Pedir productos para reponer | S–M | **Ya existe** (Compras). Agregar «Generar compra» desde productos con stock bajo o desde la cobertura del dashboard. |
| 6.4 | Recepción de compras con ingreso de stock | L | No fue pedido explícitamente, pero completa reposición y «Stock». Pendiente de confirmar. |

### Fase 7 — Reportes

| # | Cambio | Tamaño | Notas |
|---|---|---|---|
| 7.1 | Artículos vendidos con historial | M | Endpoint paginado por producto y rango (unidades, importe, devoluciones), detalle por venta. |
| 7.2 | Total comprado por cliente entre fechas | M | Total y desglose por pedido/producto; enlazado desde la ficha del cliente. Imprimible con la Fase 3. |

### Fase 8 — Comisiones

Tamaño **L**. Configuración por producto (`FIXED` por unidad o `PERCENT`), con
valor por defecto opcional por categoría. **Snapshot en la línea de venta** al
confirmar o editar, para que los cambios de configuración no alteren el
historial. Motor de cálculo aislado (`CommissionCalculator`) para poder sumar
reglas después. Reporte por vendedor y período. Bloqueado por las decisiones de
base de cálculo.

### Fase 9 — Revendedores

Tamaño **XL**, el de mayor riesgo. Propuesta a validar con Leo:

- Rol `RESELLER` con perfil comercial (reutiliza `SellerProfile` con tipo).
- El revendedor tiene **su propia cuenta corriente** (una cuenta cliente vinculada).
- Clientes del revendedor: sus pedidos se atribuyen al revendedor y, si «asume la
  deuda», el débito va a la cuenta del revendedor en lugar de a la del cliente
  final, como asiento explícito del ledger (nunca editando saldos).
- Toca invariantes del ledger, imputación FIFO, alcance de datos por vendedor y
  dashboard. Requiere su propio spec y ADR antes de codificar.

## Orden recomendado

1. Fase 0 + Fase 1: valor inmediato para ambos y menor riesgo.
2. Fase 2 y Fase 3, en paralelo si las hacen personas distintas: tocan módulos
   distintos (`pricing` frente a `document` y componentes compartidos).
3. Fase 4.
4. Fase 5.1–5.3, antes de comisiones y revendedores, que dependen de permisos y roles.
5. Fases 6 y 7.
6. Fase 8.
7. Fase 9.

## Viabilidad

- **Viable sin cambios de arquitectura:** Fases 1, 2, 3, 6 y 7, y la mayor parte
  de la 4. El monolito modular, el ledger append-only y el módulo `document`
  soportan estas extensiones.
- **Viable con cuidado:** 4.4 (pagos en la confirmación + idempotencia), 4.5
  (preparación sin romper los estados documentados) y 2.6 (relaja una regla de
  seguridad: solo con permiso explícito).
- **Riesgo alto o definición incompleta:** comisiones (base de cálculo),
  revendedores (modelo contable) y permisos de Isma (sin detalle). No empezar
  sin spec acordado.
- **Riesgos transversales:**
  - aplicar todo a ambos cambia reglas para un cliente que no las pidió (ajuste
    por cantidad final, vendedor con cambio de lista, preparación). Mitigarlo con
    permisos y valores por defecto que conserven el comportamiento actual;
  - cada migración Flyway afecta a las dos instancias (A/B) al actualizar `main`;
  - la tipografía con px fijos puede romper layouts en los cortes de 760 y 1050 px.

## Decisiones

Respuestas del usuario (2026-11-12):

1. **Lista nueva:** resuelto e implementado (siguen a la General, ver Fase 2).
2. **Comisiones:** la porcentual se calcula **sobre la ganancia** (precio de venta
   menos costo); el usuario lo indicó con duda («creo»), confirmar con Leo antes de
   implementar. Implicancia: hoy las ventas **no guardan el costo** al momento de la
   venta (`dashboard-analytics.md` lo señala), así que hay que agregar un snapshot de
   costo por línea; las ventas anteriores no tendrán ganancia calculable. Pendiente:
   si la comisión fija es por unidad y si se liquida al confirmar o al cobrar.
3. **Revendedor:** la deuda es **siempre** del revendedor. El revendedor tiene
   clientes asociados; cómo les cobra él no le importa al sistema. Los pedidos de
   esos clientes debitan la cuenta corriente del revendedor.
4. **Preparación:** el stock se descuenta **al preparar**, no al confirmar; si el
   pedido se cancela después de preparado, se revierte el movimiento. Implementado
   con V42 y ADR-006/007 actualizados. Decisiones confirmadas el 2026-11-16:
   entregar desde pendiente prepara y descuenta en ese momento; el administrador
   edita pendientes sin stock y preparados con deltas. Vendedores pueden preparar
   mediante `ORDER_PREPARE`. Los pedidos creados por vendedores conservan el
   distintivo hasta revisión manual del administrador al cierre del día, sin
   bloquear creación, preparación, cobro ni entrega. La revisión conserva fecha,
   administrador y auditoría; no altera stock ni estados comerciales.
5. **Impresión 80 mm:** sin definir; usar 80 mm donde hoy existe (pedido) y para el
   estado de deuda.
6. **Permisos:** inicialmente postergados para revisarlos en detalle. El 2026-11-16
   Mateo autorizó retomar 5.2 y 5.3, implementadas. Confirmó que los roles
   personalizados quedan limitados a datos asignados. Desacoplamiento por área
   de `ADMIN_ALL` (5.4) incluye Pedidos y ventas y Clientes/cuentas; Stock/catálogo,
   compras/proveedores y administración siguen pendientes.
7. **Compras:** sí. Una compra **confirmada** ingresa el stock de lo comprado. Hace
   falta un estado (borrador / confirmada) y que editar o anular una compra
   confirmada genere movimientos compensatorios.
   **Implementado (2026-11-16, 6.2–6.4):** V40 con estados DRAFT / CONFIRMED /
   CANCELLED / LEGACY (compras previas, sin efecto en stock), movimientos
   `PURCHASE`, `PURCHASE_ADJUSTMENT` y `PURCHASE_CANCELLATION`, edición y anulación,
   cantidades en múltiplos de 0,5 y «Agregar productos sin stock» en el formulario.
8. **Edición de precios en la tabla (2.4):** sí, como número simple, **nunca menor al
   costo**. Además, al modificar un costo que queda por encima del precio de alguna
   lista, debe abrirse un **modal para corregir esos precios en el momento**.
   **Implementado (2026-11-16):** edición en la fila con Enter/Escape y validación
   contra el costo (la API de precios devuelve `cost` solo a administradores) y
   `CostPriceFixDialog` en el formulario de producto.

## Verificación por fase

- Backend: `mvn clean test` y los casos PostgreSQL opt-in con `POSTGRES_TEST_URL`
  para migraciones y ledger. ArchUnit debe seguir verde.
- Frontend: `npm test`, `npm run build` y Playwright comercial (escritorio y móvil).
- UI: checklist de la guía (teclado, foco, Escape, 320/640/760/1050 px, temas).
- Documentación: contratos en `docs/api/`, `functionalities.md`, ADR-006/007/008 y
  la guía de UI se actualizan en el mismo cambio.
