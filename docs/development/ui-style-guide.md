# Guía de estilos y patrones de interfaz

Referencia obligatoria para crear, modificar o revisar interfaces del proyecto.
Revisada contra el código actual el **2026-09-30**.

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

Interfaz operativa compacta: superficies claras, texto verde oscuro, acento verde
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
- Fuente global: `Aptos, "Segoe UI", ui-sans-serif, system-ui, sans-serif`.
  Los números usan `tabular-nums`.
- Título de página: `h1`, `clamp(28px, 4vw, 42px)`, altura de línea `1.05`.
  Título de panel: `h2`, `15px`; subtítulo: `h3`, `14px`.
  Descripciones: `13px`; controles y tablas: `12px`; etiquetas: `11px`.
- Radios por contexto: paneles/botones `5px`, campos `4px`, tablas `8px`, modal
  de catálogo `10px`, badges tipo píldora `999px`. No uniformarlos arbitrariamente.
- Reutilizar los espacios definidos por cada patrón: panel `20px`, formulario
  `14px`, acciones `8px`. No existe una escala de spacing tokenizada actualmente.
- Foco global en botones, inputs, selects y enlaces: contorno de `3px solid
  #bb8754`, separado `2px`. Los campos tienen además borde verde y halo al enfocar.
  Extender el foco visible a cualquier nuevo control, incluido `textarea`.

## Estructura de páginas, paneles y métricas

- Respetar el shell existente: `.app-shell`, `.sidebar`, `.main-area`, `.topbar`
  y `.page-content`. No duplicarlo dentro de una feature.
- `.page-content`: ancho máximo `1440px`, centrado y padding desktop `38px`.
- Usar [`PageHeader`](../../frontend/src/shared/components/PageHeader.tsx):
  `eyebrow` y `title` obligatorios; `description` y `actions` opcionales.
  Las acciones se agrupan automáticamente en `.page-actions`.
- Usar [`Panel`](../../frontend/src/shared/components/Panel.tsx): `title`,
  `description` y `action` opcionales, más `children`. Genera un `section.panel`
  y un `.panel-header` cuando corresponde.
- `.content-grid` agrupa secciones con `20px` de separación;
  `.content-grid.two-thirds` distribuye contenido principal y secundario.
- Usar [`StatCard`](../../frontend/src/shared/components/StatCard.tsx) con
  `label`, `value`, `detail` y `emphasis` opcional. `.stats-grid` tiene cuatro
  columnas; `.stats-grid.compact`, tres. Borde superior verde y valor de `25px`.

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
  botones `34px` de alto y texto `11px`.
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

La creación de pedidos usa un único `Panel` de ancho completo con los datos y
productos. El cierre `.order-checkout` usa tres columnas sobre fondo blanco,
separadas de los productos por un borde superior: descuento general compacto
de `96px` con sufijo `%`, desglose de subtotal/descuento de hasta `240px` y
total con confirmación de hasta `340px`. Solo el bloque final usa fondo canvas,
borde y padding `16px`; el total se destaca en `22px` y el botón de ancho
completo queda debajo, con altura mínima `42px`. Sin permisos de administrador
se muestran solo desglose y confirmación. El descuento admite coma decimal,
muestra errores asociados al campo para valores fuera de `0–100` y bloquea
confirmación hasta corregirlos. A `1050px` se usan dos columnas, con descuento
y desglose a la izquierda y total/confirmación a la derecha. A `760px` o menos se apilan los tres bloques,
con etiqueta y porcentaje alineados en una fila. No se muestra la aclaración
de cuenta corriente en este cierre.
Al elegir un cliente, su saldo aparece debajo de los productos en `.order-account`,
con importe destacado, un campo «Importe para el remito» y acciones para agregar
un importe parcial o el saldo completo. Sin saldo deudor se deshabilitan las
acciones. El importe elegido aparece como una fila «Saldo anterior», sin cantidad
ni descuento y con papelera para quitarlo. Los totales separan el pedido del
saldo anterior y muestran «Total a cobrar». Cambiar cliente elimina la selección;
actualizar el importe reemplaza la fila existente. En móvil, importe y acciones
se apilan sin desbordar. El importe se guarda en el pedido para el futuro remito
y no genera un nuevo débito, descuento ni movimiento de stock.
Esta variante no incluye controles de cobro. Al cambiar de cliente se selecciona
su lista asignada o `GENERAL` si no tiene una, y se descarta la selección manual
del cliente anterior. Se cargan todas las páginas de listas para poder mostrar
la asignada. Una lista asignada no disponible se señala y requiere seleccionar
una lista activa.
La fila de cliente, vendedor y lista incluye «Cargar pedido anterior» a la derecha;
en móvil se apila con ancho completo. El botón queda gris y deshabilitado sin
cliente, durante la consulta o si no hay pedido previo. Carga el último pedido
confirmado o entregado visible para el usuario, reemplazando los productos del
borrador y conservando cantidades. Usa la lista y precios actuales. La carga
inicial excluye descuentos; si el pedido anterior tiene descuentos, solo el
administrador recibe un diálogo para cargar sin descuentos o copiarlos. Cancelar
o Escape conserva el borrador, y el foco vuelve al botón. No copia cobros ni precios manuales históricos.
Los productos no disponibles bloquean la carga con un mensaje, y los errores
de consulta permiten reintentar.

