# Guía de estilos y patrones de interfaz

Referencia obligatoria para crear, modificar o revisar interfaces del proyecto.
Revisada contra el código actual el **2026-11-20**.

## Uso y mantenimiento

1. Leer esta guía antes de planificar o editar una interfaz.
2. Revisar el componente compartido y los selectores afectados en
   [`styles.css`](../../frontend/src/styles.css).
3. Reutilizar el patrón existente; agregar variantes acotadas solo cuando el
   contenido o la interacción lo requieran.
4. Verificar estados, teclado y adaptación mobile.
5. Actualizar esta guía en el mismo cambio si se incorpora o modifica un patrón
   reutilizable, indicando su implementación, alcance y comportamiento mobile.

[`AGENTS.md`](../../AGENTS.md) establece esta lectura para las tareas de agentes.
Las instrucciones explícitas del usuario tienen prioridad. Una excepción puntual
no cambia el estándar global sin una decisión explícita de ampliar su alcance.

Esta guía describe la implementación actual: **Tailwind CSS 4 y componentes
propios en `frontend/src/shared/components`**. La integración usa
`@tailwindcss/vite` en Vite y Vitest. Los patrones semánticos existentes se
definen con `@apply` en [`styles.css`](../../frontend/src/styles.css), conservando
el marcado, los valores y la cascada del diseño previo. shadcn/ui no está instalado;
su adopción requiere una decisión separada.

## Cómo escribir estilos con Tailwind

- Reutilizar los componentes y clases semánticas documentados aquí. Para un patrón
  compartido, componer utilidades con `@apply` dentro de `@layer components`.
  Para una necesidad local sin un patrón existente, se pueden usar utilidades
  completas y estáticas en `className`.
- Usar los colores del tema: `text-ink`, `text-muted`, `bg-surface`, `bg-canvas`,
  `bg-accent`, `border-line`, `text-danger`, etc. `@theme inline` los vincula a las
  variables originales. La familia está disponible como `font-sans`.
- Mantener dimensiones exactas: `min-h-[38px]`, `rounded-[5px]`, `text-[12px]`,
  `gap-[14px]`. No sustituirlas por valores aproximados de la escala por defecto.
- Las media queries actuales conservan sus límites inclusivos: `1050px`, `760px`,
  `640px` y `460px`. No reemplazarlas automáticamente por `sm`, `md` o `lg`.
- Los imports incluyen `theme.css` y `utilities.css`, **sin Preflight**. Se conserva
  la base original del navegador y el reset mínimo del proyecto. Agregar el import
  general `@import "tailwindcss"` cambiaría esa base y exige una revisión visual.
- La capa `components` conserva la especificidad de los selectores existentes;
  la capa `utilities` permite ajustes explícitos locales. Mantener los overrides
  por feature acotados y revisar también las declaraciones con `!important`.
- Las propiedades arbitrarias conservan los shorthand, sombras y transiciones
  exactos, por ejemplo `[border:1px_solid_var(--line)]`. Cuando un shorthand y su
  excepción deben aplicarse en orden, separarlos en dos `@apply`: Tailwind puede
  ordenar las utilidades dentro de una misma directiva.
- No construir nombres de utilidades dinámicamente (`bg-${color}`): escribir
  variantes completas en un mapa para que el compilador pueda detectarlas.

```css
@layer components {
  .example-actions {
    @apply flex flex-wrap gap-[8px];
  }
}
```

