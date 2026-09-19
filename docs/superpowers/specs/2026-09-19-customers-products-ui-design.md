# Diseño: UI operativa de clientes y productos

## Objetivo

Completar la UI real de clientes y productos usando los endpoints existentes,
respetando permisos del backend y manteniendo el shell actual de React sin
agregar dependencias de formularios.

## Alcance

Incluye:

- Asignar vendedor y lista de precios desde el formulario de clientes.
- Crear y editar clientes con feedback de éxito y errores accionables.
- Editar productos y crear productos con validación local de importes.
- Desactivar y reactivar clientes y productos con confirmación explícita.
- Ocultar acciones que el usuario no puede ejecutar según permisos del JWT.
- Mostrar estados de carga, vacío, error y éxito en las mutaciones.
- Agregar tests del cliente HTTP y de los flujos principales.
- Actualizar el checklist frontend al verificar cada criterio.

No incluye:

- Pantalla completa de listas de precios.
- Ajustes de inventario.
- Nuevo pedido y confirmación comercial.
- React Hook Form, Zod o una reescritura general del shell.

## Arquitectura

Se mantienen las rutas y el `AppShell` existentes. `CustomersPage` y
`ProductsPage` se extraen a componentes de módulo para reducir el tamaño de
`App.tsx` sin cambiar la navegación pública. Los componentes reciben el mismo
cliente HTTP y usan TanStack Query para invalidar el listado después de cada
mutación.

Los formularios seguirán siendo controlados. Los payloads se construyen
explícitamente y convierten importes a número antes de llamar al backend. La
validación local cubre campos obligatorios y valores no negativos; el backend
sigue siendo la autoridad para unicidad y reglas de negocio.

## Permisos

El frontend decodificará únicamente los claims no sensibles del JWT para
obtener sus authorities. No se usará ese valor para autorizar requests: cada
acción seguirá dependiendo del backend y manejará `403` como error visible.

- `ADMIN_ALL`: puede editar, desactivar y reactivar clientes/productos.
- `CUSTOMER_WRITE`: puede crear clientes cuando el backend lo permita.
- Un vendedor no verá acciones administrativas que no tenga permitidas.

Si no existe un claim legible o el token es inválido, se ocultan acciones
opcionales y se mantiene disponible la lectura autorizada.

## Estados y errores

- `isLoading`: panel de carga existente.
- Lista vacía: mensaje específico por módulo.
- Error de lectura: mensaje del cliente API.
- Mutación exitosa: mensaje temporal y cierre del formulario.
- `400`: datos inválidos, se conserva el formulario.
- `403`: falta de permiso, se muestra mensaje accionable.
- `404`: registro no encontrado, se invalida el listado.
- `409`: SKU o identificación fiscal duplicados, se muestra conflicto.
- Durante una mutación se deshabilitan controles para prevenir doble envío.

## Testing

- Cliente HTTP: `apiPost`, `apiPut`, `apiPatch`, errores HTTP y respuestas 204.
- Clientes: crear, editar, asignar, cambiar estado y mostrar errores.
- Productos: crear, editar, cambiar estado y rechazar importes negativos.
- Permisos: acciones administrativas ausentes para un vendedor.
- Build TypeScript y tests Vitest existentes sin regresiones.

## Criterio de cierre

El bloque queda completo cuando clientes y productos usan endpoints reales para
las acciones incluidas, invalidan sus queries, muestran estados de carga/vacío/
error/éxito, respetan permisos del backend, funcionan en desktop y mobile,
tienen tests automatizados y el checklist frontend refleja únicamente lo
verificado.