La creación y edición de pedidos comparten `OrderForm`. En edición, cliente y
vendedor usan `CustomerSelect` y `SellerSelect` deshabilitados con los nombres
del pedido, sin cargar opciones para modificarlos. Se precargan productos,
cantidades, precios guardados, descuentos y saldo anterior. Los administradores
pueden agregar/quitar productos y editar el precio unitario en la tabla; cambiar
de lista descarta los precios anteriores y consulta los de la lista elegida.
La acción principal es «Guardar cambios» y actualiza el pedido existente, sin
crear otra venta ni modificar los cobros. Cancelar vuelve al detalle; si hubo
cambios, utiliza la confirmación compartida de descarte. Conserva las mismas
validaciones, tabla, totales y adaptación móvil de creación. Solo admite pedidos
y ventas confirmados, con una misma lista entre sus líneas.
En el listado, `.table-row-actions` agrupa «Ver detalle» y «Editar»; la segunda
acción solo aparece para administradores en pedidos confirmados. El enlace con
`?edit=true` abre el mismo formulario después de verificar permisos y estado
del detalle. Modificar la URL no concede acceso de edición. Las acciones usan
el wrap móvil compartido; cancelar o guardar elimina ese parámetro de la URL.

El campo «Producto» reutiliza `SearchableSelect` con ancho completo: escribir
filtra las opciones por nombre, sin distinguir mayúsculas ni acentos.
Las opciones muestran solo el nombre; excluye productos inactivos o
ya agregados al pedido. Se cargan todas las páginas del catálogo en bloques de
100 con `includeStock=false` para incluir coincidencias de páginas posteriores
sin consultar ni enviar saldos de inventario. La búsqueda es local,
sin consultas adicionales al escribir, y conserva la navegación con teclado.
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
Seleccionar, agregar o quitar un producto no genera otra consulta de precio.
No se recarga al cambiar el foco o reconectar, evitando esperas al volver a la
pantalla en celular. Los productos importados del pedido anterior se incluyen
en la carga. Cambiar cliente o lista cancela la consulta anterior; no se muestran
precios de otro contexto. La confirmación valida nuevamente los precios en el servidor.
El campo «Precio» se carga con el precio de
lista; solo el administrador puede editarlo y aplicar descuentos. La modificación
se envía como `unitPriceOverride` de la línea y solo afecta a ese pedido, sin
actualizar la lista. Cambiar cliente, lista o producto restablece el precio de
lista. Cantidad y «Descuento» utilizan cajas de `4ch + 22px` con `size=4`, sin
limitar el valor a cuatro caracteres; el descuento admite valores entre `0` y
`100`. Producto, precio, cantidad, descuento y acción se alinean en una misma
fila en escritorio. El selector de producto
recibe el espacio restante de la fila y puede reducir su ancho sin desbordar
la página. Los campos se apilan a `760px`.
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
compactos; solo el administrador puede editar descuentos. La acción final es un
botón `danger` de `38px` con una papelera de `16px` y nombre accesible «Quitar
{producto}». A `640px` o menos se utiliza el patrón de tarjetas de la tabla
compartida, con etiquetas visibles y la papelera al final.

Usar [`DataTable`](../../frontend/src/shared/components/DataTable.tsx).
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
  select `.select`, con labels visibles o nombres accesibles.
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
  El select Saldo pendiente alterna todas las ventas y solo
  ventas con saldo impago (excluye canceladas). Se combinan los filtros y se
  conservan en la URL; cada cambio vuelve a la primera página. A `640px` o menos
  cada filtro principal ocupa una fila completa mediante utilidades locales
  de Tailwind. Las fechas reutilizan `OrderDateFilter` y `OrderCalendar`, junto
  con `.orders-date-filters`, y forman dos columnas en móvil. Inician vacías,
  admiten límites abiertos y muestran errores para fechas o rangos inválidos.
  La carga fallida de las opciones permite reintentar sin bloquear el listado.
  Esta distribución pertenece a ventas y no cambia el patrón global.