Documentación de referencia:
[integración con Vite](https://tailwindcss.com/docs/installation/using-vite),
[directivas](https://tailwindcss.com/docs/functions-and-directives) y
[desactivación de Preflight](https://tailwindcss.com/docs/preflight#disabling-preflight).

## Fuentes y alcance

| Referencia | Responsabilidad |
| --- | --- |
| [`styles.css`](../../frontend/src/styles.css) | Tema Tailwind, tokens, patrones con `@apply`, variantes y media queries |
| [`shared/components`](../../frontend/src/shared/components) | API real de componentes reutilizables |
| [`CatalogAdminPage.tsx`](../../frontend/src/features/catalog/CatalogAdminPage.tsx) | Referencia visual de modales con encabezado, cuerpo y pie |
| [`ProductsPage.tsx`](../../frontend/src/features/products/ProductsPage.tsx) | Tablas con acciones y variantes por permisos |
| [`PriceListsPage.tsx`](../../frontend/src/features/pricing/PriceListsPage.tsx) | Variantes específicas de listas de precios y confirmación de estado |
| [`coding-standards.md`](coding-standards.md) | Reglas generales de desarrollo |
| [`frontend-checklist.md`](frontend-checklist.md) | Estado funcional y pendientes; no define los estilos |

Los ejemplos de pantallas son referencias visuales, no garantía de que todos sus
comportamientos de accesibilidad estén completos. Los requisitos para nuevas
interfaces se indican por separado en las secciones siguientes.

## Identidad visual y tokens

Interfaz operativa compacta: superficies adaptadas al tema, texto legible, acento verde
y bordes discretos. Mantener la jerarquía mediante tipografía, espacio y agrupación.

| Variable CSS | Valor actual | Uso |
| --- | --- | --- |
| `--ink` | `#193a43` | Texto principal y sidebar |
| `--muted` | `#667b7d` | Descripciones y texto secundario |
| `--line` | `#d7e0dc` | Bordes y separadores |
| `--surface` | `#ffffff` | Paneles y controles |
| `--surface-muted` | `#e8efeb` | Superficie secundaria y hover |
| `--canvas` | `#f3f5f2` | Fondo de página |
| `--accent` | `#34745d` | Acción principal y selección |
| `--accent-dark` | `#275d4a` | Hover principal y énfasis |
| `--copper` | `#a96a2c` | Marca y advertencias |
| `--danger` | `#a33d35` | Error y acción destructiva |

- Usar las utilidades del tema para estos roles (por ejemplo `text-ink` y
  `bg-accent`). `var(--...)` sigue disponible para propiedades arbitrarias.
  Los colores literales existentes de bordes,
  overlays y hover son detalles de sus patrones; no crear otra paleta por pantalla.
- Fuente global: `"Inter Variable", Aptos, "Segoe UI", ui-sans-serif, system-ui, sans-serif`.
  Inter se sirve desde la aplicación mediante `@fontsource-variable/inter/wght.css`,
  importado en `frontend/src/main.tsx`, con pesos variables y `font-display: swap`.
  El token `--font-sans` centraliza la familia para texto, controles y diálogos.
  Los números conservan `tabular-nums`.
- Título de página: `h1`, `clamp(28px, 4vw, 42px)`, altura de línea `1.05`.
  Título de panel: `h2`, `15px`; subtítulo: `h3`, `14px`.
  Descripciones: `13px`; controles y tablas: `12px`; etiquetas: `11px`.
- Los importes monetarios usan negrita (`700`) sin cambiar su tamaño, color ni
  formato. `DataTable` agrega `.currency-value` a las celdas con importes
  formateados con `$`; `CurrencyText` destaca solo los importes dentro de ayudas,
  descripciones y mensajes. Los campos de precio, costo, pago, saldo anterior y
  límite de crédito usan `.currency-input`, también en móvil. Cantidades,
  porcentajes y referencias de transferencia conservan su estilo.
- Radios por contexto: paneles/botones `5px`, campos `4px`, tablas `8px`, modal
  de catálogo `10px`, badges tipo píldora `999px`. No uniformarlos arbitrariamente.
- Reutilizar los espacios definidos por cada patrón: panel `20px`, formulario
  `14px`, acciones `8px`. No existe una escala de spacing tokenizada actualmente.
- Foco global en botones, inputs, selects y enlaces: contorno de `3px solid
  #bb8754`, separado `2px`. Los campos tienen además borde verde y halo al enfocar.
  Extender el foco visible a cualquier nuevo control, incluido `textarea`.

## Estructura de páginas, paneles y métricas

### Tema claro y oscuro

- `ThemeToggle` ofrece el cambio en la barra superior para todos los usuarios,
  también hasta `1050px`, sin abrir el menú. Muestra la acción «Oscuro» o «Claro»
  con un icono; hasta `460px` conserva solo el icono y su nombre accesible.
  Tiene altura mínima de `44px`, foco visible, `aria-pressed` para el estado oscuro
  y admite Enter/Espacio. No cambia los permisos ni la navegación.
- El tema inicial es claro. La preferencia se guarda por navegador en
  `localStorage` (`distribuidora.theme`), se restaura antes de pintar y se sincroniza
  entre pestañas. También está disponible en Ingresar y Activar cuenta.
  No es una configuración del usuario en el servidor. Si el
  almacenamiento está bloqueado, el cambio funciona durante la sesión actual.
- `data-theme` en `html` y `color-scheme` controlan la paleta y los controles
  nativos. La preferencia también se aplica al login y a los diálogos en portales.
  `--sidebar` se separa de `--ink` para conservar texto claro sobre la navegación;
  `--accent-hover` mantiene el contraste blanco de los botones principales.
- La tabla anterior documenta el tema claro. En oscuro se usan `--canvas`
  `#101b20`, `--surface` `#19292f`, `--ink` `#e0ece7`, `--muted` `#a5b9b2`
  y `--line` `#3b5053`. Los tokens `--detail-*` adaptan bordes, tablas, hover,
  selección y feedback de los patrones existentes, con fallback al valor claro
  exacto. Mantener estas variantes en `styles.css`; no crear paletas por pantalla.

### Tamaño de texto

- `TextSizeToggle` aparece en la barra superior antes de `ThemeToggle`. Alterna
  entre «Texto grande» y «Texto normal» con `aria-pressed`, `44px` de alto, foco
  visible y Enter/Espacio; hasta `460px` muestra solo el icono «AA».
- La preferencia se guarda por navegador en `localStorage`
  (`distribuidora.text-size`), se restaura antes de pintar y se sincroniza entre
  pestañas, igual que el tema. El control también aparece en las pantallas públicas.
  El valor por defecto es normal.
- Con `data-text-size="large"` en `html` se aplica `zoom: 1.125` a `.page-content`,
  al panel de autenticación y al contenido de los `<dialog>` y a los hijos de `.modal-backdrop`. No se aplica a
  la raíz: allí `zoom` agrandaría las alturas `100dvh` de los modales y los sacaría
  del viewport. La navegación lateral y la barra superior conservan su tamaño.
- Los tamaños del resto de la guía siguen siendo los valores base exactos; no
  convertirlos a `rem` ni duplicar estilos por tamaño. En texto grande las tablas
  anchas pueden activar su scroll horizontal interno antes; la página no debe
  desbordar horizontalmente (verificado en Stock y Listas de precios a 390 y 1280 px).

### Atajos de teclado de Operación

- Administración → Configuración muestra el panel «Atajos de teclado» con las
  siete secciones de Operación. Reutiliza `.form-grid`, `.field`, `.input` y
  `Button`. La grilla pasa a una columna hasta `760px`.
- Cada campo captura Ctrl, Alt o Meta con una letra o un número y admite Mayús
  como modificador adicional. Al asignar o quitar se guarda de inmediato por
  cuenta y por instancia en el navegador. Una combinación ocupada muestra el
  destino que la tiene y conserva la asignación anterior.
- El menú muestra cada combinación asignada con `kbd` y `aria-keyshortcuts`.
  El atajo abre la misma ruta que el enlace, respeta sus permisos y lleva el
  foco al contenido principal. Se suspende mientras hay un diálogo abierto o
  el foco está en un control de edición.

### Selector de instancias de desarrollo

- `InstanceLauncher` es una entrada independiente, compilada únicamente con el
  modo `instance-launcher` para el gateway de `compose.development.yaml`.
  El build habitual conserva solo la aplicación, sin pantalla de selección.
- Reutiliza `PageHeader`, `Panel`, `Button` y `ThemeToggle`, con los mismos tokens,
  tipografía, foco y temas. `.instance-launcher-*` limita el ancho a `760px` y
  presenta Sistema A y Sistema B en dos columnas; hasta `640px` se apilan.
  Los enlaces tienen al menos `44px` de alto y funcionan por teclado.
- Seleccionar un sistema navega a `/a/` o `/b/` del origen actual. La aplicación
  usa su `BASE_URL` para rutas, enlaces, API y credenciales por instancia.
  La selección no concede permisos ni evita el login de cada sistema.
- Configuración y alcance: [instancias de desarrollo](development-instances.md).

- Respetar el shell existente: `.app-shell`, `.sidebar`, `.main-area`, `.topbar`
  y `.page-content`. No duplicarlo dentro de una feature.
- [`AppShell`](../../frontend/src/app/AppShell.tsx) centraliza la navegación y
  reutiliza el mismo contenido y los mismos permisos en escritorio y menú compacto.
  Sobre `1050px`, el sidebar conserva `250px`, permanece visible al desplazar la
  página y ocupa `100dvh`. Solo la navegación tiene scroll interno; marca y sesión
  quedan disponibles. Hasta `820px` de altura reduce espacios entre grupos y filas.
- Operación, Catálogo y Administración son grupos desplegables independientes.
  Sus títulos son botones de `44px` como mínimo, fuente de `14px` y flecha que
  indica apertura, con `aria-expanded` y `aria-controls`. Los enlaces usan `14px`.
  Inicialmente se abre el grupo de la ruta actual; los demás quedan cerrados.
  Cambiar de ruta abre su grupo y conserva las decisiones sobre los otros.
  El estado se comparte entre sidebar y menú compacto durante la sesión y se
  conserva al cerrar el menú o cambiar de ancho; no se guarda entre recargas.
  Clic, Enter y Espacio alternan cada grupo. Los enlaces ocultos quedan fuera del
  foco y de la navegación accesible. Los permisos se aplican también a los títulos.
  Si el grupo actual está cerrado al pasar a escritorio, el foco vuelve a su
  título visible. Se reutilizan colores, radios y foco del shell existente.
  El título del grupo actual conserva el fondo `#28505a` y texto blanco, tanto
  abierto como cerrado, en escritorio y en el menú compacto.
- Hasta `1050px`, la barra superior fija de `66px` muestra marca, sección actual
  y «Menú», con área táctil mínima de `44px`. El menú abre un `dialog` nativo
  lateral de hasta `320px`, limitado al viewport y sus áreas seguras. Conserva
  grupos, selección activa, permisos, sesión y «Salir»; los enlaces tienen `44px`
  de altura mínima. Su navegación desplaza internamente sin ocultar el pie.
- El menú compacto contiene el foco e impide interactuar o desplazar el fondo.
  Enfoca «Cerrar menú» al abrir y devuelve el foco al disparador al cerrar.
  Escape, clic en el fondo o selección de un enlace cierran el menú, también
  al cambiar de ruta o pasar a escritorio; en este último caso enfoca el enlace
  activo del sidebar. La barra indica la sección actual, incluidas sus subrutas.
- La sesión activa muestra el correo del usuario cuando está disponible en su
  token. En escritorio también aparece en la barra superior. Con el menú
  compacto se consulta en el pie del menú.
- Las rutas de Resumen, Dashboard, Stock (`/products`), Marcas
  y categorías, Listas de precios, Proveedores, Compras (`/supplier-orders`, listado y
  detalle), Usuarios, Roles, Vendedores, Zonas, Configuración y Auditoría usan `RequireAdmin` en
  [`App.tsx`](../../frontend/src/app/App.tsx): comprueba `ADMIN_ALL` al renderizar
  la ruta, incluida la navegación posterior al login. Sin ese permiso redirige
  a Pedidos. No decidir el acceso al construir la tabla de rutas, porque puede
  conservar los permisos de la sesión anterior. La API mantiene su autorización.
- Pagos y deuda se retiró de la navegación para todos los usuarios, incluido el
  acceso desde Dashboard. `/payments` redirige a Pedidos y no carga esa pantalla.
- Para vendedores, el menú muestra Pedidos, Ventas y Clientes. Las acciones de
  Clientes aparecen según sus permisos y el alcance asignado (ver Ficha del cliente).
  Catálogo y Administración quedan reservados a administradores en escritorio y móvil.
  La acción «Ajustar stock» requiere además `STOCK_ADJUST`. El catálogo de roles
  identifica el ajuste global como permiso reservado a ADMIN.
  El formulario de pedidos conserva los productos, la lista asignada y los
  precios necesarios para operar; esto no habilita las pantallas de catálogo.
- Al ingresar y salir se cancelan las consultas pendientes y se limpia la caché
  de datos y mutaciones. Cambiar de administrador o vendedor no conserva datos
  de clientes, ventas, pedidos ni sus detalles de la sesión anterior. La API
  restringe clientes a su vendedor asignado y ventas/pedidos al vendedor del
  pedido, usando el vendedor actual del cliente cuando no hay uno en el pedido;
  también protege búsqueda, opciones, conteos y acceso directo por ID o número.
- `.page-content`: ancho máximo `1440px`, centrado y padding desktop `38px`.
- Usar [`PageHeader`](../../frontend/src/shared/components/PageHeader.tsx):
  `eyebrow` y `title` obligatorios; `description` (texto o contenido en línea)
  y `actions` opcionales.
  Las acciones se agrupan automáticamente en `.page-actions`.
- Usar [`Panel`](../../frontend/src/shared/components/Panel.tsx): `title`,
  `description` y `action` opcionales, más `children`. Genera un `section.panel`
  y un `.panel-header` cuando corresponde. Las acciones directas conservan su
  texto en una línea. Si el título y la acción no caben juntos, la acción pasa
  debajo.
- `.content-grid` agrupa secciones con `20px` de separación;
  `.content-grid.two-thirds` distribuye contenido principal y secundario.
- Usar [`StatCard`](../../frontend/src/shared/components/StatCard.tsx) con
  `label`, `value`, `detail` y `emphasis` opcional. `.stats-grid` tiene cuatro
  columnas; `.stats-grid.compact`, tres. Borde superior verde y valor de `25px`.
- Resumen es exclusivo de `ADMIN_ALL` en menú, ruta y API. El panel «Período»
  reutiliza `OrderDateFilter`, `OrderCalendar` y `.orders-date-filters` para
  Desde/Hasta, con ambas fechas requeridas y el día actual de Argentina como
  valor inicial. Conserva el rango en la URL y ofrece «Hoy» para restablecerlo.
  En esta pantalla, título y controles comparten el encabezado del panel para
  reducir su altura. «Hoy» se ubica inmediatamente después de Desde/Hasta,
  alineado con los campos; el conjunto se adapta al ancho disponible y solo
  separa el botón en una segunda fila si no entra junto a las fechas.
  Fechas inválidas o invertidas bloquean la consulta y ocultan los resultados.
  Las dos tarjetas de pedidos se agrupan bajo «Pedidos». Las cinco de
  facturación y cobros usan `.stats-grid.compact` en tres columnas en escritorio
  y dos a `760px` o menos. Ambos grupos pasan a una columna a `460px` o menos.
  Debajo, `DataTable` presenta las
  mismas métricas por vendedor y separa «Pagado en efectivo» y «Pagado en
  transferencia» en columnas; a `640px` se adapta a tarjetas etiquetadas.
  Los encabezados de esta tabla admiten varias líneas para conservar visible
  «Ver» con el desglose de cobros en escritorio.
  Incluye carga, error con reintento y vacío dentro de la tabla. No ofrece acciones
  de creación. El rango selecciona pedidos por fecha de creación, incluidos los
  dos días completos; los pedidos realizados incluyen cancelados, pero estos
  se excluyen de importes. Entregas, pagos acumulados y saldo contable muestran
  su estado actual, sin incluir deuda de otros períodos ni saldo anterior del remito.
  Cada vendedor, incluido «Sin asignar», ofrece «Ver»: abre `SellerOrdersDialog`
  con pedidos entregados del mismo rango de creación. Incluye cliente, fecha de
  entrega, total, pagado, efectivo, transferencia y deuda actual de cada venta.
  Usa paginación de 20 filas, estados de carga, vacío y error con reintento manual.
  Los errores HTTP se muestran sin esperar reintentos automáticos.
  El diálogo nativo reutiliza `.confirmation-dialog-*` con ancho local de hasta
  `1100px`, scroll limitado al viewport y cierre visible en un encabezado fijo.
  Contiene el foco, bloquea el fondo, admite Escape y restaura el foco en «Ver».
  La tabla conserva las tarjetas compartidas hasta `640px`. Esta variante es
  específica de Resumen y no cambia las dimensiones de las confirmaciones.

### Dashboard comercial

- «Dashboard» aparece inmediatamente debajo de «Resumen», solo para
  administradores. `/analytics` usa `AnalyticsDashboardPage` y la API
  `/api/dashboard/analytics`; conserva el Resumen independiente.
- «Clientes con mayor deuda» ordena los cinco mayores saldos actuales por importe,
  muestra el porcentaje de cada cliente sobre la deuda total y el importe y
  porcentaje concentrados entre esos clientes. «Ver deuda» abre las ventas del
  cliente con saldo pendiente; «Ver deudores» abre el listado filtrado.
  Estas prioridades no dependen del período de análisis. Reutiliza `DataTable`
  y las tarjetas compartidas hasta `640px`, sin un umbral arbitrario de importancia.
- Reutiliza `PageHeader`, `Panel`, `StatCard`, `DataTable`, `Button`, `Badge`
  y `OrderDateFilter`. Las cuatro métricas principales siguen `.stats-grid`;
  la tendencia y los pendientes usan `.content-grid.two-thirds`.
- Los filtros usan Hoy/Semana/Mes/Personalizado, fechas en la URL y el calendario
  existente. Ventas, cobros y gastos se filtran por período; deuda, entregas y stock
  indican explícitamente su situación actual. No se muestran porcentajes con
  base anterior cero. Errores de fechas bloquean la consulta.
- El gráfico SVG tiene descripción accesible y una tabla de datos expandible
  mediante teclado. El eje vertical marca máximo, mitad y cero. Cada día con
  ventas tiene un marcador con su fecha completa y su importe.
  Los rankings reutilizan la transformación de tablas en
  tarjetas hasta `640px`. La grilla local de paneles pasa de dos columnas a una
  hasta `760px`; los filtros se apilan hasta `640px`. Estas utilidades son
  específicas de Dashboard y no cambian los patrones globales.
- Las tablas dentro de paneles compactos y del gráfico usan ancho mínimo cero,
  columnas de ancho fijo y texto adaptable, con padding horizontal de `10px`
  en escritorio. Las tablas completas de pedidos conservan el ancho y scroll
  compartidos. Los nombres largos no expanden los paneles.
- Carga y errores se anuncian; se ofrecen reintento y actualización manual.
  Resumen y Dashboard validan la respuesta antes de mostrar métricas: un contrato
  incompatible conserva la navegación y los filtros, y muestra un error con reintento.
  Las alertas usan texto además de color. Se distingue antigüedad de deuda de
  vencimiento, y cobertura estimada de stock de un mínimo de reposición.
- Contrato y limitaciones: [dashboard-analytics.md](../api/dashboard-analytics.md).

### Gastos

«Gastos» aparece debajo de «Clientes» en Operación, con acceso exclusivo de
`ADMIN_ALL`. El historial usa `DataTable` con 50 gastos por página y tarjetas
hasta `640px`. Al entrar, «Desde» y «Hasta» muestran el día actual de Argentina.
El tipo y las fechas de carga «Desde» y «Hasta» se combinan antes de paginar y se
conservan en la URL. Cada cambio vuelve a la primera página. Las fechas reutilizan
el calendario compartido, admiten límites abiertos y se muestran en dos columnas
hasta `640px`. «Limpiar filtros» vuelve al historial completo. Los tipos aparecen
como Caja chica, Combustible, Servicios y Otros en el filtro y en el formulario.
Un clic en la fila o la acción «Ver detalle» abre un `FormModal` compacto con el
tipo, la fecha, el importe y la observación completa. «Cerrar» y Escape devuelven
el foco a la acción de la fila.
«Nuevo gasto» abre un `FormModal` compacto con importe, tipo y observación
opcional. El diálogo conserva el borrador ante errores y confirma el descarte
de cambios. El Dashboard muestra el total y los últimos cinco gastos del
período seleccionado, con un enlace al historial.

### Listas de precios que siguen a la General

- «Nueva lista de precios» pide solo el nombre. El sistema genera el código.
  «Cancelar» y «Guardar lista» comparten una fila al pie del modal.
- Crear una lista con productos cargados abre `ConfirmationDialog` (`primary`)
  sobre el formulario: «Ya hay N productos cargados…» y explica que tomarán los
  precios de la General y la seguirán. Activar una lista inactiva lo explica en el
  diálogo de estado. «+ Nueva lista» está disponible para administradores sin
  límite de cantidad. El selector de listas de la pantalla se pagina y las
  opciones de clientes, productos y pedidos recorren todas las páginas.
- Si hay una sola lista, su tarjeta se muestra siempre y el selector omite el
  filtro de estado y el botón para plegar. Con varias listas se aplican las
  preferencias guardadas. La tarjeta muestra «N sin revisar» (`.price-list-pending`)
  cuando hay precios pendientes.
- «Descargar todas las listas» queda en el encabezado, junto a «+ Nueva lista»,
  y genera un único PDF con todas las listas activas. «Descargar [nombre]» está
  debajo de las tarjetas (también disponible al plegarlas) y exporta la lista
  seleccionada completa. La tabla ofrece «Imprimir lista» y, si se aplica una
  búsqueda, categoría o marca, «Descargar PDF filtrado».
- En una lista que no es la General, la tabla agrega «Estado» con `Badge`:
  «Sin revisar» (`strong`), «Sin cambios» (`soft`, revisado y sigue a la General) y
  «Precio propio» (`muted`). El encabezado ofrece el filtro «Estado» (Todos, Sin
  revisar, Siguen a la General, Precio propio) y «Confirmar N sin revisar»; cada
  fila pendiente tiene «Confirmar». Una ayuda explica que esos precios siguen a la
  General y que editarlos los vuelve propios; el diálogo de edición lo repite.
- Al guardar un precio de la General (desde la lista o al cambiar el costo del
  producto), si hay listas que lo siguen con aviso pendiente se abre
  `ConfirmationDialog` «Este cambio también se aplica en …» con «Volver» (foco
  inicial) y «Guardar y no volver a avisar». Confirmar envía `acknowledgeFollowers`.
- Edición en la tabla (`InlinePriceInput`, solo administradores en listas activas): el
  precio es un campo numérico en la fila con formato es-AR. Enter valida y guarda y
  pasa a la fila siguiente; Escape restaura el valor guardado. Mientras haya un
  cambio sin guardar el borde usa `--copper` y se muestra «Enter para guardar · Esc
  para descartar». Nunca se acepta un precio menor al costo: el error se muestra en
  la fila con `role="alert"` y `aria-invalid`. En la General se aplica el mismo
  aviso de listas seguidoras. «Programar» abre un `FormModal` compacto con el
  producto y la lista en el encabezado, y el precio y la fecha en una columna.
  Si la fecha queda vacía, se aplica desde hoy. Cancelar o pulsar Escape con
  cambios sin guardar pide confirmar el descarte.
- La tabla principal y la de historial muestran encabezados de `12px` con
  capitalización de frase, padding vertical de `13px` e importes alineados a la
  derecha. «Vigente desde» usa `dd/mm/aaaa` en ambas tablas y al cancelar una vigencia. «Ver historial» abre un `FormModal` de hasta `1040px` con el producto
  y la lista en el encabezado fijo. El diálogo mantiene la paginación, permite
  reintentar la carga y usa tarjetas con etiquetas a `640px` o menos. «Cerrar» y
  Escape devuelven el foco a «Ver historial». Los administradores pueden cancelar
  vigencias futuras mediante un `ConfirmationDialog` sobre el historial.
- Al subir el costo de un producto por encima del precio de alguna lista con precio
  propio, el formulario abre `CostPriceFixDialog` (`FormModal` compacto) con un campo
  por lista, el precio actual como ayuda y el costo como mínimo. «Guardar precios y
  costo» reenvía el producto con esos precios; «Volver» conserva el formulario.
- Los importes de precio aceptan formato argentino («1.250,50») además de punto
  decimal; el campo se precarga con coma decimal.
- La tabla de precios de cualquier lista ofrece «Buscar producto», «Categoría» y
  «Marca» (`SearchableSelect`, opciones completas) con «Limpiar filtros»; se guardan
  en la URL (`q`, `categoryId`, `brandId`), vuelven a la primera página y se
  conservan al cambiar de lista.
- En el alta de productos solo es obligatorio el precio de la General; las demás
  listas muestran «(opcional)» y el placeholder «Igual a GENERAL».

### Ficha del cliente

El listado autoriza cada acción por separado mediante su permiso o ADMIN_ALL:
«Ver cliente» (CUSTOMER_VIEW), alta (CUSTOMER_CREATE), edición (CUSTOMER_EDIT),
estado (CUSTOMER_STATUS_CHANGE), lista predeterminada (CUSTOMER_PRICE_LIST_CHANGE),
cobro (ACCOUNT_SETTLE) y documentos (CUSTOMER_STATEMENT_EXPORT). Las acciones de
fila muestran «Ver cliente», «Registrar pago» y «Editar» según los permisos.
Cambio de lista, revendedor, estado y documentos quedan en «Más acciones».
Los documentos se agrupan con un separador y «Desactivar cliente» usa el color
de peligro. Imprimir y descargar muestran el progreso y los errores en el menú.
El saldo muestra dos decimales. «Ver cliente» abre
`/customers/{customerId}` como página con `PageHeader`, `Panel` y `StatCard`:
datos de contacto, vendedor, lista, estado, fecha de alta y saldo actual. La
tarjeta de saldo se alinea arriba sin estirarse a la altura de los datos.
Los datos usan un `dl.customer-detail-fields` con dos columnas, etiquetas de
`11px`, valores de `13px` y ajuste de nombres/direcciones largos. A `760px`
los datos y el resumen de saldo se apilan en una columna.

La ficha muestra «Saldo de cuenta corriente» al administrador y «Saldo de tus
ventas» a usuarios con alcance asignado. Los documentos requieren su permiso
propio; en la fila permiten imprimir/descargar A4 sin acceso a ficha, y en la ficha
se mantienen A4 y ticket. Reutilizan `DocumentActions`.

Alta y edición usan `FormModal` y los estilos existentes del formulario de cliente,
con foco contenido, Escape/descarte, protección de salida y campos bloqueados al
guardar. El vendedor asignado usa `SellerSelect`: los administradores cargan todas
las páginas autorizadas; otros roles ven solo su perfil propio, seleccionado y
bloqueado. Un error de asignación impide guardar y ofrece reintento sin perder el
borrador. La lista predeterminada queda bloqueada sin su permiso y conserva el
valor histórico, incluso inactivo. «Cambiar lista» abre un `FormModal` compacto
independiente de la edición general; cambiarla no modifica pedidos históricos.
Los metadatos ofrecen reintento. El estado usa `ConfirmationDialog`, con acción
`danger` al desactivar y principal al activar. Al terminar vuelve al botón de
estado o a la búsqueda si el cliente salió del filtro activo.

«Registrar pago» aparece con ACCOUNT_SETTLE en filas con saldo deudor positivo,
incluidos clientes inactivos al seleccionar ese filtro. Consulta la lectura mínima
`account-balance`, sin exigir ficha, y conserva filtros y paginación. Reutiliza
`DeliveryAttemptDialog` en modo `account`: muestra el cliente, el saldo de cuenta
corriente y los mismos controles y estilos de pago de las entregas, sin resultado
de entrega ni selección de deuda anterior; el título es «Pago de cuenta corriente». Precarga el saldo completo en efectivo;
permite combinar efectivo y transferencia y exige el número de transferencia.
«Pagar total» completa el importe. «Confirmar pago» acepta el total o una parte (sin superar la
deuda; un pago parcial envía `partial: true` e informa el saldo pendiente). La API registra todos los pagos en una sola
transacción e imputa primero las deudas visibles más antiguas. Una liquidación
asignada informa «Deuda asignada saldada correctamente». Si cambió el saldo, muestra
el error, actualiza el saldo y conserva el borrador para corregirlo. Al guardar
refresca clientes, ventas, pedidos, pagos y métricas. El diálogo conserva el foco,
Escape, bloqueo durante guardado y adaptación móvil del pago de entregas; al
cerrar vuelve al botón o a la búsqueda si la fila dejó de estar visible.

«Historial de pedidos» reutiliza `DataTable` y la paginación compartida. Incluye
fecha/hora de Argentina, vendedor, total, saldo pendiente, estado en español y «Ver pedido»;
el saldo es la deuda actual de cuenta corriente de cada venta, limitada al importe
sin pagar y sin duplicar el saldo anterior del remito. Los importes pendientes
se destacan en rojo y negrita; los saldos cero muestran «Sin deuda» y un dato
no disponible muestra «—». Los pedidos cancelados no muestran deuda.
a `640px` adopta las tarjetas compartidas. Consulta por ID exacto, sin restringir
al día actual. Conserva `ordersPage` en la URL y los filtros del listado al
volver. Carga y errores se anuncian y ofrecen reintento; un error del historial
conserva los datos del cliente. Cliente inexistente o ajeno no muestra pedidos.
El listado de Clientes está disponible para vendedores en escritorio y móvil,
con búsqueda, estado y saldo; la API limita filas y conteos a sus clientes
asignados. Saldo, filtro de saldo y conteos usan solo deuda propia para usuarios
sin alcance global; el administrador conserva saldo global y créditos.
El filtro global de vendedor sigue siendo exclusivo de `ADMIN_ALL`.
La ruta de ficha exige CUSTOMER_VIEW o ADMIN_ALL; sin permiso redirige a Pedidos
sin consultarla. El historial también filtra pedidos ajenos. Los selectores de cliente para pedidos y
pagos mantienen sus opciones autorizadas y el acceso al último pedido.

### Revendedores

Fase 9.2. Un revendedor es un cliente cuya cuenta corriente debe los pedidos de
sus clientes asociados; el pedido sigue a nombre del cliente que lo recibe. Lo
gestiona la administración: las acciones requieren `ADMIN_ALL` y
`RESELLER_MANAGE`.

- **Listado de Clientes.** La columna «Cliente» muestra solo el nombre, sin el
  `Badge` «Revendedor» ni el texto secundario «Cuenta: {revendedor}». El
  filtro «Buscar por revendedor» es un `CustomerSelect` en modo `filter` con
  `emptyLabel` «Todos los revendedores», visible para administradores y con
  opciones cargadas por todas las páginas (`kind=RESELLER`). «Registrar pago» no
  aparece en filas de revendedor sin `ADMIN_ALL`.
- **Acción «Revendedor».** Una sola acción de fila (`Button variant="link"`, nombre
  accesible «Revendedor de {cliente}») abre `CustomerResellerDialog`: un `FormModal`
  compacto con un `fieldset` «Tipo de cliente» (radios Cliente/Revendedor de `44px`
  de alto) y, para clientes, `CustomerSelect` en modo `selection` con `emptyLabel`
  «Sin revendedor (cuenta propia)». Guarda un cambio por vez. Los radios se
  deshabilitan con su explicación asociada cuando la regla no lo permite (un
  cliente con revendedor no puede ser revendedor; un revendedor con clientes
  asociados, plan de comisión o comisiones sin liquidar conserva la marca, y la
  explicación enumera los pasos que faltan). Al cambiar el revendedor, un `.warning-text` lista
  los pedidos sin entregar que conservan su cuenta. Escape y Cancelar usan
  `useDiscardChanges`; un `409` muestra el detalle de la API dentro del diálogo.
  La acción comparte el desplegable con los demás controles secundarios de la fila.
- **Ficha.** El revendedor muestra el `Badge` junto al estado, «Tipo de cliente»
  con la cantidad de clientes asociados, el panel «Clientes asociados» (`DataTable`
  con paginación propia `clientsPage`) y la acción «Ver deuda de la cuenta», que
  abre Ventas filtradas por cuenta. El cliente asociado muestra «Revendedor» en
  `dl.customer-detail-fields` y la `StatCard` aclara que su saldo es la deuda propia.
- **Pedidos.** Al elegir un cliente asociado, un `.helper-text` indica «Cuenta:
  {revendedor}». Para administradores, `.order-account` muestra el saldo de la
  cuenta del revendedor y explica que el remito no incluye saldo anterior; sin
  `ADMIN_ALL` se reemplaza el cobro al confirmar por el aviso «Los cobros de cuentas
  de revendedor los registra un administrador.». El detalle del pedido agrega
  «Titular de la deuda» al encabezado de «Detalle del pedido».
- **Ventas y Pagos.** Las filas de Ventas muestran solo el cliente en «Cliente».
  La columna «Vendedor / Revendedor» muestra el revendedor de la cuenta guardada
  en la venta cuando difiere del cliente; en cuentas propias muestra el vendedor
  del pedido, con fallback al vendedor del cliente y «Sin asignar» si falta.
  Hasta `640px` conserva su etiqueta en las tarjetas compartidas.
  Con `accountCustomerId` en la URL, un `.helper-text`
  con `role="status"` identifica la cuenta y ofrece «Quitar filtro de cuenta». El
  detalle de una venta de revendedor reemplaza el formulario de devolución por
  un texto. En Pagos, cada deuda de la cuenta nombra al cliente final.

### Compras (pedidos a proveedores)

«Compras» (antes «Pedidos a proveedores») aparece entre Ventas y Clientes, solo para `ADMIN_ALL`; la ruta `/supplier-orders` no cambió.
`/supplier-orders` reutiliza `PageHeader`, `Panel`, `DataTable`, la paginación,
`OrderDateFilter`/`OrderCalendar` y `SupplierSelect` del selector compartido.
Los filtros por proveedor, Desde y Hasta se combinan y conservan en la URL;
sin fechas incluyen todo el historial. «Todas las fechas» limpia solo el rango.
Fechas inválidas o invertidas bloquean la consulta y ocultan datos anteriores.
La tabla presenta número, proveedor, fecha, total estimado y enlace al detalle;
hasta `640px` usa las tarjetas compartidas. Al volver del detalle conserva filtros.
La columna «Estado» usa `Badge`: «Confirmada» (`strong`), «Pendiente» (`soft`),
«Anulada» e «Histórica» (`muted`). El alta ofrece «Guardar compra» como acción
principal y deja la compra pendiente. El detalle muestra el estado con una
explicación de su efecto en el stock y las acciones «Confirmar compra» (solo
para compras pendientes), «Editar compra» y «Anular compra» (`danger`), todas con
`ConfirmationDialog` salvo editar, que reutiliza el formulario con una descripción
según el estado. Al confirmar, el diálogo identifica la compra, explica el ingreso
de cantidades al stock y enfoca «Aceptar». Enter confirma y Escape cierra el
diálogo antes de enviar la operación. «Descargar PDF» se alinea a la izquierda junto a «Editar
compra». «Anular compra» se alinea al extremo derecho y pasa a la fila siguiente
si falta espacio. El PDF A4 incluye proveedor, fecha y las columnas Producto y
Cantidad. Las cantidades de compras no históricas deben ser múltiplos de 0,5.
«Agregar productos sin stock (N)» carga los productos activos con stock ≤ 0 del
proveedor (o todos si no tiene productos asociados).

«Reponer» aparece en las acciones de la fila de Stock para productos activos
con saldo ≤ 0 y debajo del nombre del producto en Cobertura estimada del
Dashboard. Abre `/supplier-orders/restock` con el mismo panel de compra,
una línea editable y el selector compartido de proveedor. La página espera el
catálogo actual, muestra reintento si falla y preserva los filtros de origen
al cancelar o volver después de guardar. La preselección por reposición queda
como estado inicial. Si se modifica, Cancelar pide confirmar el descarte.

«Nueva compra» abre la página `/supplier-orders/new`, con un `PageHeader` y un
`Panel` para el proveedor, la fecha, el selector de productos activos y el
detalle con cantidad y costo unitario editables por producto. El costo se
precarga desde el catálogo al agregar, reponer o copiar productos. El importe
ingresado se guarda en la compra. Si se deja vacío, la API usa el costo vigente
del catálogo. El detalle de una compra guardada y su PDF muestran producto y
cantidad por línea. Reutiliza `.form-grid` y
`.product-picker`, que se apilan a `760px`. La fecha y la cantidad quedan limitadas
respectivamente a `200px` y `140px`. Al cancelar o guardar se vuelve al
listado con sus filtros. Cancelar pide confirmación si hay cambios sin guardar.
La fecha inicia en el día de Argentina. «Cargar compra anterior» consulta la
última del ID exacto del proveedor y copia sus productos y cantidades. Requiere
confirmación para reemplazar productos y bloquea productos inactivos.
Cancelar un borrador modificado pide confirmación. Durante guardado/carga se
bloquean campos y acciones; errores conservan el borrador y el intento para
reintentar. Proveedores y productos cargan todas sus páginas y ofrecen reintento.
Guardar la compra conserva el stock. Confirmar desde el detalle ingresa las cantidades.
El flujo no envía mensajes al proveedor. Contrato y alcance en
[`supplier-orders.md`](../api/supplier-orders.md).

En productos, «Proveedores (opcional)» permite agregar varios mediante
`SupplierSelect`, sin repetir, y quitarlos individualmente. En Stock, el listado
empieza con Producto, Marca y Categoría. Estado y Acciones tienen columnas
propias. «Más acciones» agrupa movimientos, mermas y reposición cuando corresponde.
Hasta `640px`, los controles se acomodan en las tarjetas.
Un producto se puede guardar sin asociaciones. Un error de carga de opciones
no borra asociaciones existentes ni impide guardar campos ajenos al proveedor.
Las filas de selección se adaptan al ancho y admiten nombres largos.

## Botones y acciones

Usar [`Button`](../../frontend/src/shared/components/Button.tsx). Acepta atributos
nativos, `variant` y `fullWidth`; `href` hace que renderice un enlace.

| Variante | Uso | Apariencia |
| --- | --- | --- |
| `primary` (por defecto) | Acción principal de la sección o formulario | Verde, texto blanco; hover verde oscuro |
| `secondary` | Cancelar, editar, paginar, acción alternativa | Blanco, borde tenue; hover superficie secundaria |
| `danger` | Desactivar, eliminar o confirmar una operación destructiva | Texto rojo, borde rojizo y hover claro |
| `ghost` | Acción sobre fondo oscuro, como el sidebar | Transparente y texto claro |
| `link` | Acción textual de baja jerarquía | Transparente y verde oscuro |

- Base: altura mínima `38px`, padding horizontal `14px`, texto `12px/700`,
  radio `5px`, transición de fondo/borde `150ms`.
- Deshabilitado: opacidad `.45` y cursor `not-allowed`. Enlace con `href` no tiene
  deshabilitación nativa; no usarlo para ejecutar una mutación pendiente.
- `fullWidth` agrega `.button-full`. No existe prop `size`, `loading` ni `icon`.
  Mostrar carga mediante el texto y `disabled` mientras se procesa.
- Dentro de un formulario, indicar `type="button"` en acciones auxiliares y
  `type="submit"` en la acción de guardado.
- Priorizar una acción principal por grupo. Nombrar el resultado, por ejemplo
  “Guardar producto” o “Desactivar lista”. Los controles sin texto necesitan
  un nombre accesible y los íconos decorativos `aria-hidden="true"`.
- `.page-actions`: acciones generales con gap `8px` y wrap.
  `.table-row-actions`: acciones de fila alineadas a la derecha, gap `7px`,
  botones de `34px` de alto y texto de `11px`. Cuando la fila tiene acciones
  secundarias, [`SecondaryRowActions`](../../frontend/src/shared/components/SecondaryRowActions.tsx)
  las muestra en un menú flotante de hasta `232px` alineado al extremo del botón.
  La fila conserva sus dimensiones al abrirlo. Con poco espacio debajo, el menú
  se abre arriba y permite desplazamiento interno cuando hace falta. Las
  acciones se alinean a la izquierda y tienen una altura mínima de `44px` hasta
  `640px`. El botón incluye el nombre de la fila y ofrece Enter y Espacio para
  abrirlo. Tab recorre sus acciones. Escape lo cierra y devuelve el foco al
  botón. Un clic fuera o la salida de foco también lo cierran. Al abrir un
  diálogo desde el menú, el foco vuelve a «Más acciones» al cerrar el diálogo.
- `Button` no combina el `className` recibido con sus clases internas: no pasar
  un `className` suponiendo que se conservará `.button`. Para ajustar un contexto,
  usar un contenedor con selector acotado o mejorar explícitamente el componente.

```tsx
<div className="page-actions">
  <Button variant="secondary" type="button" onClick={onCancel} disabled={saving}>
    Cancelar
  </Button>
  <Button type="submit" disabled={saving}>
    {saving ? 'Guardando...' : 'Guardar producto'}
  </Button>
</div>
```

## Tablas, filtros y paginación

«Nuevo pedido» muestra la ruta Operación / Pedidos y la acción «Cancelar».
`.order-create-form` agrupa «Cliente y condiciones» y «Productos»
en paneles consecutivos y alinea «Resumen del pedido» a su derecha cuando hay
al menos `840px` de ancho disponible. En espacios más estrechos el resumen queda
debajo del formulario. Usa los tokens de tema claro y oscuro.
«Cliente», «Fecha del pedido», «Vendedor» y «Lista de precios» comparten una fila
alineada a la izquierda, con `8px` entre campos y `4px` entre cada etiqueta y su control.
A `760px` o menos se apilan. Los selectores ocupan hasta `35ch`,
`20ch` y `30ch` de texto respectivamente, además de sus bordes, padding e iconos,
y se reducen para caber en el espacio disponible. «Del cliente» indica que el
pedido usa el vendedor asignado al cliente. La fecha conserva `10ch` de texto y espacio
para el calendario. Las medidas son visuales y permiten seleccionar nombres
completos. La fecha usa el día actual de Argentina por defecto, formato
`dd/mm/aaaa` y el calendario compartido en
español. Una fecha inválida muestra un error asociado y bloquea la confirmación.
Se conserva al cambiar de cliente o cargar un pedido anterior. La edición conserva
la fecha registrada y omite este campo.

El resumen de creación incluye subtotal, descuento general con sufijo `%` según
permisos, total y acciones de ancho completo. En creación y edición, al tocar o
enfocar el descuento se selecciona el valor completo. Las flechas a la izquierda
del campo y las teclas ↑ y ↓ suman o restan un punto porcentual, con límites de
`0` y `100` y entrada manual con coma decimal. «Guardar borrador»
conserva un pedido en este navegador por usuario y lo recupera al volver a
«Nuevo pedido». Se elimina después de la confirmación exitosa. Cancelar descarta
las modificaciones posteriores al guardado si el usuario lo confirma.

En edición, el cierre `.order-checkout` usa tres columnas sobre el panel,
separadas de los productos por un borde superior: descuento general compacto
de `96px` con sufijo `%`, desglose de subtotal/descuento de hasta `240px` y
total con confirmación de hasta `340px`. Solo el bloque final usa fondo canvas,
borde y padding `16px`; el total se destaca en `22px` y el botón de ancho
completo queda debajo, con altura mínima `42px`. Con `ORDER_DISCOUNT` o `ADMIN_ALL`
se muestra el campo de descuento. El descuento admite coma decimal,
muestra errores asociados al campo para valores fuera de `0–100` y bloquea
confirmación hasta corregirlos. A `1050px` se usan dos columnas, con descuento
y desglose a la izquierda y total/confirmación a la derecha. A `760px` o menos se apilan los tres bloques,
con etiqueta y porcentaje alineados en una fila. No se muestra la aclaración
de cuenta corriente en este cierre.
Al elegir un cliente, su saldo aparece debajo de los productos en `.order-account`,
con importe destacado, un campo «Importe para el remito» y acciones para agregar
un importe parcial o el saldo completo. Sin saldo deudor se deshabilitan las
acciones. El importe elegido aparece como una fila «Saldo anterior», sin cantidad
ni descuento y con su propia acción para quitarlo. Los totales separan el pedido del
saldo anterior y muestran «Total a cobrar». Cambiar cliente elimina la selección;
actualizar el importe reemplaza la fila existente. En móvil, importe y acciones
se apilan sin desbordar. El importe se guarda en el pedido para el futuro remito
y no genera un nuevo débito, descuento ni movimiento de stock.
Debajo, `.order-collection` («Cobro al confirmar (opcional)», solo en pedidos
nuevos) agrupa en un `fieldset` por concepto «Pago de este pedido» y, si el
cliente tiene deuda, «Pago de deuda anterior»: medio (efectivo o transferencia),
importe, número de transferencia solo con transferencia, un botón de enlace para
completar el total y una ayuda que aclara a qué se imputa. Con importes cargados se
muestra «Cobrado ahora» y lo que queda del pedido en cuenta corriente. A `640px` o
menos los campos se apilan. La edición de pedidos no incluye controles de cobro. Al cambiar de cliente se selecciona
su lista asignada o `GENERAL` si no tiene una, y se descarta la selección manual
del cliente anterior. Se cargan todas las páginas de listas para poder mostrar
la asignada. Una lista asignada no disponible se señala y requiere seleccionar
una lista activa.
«Cargar pedido anterior» aparece en el encabezado de «Cliente y condiciones» al
crear y junto a la lista en edición. A `460px` o menos ocupa su propia fila de
ancho completo en creación. El botón queda gris y deshabilitado sin
cliente, durante la consulta o si no hay pedido previo. Carga el último pedido
confirmado o entregado visible para el usuario, reemplazando los productos del
borrador y conservando cantidades. Usa la lista y precios actuales. La carga
inicial excluye descuentos; si el pedido anterior tiene descuentos, un usuario con
`ORDER_DISCOUNT` o `ADMIN_ALL` recibe un diálogo para cargar sin descuentos o copiarlos. Cancelar
o Escape conserva el borrador, y el foco vuelve al botón. No copia cobros ni precios manuales históricos.
Los productos no disponibles bloquean la carga con un mensaje, y los errores
de consulta permiten reintentar.

Al crear pedidos, «Lista de precios» usa la lista asignada al cliente o
`GENERAL` si no hay asignación. Sin `ORDER_PRICE_LIST_CHANGE` o `ADMIN_ALL`, el
selector está deshabilitado y cambia automáticamente al seleccionar otro cliente.
Con ese permiso se puede elegir otra lista activa; la API rechaza cambios no autorizados. Si la
lista asignada no está disponible, el vendedor ve un mensaje para solicitar
revisión administrativa y no puede agregar productos ni confirmar.

La creación y edición de pedidos comparten `OrderForm`. Al abrir «Nuevo pedido»
y completar la carga, el foco inicial queda en «Cliente» para buscar por nombre.
Al seleccionar un cliente con una lista de precios activa, el foco pasa a «Producto».
En edición, cliente y vendedor usan `CustomerSelect` y `SellerSelect`
deshabilitados con los nombres del pedido, sin cargar opciones para modificarlos.
Se precargan productos,
cantidades, precios guardados, descuentos y saldo anterior. `ORDER_EDIT` o
`ADMIN_ALL` permiten agregar/quitar productos y editar cantidades; el precio
unitario requiere `ORDER_PRICE_OVERRIDE` y los descuentos `ORDER_DISCOUNT`.
Los valores históricos permanecen visibles y se conservan sin esos permisos. Cambiar
de lista descarta los precios anteriores y consulta los de la lista elegida.
La acción principal es «Guardar cambios» y actualiza el pedido existente, sin
crear otra venta ni modificar los cobros. Cancelar vuelve al detalle; si hubo
cambios, utiliza la confirmación compartida de descarte. Conserva las mismas
validaciones y cálculos de creación. Solo admite pedidos
y ventas confirmados, con una misma lista entre sus líneas.

El detalle del pedido y el resultado de confirmación usan el nombre del cliente
como `h1` de `PageHeader`. Debajo muestran «Pedido» y su número con la fuente
secundaria de `13px`. El detalle agrega la fecha y el `Badge` del estado general
(Confirmado, Entregado o Cancelado) en esa franja, con salto de línea si falta
espacio. La variante local
`.order-customer-header` permite partir nombres largos sin desbordar en móvil,
sin cambiar encabezados de otras pantallas. El número concatena el número completo
del cliente, con un mínimo de cuatro dígitos, y cinco del correlativo por cliente
(ejemplos `000100004` y `1000000001`).
Desde V57, la venta usa ese mismo número visible en sus listados, detalles y
comprobantes; las ventas existentes también se unifican. La búsqueda de ventas
acepta el número compartido y los números anteriores conservados como alias.
La ficha incluye el dato «N.º de cliente». Contrato en
[`order-numbering.md`](../api/order-numbering.md).

«Detalle del pedido» se ubica junto a «Pagos registrados» en escritorio. Muestra
el subtotal, el porcentaje general por separado y el descuento total guardado
de productos más general. Si el pedido incluye un importe de la cuenta corriente,
lo muestra en «Saldo anterior de cuenta corriente» y lo suma al «Total a cobrar».

Filtros adicionales del listado: «Cobro» (Todos / Pagados sin entregar) para todos
los usuarios y «Cargado por» (Todos / Administrador / Vendedor) solo para
administradores, guardados en la URL (`paid`, `createdBy`). La celda «Fecha» muestra
solo la fecha, sin atribución «Cargado por» ni distintivo de revisión pendiente.
«Todas las fechas» limpia el rango y conserva los demás filtros. Un pedido
confirmado totalmente pagado muestra el badge «Pagado» junto al estado
(`.order-status-badges`).

Teclado: en la tabla, ↑/↓ mueven el foco entre los enlaces «Ver detalle». El listado
guarda en `sessionStorage` los pedidos de la página visible; el detalle muestra
`OrderNavigator` («← Anterior», «N de M», «Siguiente →», `.order-navigator`) y ← / →
cambian de pedido cuando el foco no está en un campo y no hay un diálogo abierto.
«Volver a pedidos» regresa al listado con sus filtros.

Preparación y revisión (V42): el listado agrega «Preparación» (Todas / A preparar /
Listos para entregar), en `fulfillment`, y para administradores «Revisión
administrativa» (Todas / Pendientes de revisión), en `review`. Cambiar filtros
reinicia la página. La preparación usa `Badge` con texto español.
`OrderOperationalActions` comparte botones entre fila, detalle y Resumen; usa
`ConfirmationDialog` (`primary`), permite reintentar errores y bloquea cierre y
doble envío durante guardado.

`OrderReviewBadge`: «Vendedor · Pendiente de revisión» se conserva en el detalle
y en Resumen, con texto y color de advertencia mediante
`.order-review-pending .badge` (variante local); no aparece en las filas del listado. En
escritorio (sobre `640px`), los encabezados de `.orders-list-table`, el número
de pedido, la fecha y los badges de preparación y
estado (`.badge`) permanecen en una línea sin cortar palabras ni números; los
importes `.currency-value` conservan el mismo comportamiento en todas las
`.compact-list-table`, mientras cliente y vendedor ajustan solo en límites de
palabra. Preparar o entregar no elimina el distintivo de revisión; solo la
revisión del administrador lo quita. Hasta `640px`, las tablas mantienen las
tarjetas compartidas, `.order-review-pending .badge` permite salto de línea si
falta ancho y las acciones usan el wrap compartido.

El detalle de «Operaciones del pedido» alinea los estados a la izquierda y los
botones a la derecha, con alineación vertical centrada y salto de fila cuando
falta espacio. «Cancelar pedido» usa la variante `danger`. Las notas de
preparación, revisión y stock histórico quedan debajo.
El resultado de «Nuevo pedido» incluye «Enviar por WhatsApp» con el logo del
canal y texto visible, junto a las acciones de consulta y descarga, para usuarios
con `ORDER_NOTIFY` o `ADMIN_ALL`. Por ahora muestra un error accesible (`role="alert"`)
indicando que el envío todavía no está implementado; no solicita ni registra envíos.
Impresión, descargas y envío se agrupan bajo «Documentos del pedido», cerca del
encabezado. «Enviar por email» y «Enviar por WhatsApp» abren un `FormModal`
compacto con el contacto disponible y el formato del comprobante. Si falta el
dato o su formato impide usarlo, el modal permite cargarlo. Con permiso de
edición se puede guardar el dato en la ficha antes de solicitar el envío.
También presenta un aviso para históricos cuyo stock ya
estaba descontado. Resumen incorpora `PendingOrderReviews`, con contador y hasta
cinco pendientes de todas las fechas, incluidos entregados, más enlace al listado
completo. Tiene carga, vacío, error con reintento y revisión individual. Al revisar,
el foco vuelve al enlace estable; en Pedidos, a «Ver detalle» o búsqueda si salió
del filtro; en el detalle, al bloque de operaciones. No se bloquea el trabajo vendedor.

En la carga de pedidos, elegir un producto con Enter pasa el foco a «Cantidad»
(seleccionada). Enter en «Cantidad», «Descuento» o «Agregar» agrega la línea y
vuelve a «Producto». En «Nuevo pedido», después de agregar con el teclado se abre
el desplegable con la búsqueda anterior y se pueden elegir los productos restantes.
Escape lo cierra. Dos pulsaciones de Enter seguidas
en «Producto» activan «Confirmar pedido» con el desplegable cerrado o abierto
sin un producto marcado (sin opción activa o en «Seleccionar producto...»), si ya
hay líneas agregadas y no hay un producto pendiente de agregar. El atajo usa las
mismas validaciones que el botón. Con un producto marcado, Enter lo elige y
reinicia el doble Enter. `SearchableSelect.onEmptyEnter` permite este atajo solo
en el alta de pedidos; los demás selectores conservan su comportamiento.
En el listado, `.table-row-actions` agrupa «Ver detalle», «Editar» y «Pasar a
entregado». «Editar» aparece con `ORDER_EDIT` o `ADMIN_ALL` en pedidos confirmados.
«Pasar a entregado» aparece en pedidos confirmados con `SALE_DELIVER` o
`ADMIN_ALL`, tanto en las filas de escritorio como en las tarjetas móviles.
Consulta el saldo actual antes de abrir `DeliveryAttemptDialog` en modo de
confirmación de entrega: permite registrar pagos en efectivo o transferencia,
su número e incluir deuda anterior autorizada, sin superar el total seleccionado.
No cambia el estado hasta confirmar.
Durante la consulta bloquea las acciones de entrega y muestra carga; los errores
permiten repetir la consulta desde el botón. Si el pedido cambió de estado,
actualiza el listado sin abrir el diálogo. El registro comparte validación y
mutación con el detalle mediante `useOrderDelivery`; al guardar actualiza los
pedidos, ventas, pagos y saldos relacionados sin perder filtros ni paginación.
Al cancelar restaura el foco en el disparador; al guardar lo devuelve a «Ver
detalle» o a la búsqueda si la fila dejó de estar visible. El enlace de edición con
`?edit=true` abre el mismo formulario después de verificar permisos y estado
del detalle. Modificar la URL no concede acceso de edición. Las acciones usan
el wrap móvil compartido; cancelar o guardar elimina ese parámetro de la URL.

El campo «Producto» reutiliza `SearchableSelect` con ancho completo: escribir
filtra las opciones por nombre, sin distinguir mayúsculas ni acentos.
Las opciones muestran el nombre y la disponibilidad. Excluyen productos
inactivos o ya agregados al pedido. El formulario carga todas las páginas del
catálogo en bloques de 100 con `includeStock=true`, autorizado para crear o editar
pedidos. La búsqueda es local y conserva la navegación con teclado.
Solo este selector activa `preserveSearch`: al elegir una opción conserva el
texto de búsqueda y, después de agregarla, vuelve a mostrar ese texto. Al abrir
la lista se mantiene el filtro para seleccionar otro producto. Elegir la opción
vacía limpia la búsqueda; cambiar de cliente también la reinicia. Escape o blur
descarta texto sin seleccionar y conserva la última búsqueda utilizada.
El desplegable queda visible sobre el contenido, sin recortes de su contenedor;
en móvil ocupa el ancho del campo y admite scroll dentro de la lista.
Las tablas de precios muestran producto, precio de lista, vigencia y acciones,
sin una columna de código de producto. El nombre recibe el espacio de esa
columna y el precio conserva su énfasis numérico.
El selector `.order-product-picker` precarga los precios de los productos
disponibles al elegir cliente y lista, en lotes de hasta 100 productos. La caché
se identifica por cliente, lista y productos, y permanece vigente por un minuto.
En edición incorpora el ID del pedido y lo envía como contexto autorizado de precios.
Los usuarios con alcance asignado no precargan el historial del cliente al editar;
el administrador conserva la carga previa de ese historial.
Seleccionar, agregar o quitar un producto no genera otra consulta de precio.
No se recarga al cambiar el foco o reconectar, evitando esperas al volver a la
pantalla en celular. Los productos importados del pedido anterior se incluyen
en la carga. Cambiar cliente o lista cancela la consulta anterior; no se muestran
precios de otro contexto. La confirmación valida nuevamente los precios en el servidor.
El campo «Precio» se carga con el precio de
lista; sin `ORDER_PRICE_OVERRIDE` se presenta como texto con `.read-only-field`, igual que
«Vendedor», sin caja editable. Conserva «—», «Consultando...» y «No disponible»
para los estados vacío, carga y error, y anuncia los cambios de precio.
`ORDER_PRICE_OVERRIDE` o `ADMIN_ALL` habilitan el input de precio; `ORDER_DISCOUNT`
habilita los inputs de descuento. La modificación
se envía como `unitPriceOverride` de la línea y solo afecta a ese pedido, sin
actualizar la lista. Cambiar cliente, lista o producto restablece el precio de
lista. Cantidad y «Descuento» utilizan cajas de `4ch + 22px` con `size=4`, sin
limitar el valor a cuatro caracteres; el descuento admite valores entre `0` y
`100`. Producto, precio, cantidad, descuento y acción se alinean en una misma
fila en escritorio. El selector de producto
recibe el espacio restante de la fila y puede reducir su ancho sin desbordar
la página. En creación, cuando la columna principal mide hasta `660px` el selector ocupa
una fila completa. A `760px` de viewport o menos, el selector y «Agregar» ocupan cada uno
su fila y los campos numéricos se distribuyen en dos columnas. En edición se apilan.
La acción de agregar espera la consulta de precio y permite reintentar errores.
Durante la consulta, «Consultando…» aparece como placeholder dentro del campo,
sin cambiar la alineación de la fila. La consulta de precio tiene un límite de
`15s`, se cancela al cambiar de selección y muestra el error con reintento si no
responde a tiempo.
Cantidad y descuento se transfieren a la línea y se restablecen a `1` y `0`
para la siguiente selección. La cantidad debe ser positiva y múltiplo de `0,5`;
el descuento debe estar entre `0` y `100`.
La misma validación de cantidad se aplica al editar líneas y cargar pedidos
anteriores: acepta coma o punto decimal y solo valores positivos múltiplos de
`0,5`. Un valor inválido muestra el campo en rojo y un mensaje asociado mediante
`aria-describedby`, bloquea agregar o confirmar y deja subtotal/total en «—»
hasta corregirlo. Conserva lo escrito para permitir corregirlo sin redondear.

Las líneas agregadas reutilizan `DataTable` con la variante `.order-lines-table`.
La tabla permanece visible aunque no haya líneas. En ese caso, conserva los
encabezados y muestra la ayuda para elegir un producto dentro de una celda que
ocupa todas las columnas, sin reemplazar la tabla por un estado vacío externo.
Producto, cantidad, precio unitario, descuento porcentual, subtotal y eliminación
se muestran en una fila en escritorio. Cantidad y descuento conservan los campos
compactos; solo `ORDER_DISCOUNT` o `ADMIN_ALL` permiten editar descuentos. La acción
final mide `38px` y mantiene el nombre accesible «Quitar {producto}». Usa una cruz
en creación y una papelera en edición. El panel de creación muestra la cantidad de
productos y unidades debajo de la tabla. A `640px` o menos se utiliza el patrón
de tarjetas de la tabla compartida, con etiquetas visibles y la acción al final.

Usar [`DataTable`](../../frontend/src/shared/components/DataTable.tsx).

El listado de productos muestra costo y stock en negrita, conservando su tamaño
en escritorio y móvil y el subrayado del stock negativo.
Combina búsqueda por nombre, marca y categoría con
`SearchableSelect`, y un select de stock con «Ambos» por defecto, «Positivo»
(mayor a cero) y «Negativo» (menor a cero). «Ambos» incluye cero y productos sin
saldo registrado. Las opciones de marca/categoría incluyen el catálogo completo,
también las inactivas, para poder encontrar productos asociados. Conserva los
filtros en la URL y cada cambio vuelve a la primera página; los aplica la API
antes de paginar. Ofrece reintento si falla la carga de opciones. A `640px` o
menos cada campo ocupa una fila completa mediante utilidades locales, sin
cambiar el patrón global de `.toolbar`.

`columns` define `key`, `label`, `align`, `emphasis` y `render` opcional;
`rows` recibe `Record<string, string>[]`. Formatear valores antes de pasarlos.
`className` se agrega al contenedor `.table-wrap`, no al elemento `table`.
`emptyContent` es opcional: sin filas, se muestra dentro de una celda con
`colSpan` igual a la cantidad de columnas. `.table-empty-cell` centra el contenido
con padding vertical de `32px`; `.table-empty-message` limita el texto a `320px`.
En móvil se adapta a la tarjeta de la tabla sin generar etiquetas de columna.

- Contenedor con borde y radio `8px`, overflow horizontal en desktop.
  Tabla de ancho completo y mínimo `720px`.
- Encabezado: fondo `#f7f9f7`, texto `11px/700`, padding `13px 16px`.
  Celdas: `12px`, padding `15px 16px`, separadores y hover suave.
- Identificador principal con `emphasis`; importes y cantidades con
  `align: 'right'`. Mostrar el estado con texto, no solo color.
- Usar `render` para badges y acciones. Si se usa `onRowClick`, los controles
  internos deben evitar propagar el click cuando ejecuten una acción distinta.
- `onRowClick` hoy solo agrega click a un `tr`; no ofrece interacción equivalente
  por teclado. Para navegación nueva, ofrecer un enlace o botón explícito.
- Cada celda recibe `data-label`: a `640px` o menos la tabla se transforma en
  tarjetas y utiliza ese atributo como etiqueta. Una tabla manual debe mantenerlo.
- Agrupar búsqueda y filtros en `.toolbar`; input `.input.search-input` y
  select `.select`, con labels visibles o nombres accesibles. Los buscadores
  miden `240px` en escritorio y ocupan el ancho disponible a `640px` o menos.
  Auditoría usa `260px` en escritorio para mostrar el placeholder completo.
- En clientes, la búsqueda por nombre o identificación se combina con vendedor
  por ID mediante `SearchableSelect` (administradores), cuenta corriente «Solo
  con saldo» (saldo distinto de cero) y estado Activo/Inactivo/Todos. El estado
  inicial es Activo. Los filtros se aplican antes de paginar, se conservan en la
  URL y cada cambio vuelve a la primera página. Las opciones de vendedor incluyen
  todos los vendedores con clientes visibles, sin límite de página, y permiten
  reintentar si falla su carga. A `640px` o menos cada campo ocupa una fila completa
  mediante utilidades locales. El estado vacío indica que no hay coincidencias.
- En ventas, la búsqueda por número utiliza un input; cliente y vendedor
  utilizan `SearchableSelect` por ID con opciones de las ventas visibles para el
  usuario. Permiten escribir parte del nombre, sin distinguir mayúsculas ni
  acentos; escribir reduce las opciones y elegir una aplica el filtro.
  Para vendedores, la API devuelve únicamente sus ventas `DELIVERED`, aplicando
  esta condición antes de paginar y contar. Las opciones de cliente y vendedor
  pertenecen a esas ventas entregadas. La descripción y la opción «Todas las
  entregadas» explicitan el alcance. El administrador conserva todos los estados.
  El select Saldo pendiente alterna todas las ventas y solo
  ventas con saldo impago (excluye canceladas). Se combinan los filtros y se
  conservan en la URL; cada cambio vuelve a la primera página. A `640px` o menos
  cada filtro principal ocupa una fila completa mediante utilidades locales
  de Tailwind. Las fechas reutilizan `OrderDateFilter` y `OrderCalendar`, junto
  con `.orders-date-filters`, y forman dos columnas en móvil. Inician vacías,
  admiten límites abiertos y muestran errores para fechas o rangos inválidos.
  La carga fallida de las opciones permite reintentar sin bloquear el listado.
  Esta distribución pertenece a ventas y no cambia el patrón global.
- La pantalla independiente Pagos y deuda está retirada de la interfaz. En la
  API de pagos y deudas, el vendedor consulta los datos de sus pedidos; los
  pedidos sin vendedor usan la asignación actual del cliente, como en Pedidos.
  La API aplica ese alcance también a los conteos, la búsqueda y la imputación
  de cobros, tanto FIFO como por venta específica. El selector compartido de
  clientes omite el saldo global para vendedores y carga todas las páginas de
  deudas autorizadas. Los saldos devueltos después de cobrar corresponden a las
  deudas del vendedor; el administrador conserva la vista completa del cliente.
- En pedidos, la búsqueda en `.orders-toolbar` usa `14ch + 22px` y cada select
  toma el ancho que necesita su opción más larga o su etiqueta. `.orders-date-filters`
  agrupa dos campos de `120px` a la derecha, en la misma fila si hay espacio. A
  `640px` o menos, la búsqueda y los selects ocupan filas completas y las fechas
  se distribuyen en dos columnas iguales. Los campos usan `dd/mm/aaaa`,
  labels visibles y errores asociados mediante `aria-describedby`.
  `OrderDateFilter` conserva el texto numérico y abre `OrderCalendar` en español al
  hacer clic en el campo o su botón de calendario, o con `Enter`/`Alt+ArrowDown`.
  `.orders-date-control` posiciona el botón sobre el campo. El calendario utiliza
  un `dialog` nativo anclado al campo y limitado al viewport. Se monta en `body`
  para posicionarlo en coordenadas de la ventana y aplicar «Texto grande» una
  sola vez a su contenido. Muestra meses y días en español, semana desde el
  lunes y acciones Hoy/Limpiar/Cerrar. Conserva el
  foco dentro del diálogo y lo devuelve al disparador al cerrar; admite Escape,
  flechas, Home/End y PageUp/PageDown (con Shift cambia el año). La selección
  conserva `dd/mm/aaaa` y se aplica también en mobile.
  Las fechas inician en el día actual de Argentina. Si los filtros no encuentran
  pedidos, el estado vacío ofrece «Ver todos los pedidos»: quita búsqueda,
  estado y ambas fechas, vuelve a la primera página y conserva los límites
  vacíos en la URL para que no se restablezcan al recargar.
- `.pagination` contiene resumen y botones anterior/siguiente. Se compone en
  cada pantalla: **no existe un componente compartido `Pagination`** actualmente.
  Deshabilitar los extremos y conservar filtros/página en la URL cuando corresponda.
- No copiar anchos `nth-child` de otra tabla sin revisar columnas y permisos.
  Las variantes `.products-table-admin`/`.products-table-standard` son específicas.

```tsx
const columns: TableColumn[] = [
  { key: 'name', label: 'Producto', emphasis: true },
  { key: 'amount', label: 'Importe', align: 'right' },
  { key: 'status', label: 'Estado', render: (value) => <Badge>{value}</Badge> },
]

<DataTable columns={columns} rows={rows} />
```

## Formularios

### Roles y permisos

`RolesPage` usa `PageHeader`, `Panel` y `DataTable` en `/admin/roles`, con enlace
en Administración y acceso exclusivo de `ADMIN_ALL` en menú y ruta. La tabla
presenta rol, permisos asignados y «Editar permisos»; hasta `640px` conserva las
tarjetas compartidas. `.roles-table` permite envolver descripciones largas, y
`.role-permissions-list` presenta los permisos sin introducir un patrón de tabla global.

La edición usa `FormModal` y un `fieldset.role-permissions-grid`: dos columnas,
una hasta `760px`. Cada `label.role-permission-option` conserva borde, tokens,
padding de `12px`, checkbox nativo de `18px` y área de al menos `44px`. El texto
explica permisos obligatorios o reservados, derivados de la API; el bloqueo no
depende solo del color. Los nombres largos se envuelven. Tab/Espacio permiten
operar las casillas; las restricciones conservan su texto legible en ambos temas.

«Revisar cambios» se habilita solo ante diferencias y abre `ConfirmationDialog`
con altas, bajas y efecto sobre sesiones. Cancelar/Escape usan `useDiscardChanges`;
los errores conservan el borrador. Guardar bloquea campos, acciones y cierre. Un
conflicto devuelve al formulario, informa cómo recargar y bloquea otro guardado
con el mismo borrador. Si se invalida la sesión propia, se limpia la caché y el
login muestra el resultado del guardado. Al cerrar normalmente vuelve el foco al
botón del rol. Las variantes `.role-permission-*` son específicas de esta pantalla.

«Crear rol» usa `CreateRoleDialog` sobre `FormModal`, con nombre, código único y
`RolePermissionFields`, compartido con la edición. El código se presenta en
mayúsculas y su ayuda se asocia mediante `aria-describedby`, fuera de la etiqueta.
La grilla alinea ambos campos al inicio para conservar la altura de los inputs
cuando solo el código tiene ayuda. Explica el alcance limitado a datos asignados y que no se heredan permisos de
Vendedor. Permite una selección vacía y confirma antes de crear. Un duplicado
conserva el borrador para corregir el código; cancelar/Escape piden descarte.

Usuarios carga el catálogo completo para crear/invitar o cambiar roles, con
carga, error y reintento; no guarda sin catálogo disponible. El alta usa `FormModal`
compacto. El email ocupa su fila y los campos de rol y nombre reservan `20ch`
más espacio para bordes, padding y flecha del selector. Estos anchos son visuales.
A `760px` o menos los campos se apilan. El nombre es obligatorio para los roles
que requieren perfil de vendedor. `UserRoleDialog`
es compacto, deshabilita opciones incompatibles con el estado del perfil y
confirma reemplazo y cierre de sesiones, conservando asignaciones e historial.
Los errores permanecen dentro del diálogo. El cambio propio limpia sesión/caché
y explica el nuevo ingreso; el foco vuelve al disparador al cerrar normalmente.
No reemplazar los selectores compartidos de clientes/vendedores por estos
selects de roles. «Nuevo pedido» solo aparece con `ORDER_CREATE` o `ADMIN_ALL`.

### Acciones comerciales por permiso

`hasPermission` autoriza una acción con su permiso específico o ADMIN_ALL; no se
usa para decidir alcance global ni mostrar datos de otros vendedores. Cancelar,
reactivar, devolver ventas, imprimir/descargar y enviar comprobantes usan
ORDER_CANCEL, ORDER_REACTIVATE, SALE_RETURN, ORDER_DOCUMENT_EXPORT y ORDER_NOTIFY
respectivamente. Imprimir y enviar se muestran de manera independiente.
Las confirmaciones y validaciones comerciales conservan sus componentes compartidos.
La revisión del administrador y sus distintivos siguen exclusivos de ADMIN_ALL.

El selector de productos usa `.order-product-picker-discount` cuando incluye
descuentos, con las mismas cinco columnas y medidas de la variante anterior;
sin ese permiso conserva cuatro columnas y precio editable o de lectura según
ORDER_PRICE_OVERRIDE. A 760px se apila con el patrón existente. El descuento
general solo tiene input con permiso; el total conserva el porcentaje histórico
en edición aunque ese campo sea de lectura. No cambiar estas reglas por el nombre del rol.

En Productos, el alta y la edición requieren categoría, marca y descripción
(hasta 200 caracteres). «Nombre» es un `output.read-only-field` con nombre
accesible y actualización anunciada: concatena marca y descripción separadas
por un espacio. La API calcula y guarda el nombre, sin aceptar un nombre manual.
Al editar se carga la descripción separada; los productos antiguos sin marca
deben seleccionarla antes de guardar. El formulario reutiliza `.form-grid` y
sus dos columnas, apiladas a `760px` o menos. El costo y los precios por lista
conservan sus validaciones. Renombrar una marca actualiza los nombres del catálogo
y refresca las consultas relacionadas, preservando los nombres históricos de
pedidos y ventas.

[`SearchableSelect`](../../frontend/src/shared/components/SearchableSelect.tsx)
combina un campo editable y una lista desplegable con búsqueda local por nombre.
Recibe opciones `{ id, name, detail? }` y conserva el ID elegido, incluso con nombres
repetidos. `detail` (por ejemplo «Stock: 12» en el producto del pedido) se muestra
a la derecha en `.searchable-select-detail` (`11px`, color atenuado), se expone
como descripción accesible de la opción y no participa de la búsqueda; el nombre
accesible de la opción sigue siendo solo `name`. La opción vacía siempre está disponible para quitar la selección;
su texto y significado dependen del contexto (todos, sin asignar o seleccionar).
Si se abandona la búsqueda sin elegir, conserva la selección anterior.
Usa las clases `.searchable-select-*`, el campo `.input` de `39px`, ancho base
de `210px` y lista con scroll de hasta `240px`, sin desbordar el ancho del campo.
El ancho móvil es común: a `640px` o menos ocupa todo el ancho disponible.
Expone roles combobox/listbox/option y opción activa mediante
`aria-activedescendant`: flechas recorren opciones, Enter elige, Escape cancela
y Tab cierra sin atrapar el foco. Incluye estados de carga, deshabilitado,
selección no disponible y búsqueda sin coincidencias.

### Patrón obligatorio de selección de clientes y vendedores

Usar [`CustomerSelect` y `SellerSelect`](../../frontend/src/shared/components/EntitySelect.tsx)
en cualquier filtro o formulario que elija una entidad por ID. Ambos delegan la
interacción a `SearchableSelect`; no sustituirlos por un select nativo ni duplicar
su búsqueda en una feature. Las búsquedas libres del listado de clientes o de
vendedores siguen usando un input: buscan texto, no seleccionan una entidad.

- En modo `filter` (por defecto), las etiquetas son «Buscar por cliente» y
  «Buscar por vendedor»; la opción vacía es «Todos los clientes/vendedores».
  Elegir una opción aplica su ID y la pantalla reinicia su paginación. El control
  mide `210px` en escritorio y ocupa una fila completa a `640px` o menos.
- En modo `selection`, ocupa todo el ancho del campo del formulario. `label`
  conserva el contexto («Cliente del pago», «Vendedor asignado», etc.) y
  `emptyLabel` define el significado del ID vacío: seleccionar, sin asignar,
  todos o usar el vendedor del cliente. `required` exige elegir una opción;
  escribir un nombre sin seleccionarlo no satisface la validación nativa.
  `autoFocus` permite enfocar el campo de búsqueda al montarlo habilitado.
- Clic o flechas abren la lista. Escribir filtra las opciones por nombre sin
  distinguir mayúsculas ni acentos; seleccionar confirma el ID. Flechas recorren,
  Enter elige, Escape cancela y Tab/clic fuera cierran conservando la selección
  previa. El texto escrito sin confirmar nunca se envía como ID.
- Las opciones vacías permanecen disponibles para quitar una selección; nombres
  repetidos conservan IDs distintos. Opciones de carga, selección no disponible,
  bloqueo y búsqueda sin coincidencias tienen textos compartidos. `loading`
  deshabilita el campo y su botón; `invalid` y `describedBy` asocian errores/ayuda.
- Los formularios cargan todas las páginas de opciones autorizadas mediante
  [`apiGetAllPages`](../../frontend/src/shared/api/pagination.ts), conservando los
  filtros y permisos de cada consulta. Ventas y el filtro de vendedores en
  clientes usan sus endpoints de opciones completos. La reasignación no toma
  sus opciones de la página visible de vendedores. Si la carga falla, permitir
  reintentar sin ofrecer un conjunto incompleto como si estuviera completo.

Aplicado a ventas, filtro y formulario de clientes, creación de pedidos, pagos
y reasignación de clientes/pedidos. Los revendedores son clientes: su filtro
(«Buscar por revendedor») y su selección en «Revendedor» usan `CustomerSelect`
con esos `label`/`emptyLabel` ([Revendedores](#revendedores)). Las reglas de negocio (vendedores activos de
destino, vendedor heredado del cliente y selección obligatoria en pedidos/pagos) pertenecen a cada
pantalla; la interacción de búsqueda y selección es siempre la misma.

- `.form-grid`: dos columnas, gap `14px`; una columna a `760px` o menos.
- Cada campo usa `label.field` con un texto y un control asociado. Si el label
  no envuelve el control, vincular `htmlFor` con `id`.
- `.input` y `.select`: altura mínima `39px`, borde `#c7d4ce`, radio `4px`,
  texto `12px` y padding horizontal `10px`. `.select` tiene ancho automático y
  mínimo `175px`; acotar la variante cuando deba ocupar toda una columna.
- Para texto multilínea usar `.input.textarea`: altura mínima `96px`, padding
  `10px` y resize vertical. Mantener fuente heredada y foco visible.
- `.read-only-field` muestra un dato sin edición; no simularlo con un input activo.
- `.helper-text` sirve para ayuda; `.error-text`, para errores. Asociar ayuda/error
  al control con `aria-describedby` y usar `aria-invalid` cuando corresponda.
- Mantener acciones fuera de la grilla de campos en modales. La variante
  `.customer-form-actions` pertenece al formulario de clientes.
- Bloquear doble envío; conservar datos ante error; mostrar el estado pendiente
  de forma visible. Seguir el idioma actual de la interfaz con texto profesional.

## Modales y confirmaciones

Los formularios de alta/edición usan el componente compartido
[`FormModal`](../../frontend/src/shared/components/FormModal.tsx); las
confirmaciones, `ConfirmationDialog`. No crear formularios de edición como
paneles insertados al inicio de la página: si el usuario hizo scroll, aparecen
fuera de la vista (problema reportado en Productos y Listas de precios).

### Formularios en modal (`FormModal`)

`FormModal` recibe `label` (nombre accesible) o `labelledBy` (ID del título
visible), y admite `describedBy` (ID de la descripción) y `className` para
variantes locales. Recibe también `onClose`, `busy` y `size` (`default` o
`compact`). Envuelve un `Panel` con el formulario o detalle. Usa `<dialog>`
con `showModal()` en un portal a
`document.body`: se dibuja en la capa superior del navegador, por lo que siempre
abre dentro del viewport sin importar el scroll; bloquea el scroll del fondo con
`lockBodyScroll`, enfoca el primer campo, contiene Tab/Shift+Tab y devuelve el
foco al disparador al cerrar. Escape llama a `onClose` salvo con `busy`; pasar el
mismo `requestDiscard` de `useDiscardChanges` que usa «Cancelar» para no perder
datos. Mostrar los errores del formulario dentro del panel.

- `.form-modal`: ancho `min(100vw - 40px, clamp(640px, 76vw, 1040px))`, altura
  máxima `100dvh - 40px` con scroll interno, panel con padding `24px` y radio
  `10px`, título de `20px`. Hasta `760px`: ancho `100vw - 24px` y padding `16px`.
- `.form-modal-compact`: ancho máximo `480px`, para formularios cortos (crear o
  renombrar listas de precios).
- Un diálogo abierto desde un `FormModal` (por ejemplo, confirmar el cambio de
  estado de un producto) debe ser nativo (`ConfirmationDialog`): un
  `.modal-backdrop` común quedaría debajo de la capa superior y no recibiría foco.

Uso actual: formulario de producto (`ProductsPage`), alta y renombre de listas
(`PriceListsPage`). El formulario de clientes mantiene su variante `.customer-modal`.

Los modales básicos `.modal-backdrop > .panel` restantes limitan su altura a
`100dvh - 40px` con scroll interno para que el contenido largo no quede cortado.

### Patrón visual común

- Overlay: `.modal-backdrop.catalog-modal-backdrop`; posición fija, pantalla
  completa, centrado, padding `20px`, capa base `20`, fondo oscuro y blur `2px`.
- Panel: `section.panel.catalog-modal`; ancho `min(100%, 480px)`, radio `10px`,
  padding `0`, altura máxima `calc(100vh - 40px)` y scroll interno.
- `.catalog-modal-header`: título `h2` de `20px` y descripción breve,
  padding `24px 26px 20px`, borde inferior.
- `.catalog-modal-body`: una columna, gap `10px`, padding `22px 26px 24px`;
  campos con altura mínima `44px` y texto `14px`.
- `.catalog-modal-footer`: acciones a la derecha, gap `10px`, borde superior,
  fondo `#fafcfb`, padding `16px 26px`, botones de altura mínima `40px`.
- A `760px` reducir márgenes/padding y ajustar la altura disponible; a `640px`
  las acciones del pie se expanden por igual.

### Comportamiento requerido para nuevas interfaces

- `role="dialog"`, `aria-modal="true"` y `aria-labelledby` asociado al título
  visible; `aria-describedby` cuando haya una descripción útil.
- Al abrir, enfocar el primer campo o “Cancelar” en una confirmación destructiva.
  Contener el foco dentro del diálogo, impedir interacción con el fondo y devolver
  el foco al disparador al cerrar.
- Permitir `Escape` cuando no haya una operación pendiente. Si hay cambios sin
  guardar, resolver primero la confirmación de descarte.
- Cancelar/cerrar debe estar disponible; mientras se guarda, bloquear doble
  confirmación y cualquier cierre que interrumpa el flujo.
- Mostrar errores dentro del panel con `role="alert"`. Mantener el diálogo abierto
  y los datos disponibles si falla la operación.
- En una confirmación, identificar el elemento y la consecuencia. Usar `danger`
  para desactivar/eliminar y `primary` para activar o guardar.
- Verificar scroll y altura en mobile antes de considerar terminado el cambio.

**Límite actual:** varios modales siguen implementados por pantalla con
`.modal-backdrop`. El formulario de catálogo tiene foco inicial y manejo de
`Escape`, pero no implementa contención ni restauración del foco. No asumir que
`aria-modal` resuelve esos comportamientos; para modales nuevos usar `FormModal`
o `ConfirmationDialog`.

### Imprimir y descargar documentos (`DocumentActions`)

[`DocumentActions`](../../frontend/src/shared/components/DocumentActions.tsx) recibe
`path` (endpoint PDF autenticado), `filename`, `printLabel`, `downloadLabel`,
`disabled` y `onDone`. Cada etiqueta habilita su acción y se puede omitir.
Muestra los botones secundarios en `.document-actions`
(flex con wrap, gap `8px`): imprimir llama a `apiPrintPdf`, que carga el PDF en un
iframe oculto y abre el diálogo de impresión sin descargar el archivo; en
dispositivos táctiles (`pointer: coarse`) abre una pestaña en el mismo clic y
muestra allí el PDF para imprimir o compartir. Descargar usa `apiGetBlob`. Mientras
trabaja muestra «Preparando...»/«Descargando...» y bloquea ambos botones; los
errores aparecen debajo (`.document-actions-error`, `role="alert"`). Nombrar las
acciones con el documento y formato: «Imprimir remito A4», «Imprimir ticket 80 mm»,
«Imprimir lista», «Imprimir estado A4». Uso actual: acciones del pedido, tabla de
precios (con los filtros aplicados) y ficha del cliente (estado de deuda A4 y 80 mm,
en `.customer-statement-actions`).

### Confirmaciones reutilizables de cancelación y descarte

[`ConfirmationDialog`](../../frontend/src/shared/components/ConfirmationDialog.tsx)
centraliza las confirmaciones destructivas. Montarlo solo cuando se requiere
confirmación. Recibe `title`, `description`, `confirmLabel`, `onConfirm` y
`onCancel`; opcionalmente `cancelLabel`, `pendingLabel`, `pending`, `error` y
`confirmVariant` (`danger` por defecto; `primary` para confirmar una creación o un
guardado, por ejemplo crear una lista con los precios de la General o el aviso de
listas que la siguen), e `initialFocus` (`cancel` por defecto o `confirm`).
Usa `<dialog>` con `showModal()` en un portal a `document.body`: contiene el foco,
impide interacción con el fondo y queda sobre los formularios existentes. Tab y
Shift+Tab recorren sus acciones de forma circular sin salir del diálogo. Enfoca
la acción segura al abrir por defecto y devuelve el foco al disparador si sigue
montado. Confirmar una compra pendiente usa `initialFocus="confirm"` para que
Enter active «Aceptar» tras mostrar el efecto en stock.
Escape conserva los datos. Mientras `pending` está activo, ambas acciones y
Escape quedan bloqueados; los errores se muestran dentro con `role="alert"`.
No cierra al pulsar el fondo.

`.confirmation-dialog-*` conserva el patrón de catálogo: ancho `480px`, radio
`10px`, encabezado/cuerpo/pie separados, botones compartidos y confirmación
`danger`. Limita la altura con `100dvh` y scroll interno; a `760px` reduce padding
y márgenes, y a `640px` las acciones comparten el ancho y permiten wrap.

En Marcas y categorías, un administrador puede eliminar definitivamente una
marca o categoría activa o inactiva cuando muestra cero productos asociados. La acción
abre `ConfirmationDialog`; si hay productos o reglas de comisión que impiden
borrarla, el error queda en el diálogo. Al eliminarla, el foco vuelve a la
pestaña activa. Desactivar sigue disponible como acción independiente.

[`useDiscardChanges`](../../frontend/src/shared/useDiscardChanges.tsx) recibe
`hasChanges`, `onDiscard`, `disabled` y `protectUnload` opcionales; devuelve
`requestDiscard` y `discardDialog`. Sin cambios ejecuta el cierre directamente;
con cambios ofrece «Seguir editando» y «Descartar cambios». Se usa al cancelar
la creación de pedidos y al cerrar el formulario de clientes. La cancelación
de un pedido confirmado usa `ConfirmationDialog` con su consecuencia específica,
estado de carga y error recuperable.

En pedidos, `protectUnload` mantiene el aviso nativo para recargar/cerrar la
pestaña. Ese aviso es controlado por el navegador y no admite diseño ni texto
personalizado. El hook confirma los cierres explícitos que usan `requestDiscard`;
no bloquea por sí mismo navegación del sidebar ni atrás/adelante del router.

### Ajuste de stock

[`StockAdjustmentDialog`](../../frontend/src/shared/components/StockAdjustmentDialog.tsx)
comparte el formulario de ajuste en Stock (Productos) e Inventario. Reutiliza
`.confirmation-dialog-*`: encabezado con producto, resumen de stock actual y
resultante en dos columnas, selector de tipo de ajuste, campos de cantidad y
motivo en una columna y acciones fuera de la grilla.

«Nuevo producto» ofrece «Stock inicial (opcional)» a administradores con
`STOCK_ADJUST`. El campo admite cantidades no negativas en múltiplos de `0,5`,
queda vacío por defecto y muestra errores junto al campo. Si se indica una
cantidad positiva, se registra el movimiento «Stock inicial» al guardar.

El tipo de ajuste (`.stock-adjustment-mode`, radios de `44px`) ofrece «Indicar
cantidad final» (por defecto: reemplaza el stock por la cantidad contada y muestra
la diferencia) y «Sumar o restar» (delta firmado). `buildStockAdjustment` valida
el borrador y arma el cuerpo (`targetQuantity` o `quantity`) para ambas pantallas.
El resultado solo aparece para cantidades válidas múltiplos de `0,5` (en delta,
distintas de cero); un saldo negativo mantiene una advertencia textual. El motivo
es opcional, usa un textarea de al menos `88px`; los controles tienen `44px` de
altura mínima. El resumen y los campos se comparten con el formulario de mermas.

Usa un diálogo nativo con foco inicial en cantidad, contención y restauración del
foco, bloqueo del fondo y scroll interno limitado al viewport. Cancelar y Escape
reutilizan `useDiscardChanges` si hay datos ingresados. Ambos diálogos comparten
`lockBodyScroll` para restaurar el scroll al cerrar el último modal, incluso si se
descartan los cambios con ambos abiertos. Durante guardado se bloquean
campos y cierre; los errores conservan los datos y se anuncian dentro del diálogo.
Hasta `760px` conserva los márgenes y padding del diálogo compartido y hasta
`640px` sus acciones se expanden por igual. El nombre del producto admite varias
líneas sin desbordar y la ayuda está asociada al campo de cantidad.

### Mermas

Stock muestra «Registrar merma» en acciones de productos activos con
`ADMIN_ALL` y `STOCK_SHRINKAGE`, dentro de «Más acciones». Reutiliza `FormModal` compacto,
`.stock-adjustment-summary` y `.stock-adjustment-fields`. Cantidad perdida
acepta múltiplos positivos de 0,5. El motivo es obligatorio. El stock estimado
advierte cuando queda negativo. Escape y Cancelar protegen el borrador.
Al guardar, vuelve al botón o a la búsqueda si la fila sale del filtro.

«Ver mermas» lleva a `/products/shrinkages` con acceso administrativo.
El reporte reutiliza `OrderDateFilter`, `DataTable` y la paginación
compartida. El período se conserva en la URL y se evalúa en hora argentina.
«Volver a Stock» recupera los filtros del listado de origen.

### Historial de artículos vendidos

«Artículos vendidos» aparece en Ventas con `REPORTS_VIEW` o `ADMIN_ALL`.
Dashboard enlaza al reporte con su período seleccionado. En
`/sales/products`, el buscador por artículo y los campos Desde y Hasta
reutilizan la barra de filtros y `OrderDateFilter`. El período usa el calendario
argentino y empieza en el mes actual. La URL guarda búsqueda, fechas y página.
El rango inválido muestra un aviso y oculta los resultados.

La tabla comparte `DataTable`, sus tarjetas a 640px y la paginación habitual.
«Ver ventas» abre el historial del artículo con las mismas fechas. Muestra
ventas individuales y totales del período. Volver conserva filtros y página del
listado. Los estados usan `Badge` y cada «Ver pedido» abre el detalle existente.

### Compras del cliente

«Ver compras del cliente» aparece en la ficha con `REPORTS_VIEW` o
`ADMIN_ALL`. El reporte reutiliza `OrderDateFilter` y guarda el período y la
página en la URL. «Volver a la ficha» recupera la página del historial y los
filtros de origen. Un rango inválido muestra un aviso y oculta el reporte.

El panel del total conserva el importe del período al paginar. Imprimir y
descargar usan `DocumentActions` con el período completo. Cada pedido usa
`details` y `summary` con foco visible para abrir el desglose con teclado.
Las líneas usan `DataTable` y sus tarjetas compartidas hasta `640px`.

### Configuración de comisiones

`ADMIN_ALL` puede abrir «Planes y asignaciones» y «Liquidación de revendedores»
desde el panel «Comisiones» de Configuración comercial. La pantalla de planes reutiliza
`DataTable` para los planes, `FormModal` y `useDiscardChanges` para editarlos
y dos paneles de asignación con el mismo formulario: «Asignación a vendedores»
con `SellerSelect` y «Asignación a revendedores» con `CustomerSelect`, ambos en
modo selección y con todas las páginas de opciones. Cada formulario tiene nombre
accesible (el título del panel) y su propio selector de plan, visible desde el
inicio. El selector se deshabilita y explica qué falta si aún no se eligió un
beneficiario o todavía no hay planes. Las reglas se agrupan con `fieldset` y usan la
grilla habitual de campos, con una columna a `760px` o menos. La selección de
marca, categoría y artículo reutiliza `SearchableSelect`.

«Liquidación de comisiones» elige primero «Comisiones de» (vendedores o
revendedores, guardado en la URL como `type`) y luego el beneficiario con
`SellerSelect` o `CustomerSelect`. Reutiliza los campos Desde y Hasta y
`DataTable` para el historial por período, con el cliente de cada pedido. El
saldo pendiente incluye todos los movimientos del beneficiario. «Registrar pago
al vendedor» o «al revendedor» abre `ConfirmationDialog` con el importe y el
beneficiario elegidos. Si el saldo
cambió, la pantalla actualiza el importe y pide revisar la nueva liquidación.

### Catálogo y administración

- El alta y la edición de marcas, categorías, proveedores, vendedores y zonas
  usan `FormModal`. La desactivación de marcas y categorías, los cambios de
  estado de vendedores, la eliminación de zonas y el bloqueo, desbloqueo o
  revocación de sesiones usan `ConfirmationDialog`.
- En «Nuevo vendedor», quienes tienen `ADMIN_ALL` y `RESELLER_MANAGE` pueden
  elegir el tipo de perfil. «Vendedor» requiere vincular un usuario activo.
  «Revendedor» solo pide el nombre y crea la ficha en Clientes.
  «Perfiles comerciales» muestra vendedores y revendedores en la misma tabla,
  con una columna «Tipo», búsqueda por nombre, filtro de estado y paginación
  común. «Mostrar» permite limitar la tabla a un tipo. Al guardar un revendedor,
  la búsqueda se completa con su nombre en «Todos los perfiles». Su acción
  «Ver ficha» abre la cuenta en Clientes.
- Las reasignaciones de vendedores usan el selector compartido con las opciones
  de todas las páginas. La confirmación muestra la consecuencia, bloquea el
  cierre durante el guardado y conserva la selección si la API devuelve un error.
- Roles muestra los permisos en dos columnas desde `761px` y en una en espacios
  más estrechos.
- Configuración comercial usa la grilla `content-grid two-thirds` para Límite
  de crédito y Comisiones. El límite indica su estado o si hay cambios sin guardar.
  La casilla de activación y el importe se agrupan. El importe aparece cuando la
  casilla está marcada.
  «Guardar límite» conserva el ancho de su contenido.
  «Datos del comercio» ocupa el ancho completo y reutiliza los campos del
  formulario compartido. El nombre, la dirección y el teléfono se guardan juntos
  y se usan al generar tickets de venta.
- Auditoría muestra las operaciones y los resultados en español. Un identificador
  largo se recorta visualmente. El valor completo aparece al pasar el puntero
  o al desplegarlo con teclado o toque. La búsqueda acepta los códigos de
  auditoría y las etiquetas traducidas completas.

### Variantes específicas por pantalla

| Variante | Alcance y diferencia |
| --- | --- |
| `.modal-backdrop .panel` | Patrón básico de `520px`; no incluye por sí solo límite de altura ni las tres secciones |
| `.product-movements-dialog` | Movimientos del producto: ancho adaptable hasta `1100px` y altura limitada al viewport con scroll vertical. `.product-movements-table` muestra las cinco columnas sin scroll horizontal, con anchos proporcionales y texto completo adaptable, incluidas las referencias largas. Hasta `640px` conserva las tarjetas compartidas |
| `.customer-modal` | Formulario de clientes de ancho adaptable hasta `1040px`, scroll interno y descarte mediante `ConfirmationDialog` |
| `.price-status-dialog-*` | Confirmación de estado de listas: `480px`, radio `12px`, resumen del cambio, cierre y capa `35` |
| `.price-lists-page .modal-backdrop` | Ajuste de pricing con capa `30` |
| `.price-list-pending`, `.price-products-table-follow` | Listas que siguen a la General: contador «N sin revisar» en la tarjeta (fondo de advertencia, `11px`) y tabla de cinco columnas con «Estado» (mínimo `880px`; en tarjetas hasta `640px`) |
| `.price-lists-page .panel-header` | Hasta `640px` los controles del encabezado (filtros, confirmar pendientes) pasan a su propia fila |
| `.compact-list-table` | Clientes, pedidos, stock y auditoría permiten ajustar el texto de las celdas. Las acciones pueden pasar a otra línea dentro de la misma celda. Hasta `640px` conservan la disposición compartida de tarjetas |
| `.sale-detail-dialog` | Detalle de venta: hasta `1040px` sobre `760px`, padding `28px`, títulos de `20px` y tablas/datos de `14px`; hasta `760px` conserva el ancho y tipografía compactos. Altura limitada al viewport con scroll interno |
| `.delivery-attempt-dialog` | Registro de entrega: diálogo nativo de hasta `720px`, encabezado y pie fijos, cuerpo con scroll y pagos con medio, importe y eliminación alineados. Número de transferencia dentro del mismo fondo. Hasta `640px`, los pagos se apilan y las acciones del pie comparten el ancho |

Mantener estas diferencias acotadas. Si se generaliza una variante, extraer la
estructura reutilizable y documentar su API antes de extenderla a otras features.

[`DeliveryAttemptDialog`](../../frontend/src/features/orders/DeliveryAttemptDialog.tsx)
pertenece al flujo de entrega de pedidos. Muestra el pedido, el saldo pendiente y
el resumen del pago antes de confirmar. El título es «Pago», sin «opcional» ni
subtítulo. Ofrece «Agregar deuda anterior» cuando existe deuda autorizada vigente,
con importe editable, «Pagar total» y «Pagar solo esta venta» dentro del fondo
compartido de los pagos. El medio de pago conserva el ancho necesario para
«Transferencia», sin expandirse. La caja del importe mide `14ch + 22px` para
mostrar nueve dígitos enteros, dos separadores de miles, coma y dos decimales;
no limita la cantidad de caracteres ingresados. Al perder el foco muestra
formato argentino con puntos de miles y al menos dos decimales, conservando
hasta cuatro decimales sin redondearlos. Al enfocar vuelve al texto ingresado;
el formato no modifica el borrador, la validación ni el importe enviado.
«Pagar total» se alinea inmediatamente a la derecha del importe del primer pago;
sin pagos permanece disponible dentro del fondo para crear el pago completo. La
eliminación usa una papelera roja de `18px` dentro de un botón `danger` de `44px`,
con nombre accesible «Quitar pago {n}». Hasta `640px`, el medio ocupa su propia
fila con la papelera, conservando su ancho compacto; el importe y «Pagar total»
comparten la fila siguiente, también desde `320px`.
Valida el máximo
disponible; el pago se aplica primero a esta venta y luego a ventas anteriores.
Permite quitar incluso el último pago y elimina el número cuando ya no hay
transferencias. Solicita el número en el mismo bloque de fondo del pago por
transferencia. Al elegir «No entregado», limpia los pagos y la deuda seleccionada
y exige una observación; «Entregado» no muestra observación. Contiene el foco, bloquea
el fondo y su scroll, enfoca el resultado al abrir y restaura el foco al cerrar.
Escape y todas las acciones/campos quedan bloqueados durante el guardado.
La prop opcional `collectionsNotice` reemplaza la sección de pago de una entrega
por ese texto (`.delivery-attempt-empty`) cuando el usuario no puede cobrar; hoy
la usa `resellerCollectionsNotice` para pedidos de revendedor sin `ADMIN_ALL`.
Tab y Shift+Tab recorren circularmente los controles habilitados del diálogo.
Los errores permanecen dentro del cuerpo sin perder los datos. Hasta `760px`,
usa márgenes de `14px` y padding reducido. Su cuerpo desplaza sin ocultar el pie.

El modal de alta/edición de clientes usa `min(100%, clamp(640px, 76vw, 1040px))`
de ancho, altura máxima `calc(100dvh - 40px)` y scroll interno. Conserva dos
columnas de campos en escritorio y una a `760px` o menos. El panel tiene radio
de `10px` y padding uniforme de `24px` (`16px` a `760px` o menos), incluido el
espacio debajo de las acciones. La grilla no agrega margen inferior. El título
de `20px` y el botón de cierre se alinean al centro; el cierre usa un SVG de
`20px` dentro de un área de `40px`, radio `7px`, hover discreto y el foco visible
compartido. Mantiene el nombre accesible y el bloqueo durante el guardado.
Esta variante no modifica las dimensiones ni el cierre de otros modales.

## Badges, feedback y estados

- [`Badge`](../../frontend/src/shared/components/Badge.tsx): `tone="soft"`
  por defecto, `strong` para énfasis y `muted` para estados secundarios.
  Píldora con padding `4px 8px` y texto `10px/700`.
- Éxito: `.success-text` con `role="status"`; error: `.error-text` con
  `role="alert"`; advertencia: `.warning-text`, borde cobre y descripción de la
  consecuencia. No comunicar un estado únicamente por color.
- [`EmptyState`](../../frontend/src/shared/components/EmptyState.tsx) recibe
  `title`, `description` y `action` opcional. Por defecto, usa un mínimo de
  `220px`. La variante `compact` ajusta la altura al contenido y se usa en «Pagos
  registrados» del pedido cuando comparte la fila con la cuenta corriente.
- Distinguir carga, error recuperable, ausencia de datos y filtros sin resultados.
  Ofrecer reintento o limpieza de filtros cuando corresponda; mostrar acciones
  de creación solo si el usuario tiene permisos.
- En el login, solo `401`/`403` muestran «Credenciales inválidas». Un error de
  conexión o `5xx` informa que no se pudo conectar o que el servidor no está
  disponible, conservando los campos y permitiendo reintentar. El mensaje usa
  `.error-text` con `role="alert"`. El campo de contraseña se puede mostrar u ocultar
  con un botón que conserva su valor y no envía el formulario.
- Usar `state="loading"` para mostrar un indicador de progreso y anunciarlo
  como estado. `state="error"` usa un símbolo de advertencia y anuncia el error.
  El reintento se pasa como `action`. El indicador respeta movimiento reducido.

## Responsive y movimiento

| Ancho máximo | Comportamiento actual |
| --- | --- |
| `1050px` | Menú lateral desplegable, barra superior fija de `66px`, contenido con padding `28px`, métricas en dos columnas |
| `760px` | Barra superior con padding horizontal `16px`, contenido `24px 16px`, header/formularios/layouts principales en una columna |
| `640px` | Toolbar de ancho completo, acciones generales expandidas, tablas en tarjetas y pies de modal adaptados |
| `460px` | Métricas en una columna, paginación y encabezados de sección apilados |

- Verificar desde `320px` de ancho, que es el mínimo definido en `body`.
- Evitar overflow horizontal del documento. El scroll de tabla queda dentro
  de `.table-wrap` antes de su transformación mobile.
- Las reglas específicas de pricing se encuentran al final de `styles.css`;
  revisar el orden de la cascada al agregar overrides y sus media queries.
- Las tablas de precios usan un ancho mínimo de `760px` en escritorio para
  conservar sus cuatro columnas. A `640px` o menos se elimina ese mínimo y
  los anchos de columna para usar tarjetas sin desbordar el documento.
- Respetar `prefers-reduced-motion`. Ya existe una regla global que reduce
  animaciones y transiciones; no introducir movimiento decorativo continuo.

## Criterio de cierre de un cambio de UI

- [ ] Se leyeron esta guía y los componentes/selectores afectados.
- [ ] Se reutilizaron tokens, componentes y patrones existentes.
- [ ] Las variantes nuevas están acotadas; no alteran otras pantallas por cascada.
- [ ] Se contemplaron carga, vacío, error, éxito, permisos y estado pendiente.
- [ ] Teclado, labels, foco visible y comportamiento de diálogo están verificados.
- [ ] Desktop y mobile funcionan, incluidas tablas y contenido largo de modales.
- [ ] Los cambios de comportamiento tienen pruebas relevantes; los cambios
      puramente documentales requieren verificar referencias, no reconstruir la app.
- [ ] Si cambió un patrón reutilizable, la guía se actualizó en el mismo cambio.

Para código frontend ejecutar los checks pertinentes definidos en
[`package.json`](../../frontend/package.json): `npm run build`, `npm test` y,
cuando cambie un flujo completo o comportamiento responsive, las pruebas E2E
relevantes. Ejecutarlos desde `frontend` y documentar qué se verificó.

`npm run test:navigation` verifica el shell en doce anchos de `320px` a `1920px`,
incluidos ambos lados de `1050px`, teclado, Escape, fondo, permisos, cambio de
resolución y cierre de sesión. Usa respuestas de API simuladas, sin backend ni
base de datos; genera capturas en `test-results/navigation`. Si Chromium no está
instalado, admite `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` como el resto de los E2E.

### Verificación de equivalencia visual

[`verify-style-parity.mjs`](../../frontend/scripts/verify-style-parity.mjs)
compara una hoja CSS de referencia con el CSS compilado. Usa un conjunto de
markup representativo de shell, métricas, botones, formularios, tablas por permisos,
listas de precios y modales. Compara estilos calculados (incluidos pseudo-elementos),
geometría y capturas exactas en nueve anchos de `320px` a `1440px`, con estados
normal, hover, foco, diálogos y movimiento reducido. No reemplaza las pruebas de
flujo con datos reales ni representa una auditoría completa de accesibilidad.

Antes de modificar estilos, guardar la hoja **compilada** de referencia fuera de
`src`. Después compilar y ejecutar desde `frontend`:

```text
npm run build -- --outDir dist-tailwind-check
node scripts/verify-style-parity.mjs <baseline.css> dist-tailwind-check
```

Requiere Chromium de Playwright instalado. Si se utiliza un ejecutable ya disponible,
indicar su ruta mediante `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`. Las capturas se
guardan en `test-results/style-parity`.
