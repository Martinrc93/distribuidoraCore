# Selección de vendedor en nuevo pedido — Diseño

## Objetivo

Reorganizar los campos iniciales del formulario de pedido y permitir que administradores asignen al pedido cualquiera de los vendedores existentes, tomando por defecto el vendedor del cliente.

## Reglas aprobadas

- En escritorio, los campos aparecen en una fila: Cliente ocupa la mitad izquierda; Vendedor y Lista de precios comparten la mitad derecha en partes iguales.
- Cliente sigue siendo el selector y mantiene su comportamiento actual.
- Vendedor aparece a la derecha de Cliente y a la izquierda de Lista de precios.
- El vendedor se precarga desde el vendedor asignado al cliente seleccionado.
- Solo usuarios `ADMIN_ALL` pueden cambiar el vendedor. Los demás ven el vendedor asociado al cliente como información de solo lectura; backend sigue asociando los pedidos de vendedores al perfil autenticado.
- Un vendedor elegido por el administrador queda guardado en `orders.orders.seller_id`. No cambia la asignación persistida del cliente.
- Al cambiar de cliente, se restablece el vendedor predeterminado del nuevo cliente y la lista de precios sigue resolviéndose/asignándose según el flujo actual.
- En pantallas angostas, los campos se apilan en una columna siguiendo los breakpoints existentes.

## Diseño

### Frontend

- Extender el tipo de cliente del alta de pedidos con `sellerId` y el nombre `seller`, ambos ya devueltos por la lectura de clientes.
- Para `ADMIN_ALL`, consultar `/api/sellers?page=0&size=100` y mostrar un selector con los vendedores retornados. Ese endpoint ya requiere `ADMIN_ALL` y entrega los perfiles existentes.
- Mostrar para los demás usuarios el nombre del vendedor del cliente sin control editable.
- Usar una grilla local de tres columnas con proporciones `2fr 1fr 1fr`; al breakpoint móvil colapsa a una columna.
- Incluir `sellerId` en el payload de confirmación para el administrador, invalidar el intento idempotente al cambiarlo y conservar el mismo payload en retries.

### Backend

- Agregar `sellerId` opcional a `ConfirmationRequest`/`ConfirmationCommand` y a la fingerprint de idempotencia.
- Para `ADMIN_ALL`, usar el seller enviado si existe; si no, mantener el seller asignado al cliente como fallback. Validar que el perfil elegido exista antes de efectuar movimientos de stock.
- Para no administradores, seguir usando exclusivamente el perfil vendedor del usuario autenticado y rechazar un `sellerId` que intente reemplazarlo.
- Persistir el resultado en `orders.orders.seller_id` durante la confirmación; ventas y lecturas relacionadas continúan obteniendo vendedor por la relación de pedido ya existente.
- No agregar columna ni relación nueva a la base.

## Errores y estados

- La carga de vendedores para administradores participa en los estados de carga/error de preparación del pedido y permite reintentar la lectura.
- Si se elige un `sellerId` inexistente, el backend rechaza la confirmación antes de tocar stock o crear pedido/venta.
- Los permisos no cambian: selección requiere `ADMIN_ALL`; los pedidos ordinarios siguen usando el vendedor autenticado.

## Verificación

- Frontend: pruebas para la fila Cliente/Vendedor/Lista, precarga al elegir cliente, selección de vendedor admin, modo de solo lectura para otros usuarios, que el payload incluya el seller escogido y que retries preserven payload/clave.
- Backend: pruebas de persistencia del seller elegido, fallback al seller del cliente, prevalencia del perfil autenticado para no administradores, rechazo de modificación no autorizada y validación de seller inexistente sin efectos laterales.
- Ejecutar `mvn test`, pruebas PostgreSQL opt-in sobre base descartable, `npm run test` y `npm run build`.