- En pedidos, `.orders-date-filters` agrupa fecha mínima y máxima a la derecha
  de `.orders-toolbar`. Cada campo mide `140px`; a `640px` o menos, búsqueda y
  estado ocupan filas completas y las fechas se distribuyen en dos columnas
  iguales. Esta variante pertenece a pedidos. Los campos usan `dd/mm/aaaa`,
  labels visibles y errores asociados mediante `aria-describedby`.
  `OrderDateFilter` conserva el texto numérico y abre `OrderCalendar` en español al
  hacer clic en el campo o su botón de calendario, o con `Enter`/`Alt+ArrowDown`.
  `.orders-date-control` posiciona el botón sobre el campo. El calendario utiliza
  un `dialog` nativo anclado al campo y limitado al viewport, con meses y días
  en español, semana desde el lunes y acciones Hoy/Limpiar/Cerrar. Conserva el
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

[`SearchableSelect`](../../frontend/src/shared/components/SearchableSelect.tsx)
combina un campo editable y una lista desplegable con búsqueda local por nombre.
Recibe opciones `{ id, name }` y conserva el ID elegido, incluso con nombres
repetidos. La opción vacía siempre está disponible para quitar la selección;
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

Aplicado a ventas, filtro y formulario de clientes, creación de pedidos, pagos,
reglas de descuento y reasignación de clientes/pedidos. Las reglas de negocio
(clientes activos para descuentos, vendedores activos de destino, vendedor
heredado del cliente y selección obligatoria en pedidos/pagos) pertenecen a cada
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

No existe un componente compartido `Modal` o `Dialog`. Reutilizar la estructura
y las clases de catálogo para nuevos modales comunes; no inventar una API inexistente.

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

**Límite actual:** los modales están implementados por pantalla. El formulario
de catálogo tiene foco inicial y manejo de `Escape`, pero no implementa contención
ni restauración del foco. No asumir que `aria-modal` resuelve esos comportamientos.

### Confirmaciones reutilizables de cancelación y descarte

[`ConfirmationDialog`](../../frontend/src/shared/components/ConfirmationDialog.tsx)
centraliza las confirmaciones destructivas. Montarlo solo cuando se requiere
confirmación. Recibe `title`, `description`, `confirmLabel`, `onConfirm` y
`onCancel`; opcionalmente `cancelLabel`, `pendingLabel`, `pending` y `error`.
Usa `<dialog>` con `showModal()` en un portal a `document.body`: contiene el foco,
impide interacción con el fondo y queda sobre los formularios existentes. Enfoca
la acción segura al abrir y devuelve el foco al disparador si sigue montado.
Escape conserva los datos. Mientras `pending` está activo, ambas acciones y
Escape quedan bloqueados; los errores se muestran dentro con `role="alert"`.
No cierra al pulsar el fondo.

`.confirmation-dialog-*` conserva el patrón de catálogo: ancho `480px`, radio
`10px`, encabezado/cuerpo/pie separados, botones compartidos y confirmación
`danger`. Limita la altura con `100dvh` y scroll interno; a `760px` reduce padding
y márgenes, y a `640px` las acciones comparten el ancho y permiten wrap.

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

### Variantes específicas por pantalla

| Variante | Alcance y diferencia |
| --- | --- |
| `.modal-backdrop .panel` | Patrón básico de `520px`; no incluye por sí solo límite de altura ni las tres secciones |
| `.customer-modal` | Formulario de clientes de hasta `640px`, scroll interno y descarte mediante `ConfirmationDialog` |
| `.price-status-dialog-*` | Confirmación de estado de listas: `480px`, radio `12px`, resumen del cambio, cierre y capa `35` |
| `.price-lists-page .modal-backdrop` | Ajuste de pricing con capa `30` |

Mantener estas diferencias acotadas. Si se generaliza una variante, extraer la
estructura reutilizable y documentar su API antes de extenderla a otras features.

## Badges, feedback y estados

- [`Badge`](../../frontend/src/shared/components/Badge.tsx): `tone="soft"`
  por defecto, `strong` para énfasis y `muted` para estados secundarios.
  Píldora con padding `4px 8px` y texto `10px/700`.
- Éxito: `.success-text` con `role="status"`; error: `.error-text` con
  `role="alert"`; advertencia: `.warning-text`, borde cobre y descripción de la
  consecuencia. No comunicar un estado únicamente por color.
- [`EmptyState`](../../frontend/src/shared/components/EmptyState.tsx) recibe
  `title`, `description` y `action` opcional. Contenido centrado, mínimo `220px`.
- Distinguir carga, error recuperable, ausencia de datos y filtros sin resultados.
  Ofrecer reintento o limpieza de filtros cuando corresponda; mostrar acciones
  de creación solo si el usuario tiene permisos.
- El componente vacío también se usa hoy para carga/error: no trae spinner,
  reintento automático ni anuncios accesibles incorporados.

## Responsive y movimiento

| Ancho máximo | Comportamiento actual |
| --- | --- |
| `1050px` | Sidebar `210px`, contenido con padding `28px`, métricas en dos columnas |
| `760px` | Shell vertical, navegación horizontal, contenido `24px 16px`, header/formularios/layouts principales en una columna |
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
