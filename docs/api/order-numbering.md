# Numeración de clientes y pedidos

Cada cliente recibe un número estable de cuatro dígitos (`0001`–`9999`) al
crearse. No depende de su nombre, CUIT ni UUID y no cambia al editar sus datos.
El listado y detalle de clientes devuelven `number` como texto con ceros iniciales.

Los pedidos concatenan ese número y un correlativo de cinco dígitos por cliente
(`00001`–`99999`), sin prefijo ni separadores. El cuarto pedido del cliente `0001`
se identifica como `000100004`. Otro cliente inicia su propio correlativo en
`00001`. La fecha seleccionada no modifica el correlativo. Cancelar un pedido
no libera su número; editarlo conserva su número e ID.

La asignación del correlativo usa una actualización transaccional de la fila del
cliente en PostgreSQL. Pedidos concurrentes del mismo cliente obtienen números
distintos. Un fallo revierte el contador junto con el pedido. Repetir la misma
confirmación con su clave de idempotencia devuelve el pedido original sin
incrementar el contador. Al alcanzar los límites, la base rechaza la asignación
en lugar de truncar dígitos o reutilizar números.

La migración V36 asigna números a clientes existentes por fecha de alta e ID,
y correlativos a sus pedidos por fecha de creación e ID dentro de cada cliente,
incluyendo pedidos cancelados. Conserva IDs, líneas, ventas, pagos y saldos.
El número anterior se guarda en `legacy_order_number`; la consulta del pedido
por número acepta ese alias y devuelve el número actual. Si un alias coincide
con un número actual, este último tiene prioridad. Las rutas por UUID siguen
funcionando. La seed conserva sus alias `PED-…` para actualizar fechas sin
alterar los números ya asignados.

Los pedidos a proveedores mantienen su numeración `PRV-…` independiente.
