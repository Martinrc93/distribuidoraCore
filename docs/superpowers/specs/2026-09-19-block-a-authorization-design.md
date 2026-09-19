# Diseño: Bloque A de autorización y administración de usuarios

## Alcance

Este bloque corrige autorización horizontal, administración de usuarios y
configuración segura. No incluye pricing, snapshots comerciales, saldos de
cancelación, locks, descuentos, CI, Testcontainers ni mejoras visuales del PDF.

El PDF conserva su contenido actual: no mostrará el estado del pedido. Sí debe
respetar el mismo ownership que el detalle del pedido.

## Roles y permisos

Inicialmente existen solo los roles `ADMIN` y `SELLER`.

- `ADMIN` recibe `ADMIN_ALL` y acceso global.
- `SELLER` conserva los permisos operativos de venta, pero cada operación está
  limitada a sus clientes y pedidos.
- La autorización por permiso se mantiene con `@PreAuthorize`.
- La autorización por recurso se aplica dentro de consultas y servicios; no se
  confía únicamente en el controller.
- Cuando un recurso existe pero pertenece a otro vendedor, la API responde
  `404 NOT_FOUND` para no revelar su existencia.

## Ownership de vendedor

Un componente de aplicación `CurrentUserAccess` obtiene el UUID autenticado,
determina si posee `ADMIN_ALL` y, para vendedores, resuelve su
`seller.seller_profiles.id`.

Un vendedor puede:

- listar y consultar únicamente clientes cuyo `customer.seller_id` coincide con
  su perfil;
- crear y confirmar pedidos únicamente para esos clientes;
- listar y consultar pedidos cuando `orders.seller_id` coincide con su perfil o,
  para datos anteriores sin snapshot, cuando el cliente continúa asignado al
  vendedor;
- consultar pagos, registrar intentos de entrega y descargar el PDF únicamente
  cuando tiene acceso al pedido asociado;
- consultar productos y precios necesarios para vender, sin recibir el costo.

Al confirmar un pedido se guarda `orders.seller_id` con el perfil del vendedor
autenticado. El administrador puede operar globalmente; si confirma para un
cliente con vendedor asignado, se conserva ese vendedor como snapshot.

Lecturas de usuarios, auditoría, costos, movimientos globales y dashboard
administrativo requieren `ADMIN_ALL` o un permiso administrativo equivalente.
Las mutaciones de clientes y productos quedan restringidas a `ADMIN_ALL`.

## Administración de usuarios

Se agrega `POST /api/users`, protegido por `USER_MANAGE` o `ADMIN_ALL`.

Request:

```json
{
  "email": "vendedor@empresa.local",
  "temporaryPassword": "contraseña temporal",
  "role": "SELLER",
  "displayName": "Nombre del vendedor"
}
```

Reglas:

- `role` admite únicamente `ADMIN` o `SELLER`.
- La contraseña temporal es obligatoria y se almacena con Argon2.
- `displayName` es obligatorio para `SELLER` y se ignora para `ADMIN`.
- En una sola transacción se crean `identity.users`, `identity.user_roles` y,
  para vendedores, `seller.seller_profiles`.
- Un fallo en cualquier escritura revierte el alta completa.
- Email duplicado devuelve `409 CONFLICT`.
- Datos inválidos o rol inexistente devuelven `400 INVALID_REQUEST`.
- El alta y sus fallos relevantes se auditan.

El bootstrap administrativo también debe resolver el rol `ADMIN`, asignarlo en
`identity.user_roles` y ser idempotente si el usuario o la relación ya existen.

## Configuración segura

La configuración se separa por intención:

- El perfil local/Compose puede utilizar credenciales demo conocidas, seed demo
  y puertos publicados, pero debe identificarse expresamente como desarrollo.
- Fuera del perfil local, `JWT_SECRET` no tiene valor por defecto y la aplicación
  falla al iniciar si está ausente o es inseguro.
- El seed demo queda desactivado por defecto fuera de desarrollo.
- La contraseña demo solo se acepta cuando el seed demo fue habilitado de forma
  explícita.
- La documentación debe advertir que `compose.yaml` no es configuración de
  producción.

## API y consultas

Las consultas existentes se dividen por audiencia:

- Admin: dashboard global, usuarios, costos, inventario y movimientos globales.
- Seller: clientes propios, productos sin costo, pedidos propios y pagos de esos
  pedidos.

El filtro se incorpora en SQL usando el perfil autenticado para evitar cargar un
conjunto global y filtrarlo en memoria. Paginación y conteos usan el mismo
predicado de ownership.

El detalle, lifecycle y documento llaman a una misma política de acceso para que
no diverjan. El PDF continúa mostrando venta, fecha, cliente, vendedor, líneas,
total, pagado y saldo; no agrega estado ni detalle de formas de pago.

## Errores

- `401`: autenticación ausente o inválida.
- `403`: el rol carece del permiso funcional completo.
- `404`: recurso inexistente o perteneciente a otro vendedor.
- `409`: email duplicado u otro conflicto de unicidad.
- `400`: payload, rol o contraseña temporal inválidos.

## Pruebas

- Matriz HTTP de `ADMIN`, `SELLER`, usuario sin permiso y no autenticado.
- Un vendedor no puede listar, consultar, entregar ni descargar PDF de pedidos
  ajenos.
- Un vendedor no puede confirmar pedidos para clientes ajenos.
- Pedidos propios aparecen en listados, detalle, pagos y PDF.
- Productos visibles al vendedor no exponen `cost`.
- Alta `ADMIN` crea usuario y rol sin perfil vendedor.
- Alta `SELLER` crea usuario, rol y perfil en una transacción.
- Email duplicado y rollback parcial no dejan filas huérfanas.
- Bootstrap admin asigna `ADMIN_ALL` de forma idempotente.
- La aplicación sin secreto JWT fuera de desarrollo no inicia.
- Los tests existentes de confirmación, lifecycle y documentos continúan
  pasando.
