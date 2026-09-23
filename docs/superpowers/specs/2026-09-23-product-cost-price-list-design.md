# Diseño: costo de producto y precios por lista

## Alcance

El producto conservará únicamente su costo. No tendrá un precio general ni
historial de costos. Todos los precios comerciales se almacenarán por producto
y lista en `catalog.product_prices`.

Este cambio cubre el alta y edición de productos, la consulta de productos, la
edición de precios por lista y las pantallas y diagramas relacionados. No
modifica la resolución de precios aplicable a pedidos salvo para quitar la
dependencia del precio general del producto.

## Modelo de datos

- `catalog.products` mantiene `cost` y elimina `price`.
- `catalog.product_prices` es la única fuente de precios de venta.
- Cada precio pertenece a una combinación única de `price_list_id` y
  `product_id`.
- No se crea una tabla ni evento de historial de costos.
- Las listas activas son las que participan en la validación del costo.

## Alta de producto

El alta recibe los datos descriptivos, el costo y los precios iniciales de las
listas en el mismo payload. El producto no recibe un campo `price`
independiente.

La validación conserva las reglas actuales de costo no negativo y producto
válido. Cada lista que se cree para el producto debe tener un precio mayor o
igual al costo.

## Edición de producto

La edición acepta modificar únicamente el costo cuando no se infringe ninguna
lista activa.

El servicio consulta los precios vigentes del producto en todas las listas
activas antes de guardar:

1. Si el nuevo costo es menor o igual a todos los precios vigentes, actualiza
   solo el costo.
2. Si el nuevo costo supera uno o más precios, rechaza el payload si no incluye
   un nuevo precio para cada lista afectada.
3. Cada nuevo precio de una lista afectada debe ser mayor o igual al nuevo
   costo.
4. Las listas no afectadas pueden conservar sus precios y no son obligatorias
   en el payload.
5. Cuando todas las validaciones pasan, costo y precios se actualizan dentro de
   una única transacción.

La API debe informar los códigos o identificadores de las listas afectadas
cuando falten precios, para que la interfaz pueda mostrar los campos que deben
completarse.

## API y respuestas

- `ProductInput` deja de incluir `price`.
- Las respuestas de producto dejan de incluir `price` y no muestran la etiqueta
  “precio general”.
- El payload de edición incorpora `prices` únicamente cuando son necesarios para
  resolver listas afectadas. Cada elemento identifica `priceListId` y `price`.
- Un costo inválido continúa devolviendo `400 INVALID_REQUEST`.
- Un costo que exige precios adicionales sin recibirlos devuelve `400` con las
  listas afectadas.
- Un precio nuevo inválido o menor al costo devuelve `400`.
- No se cambia el comportamiento de autorización: las mutaciones de productos
  y precios siguen restringidas al administrador.

## Interfaz

La pantalla de productos muestra el costo, pero no una columna de precio
general. Al editar el costo:

- si no se superan precios de listas, permite guardar solo el costo;
- si se superan precios, muestra las listas afectadas y solicita sus nuevos
  precios;
- no solicita modificar listas cuyos precios siguen siendo válidos.

La administración de listas continúa permitiendo editar precios de forma
independiente cuando no se está cambiando el costo del producto.

## Flujo transaccional

La operación de edición valida el producto, consulta listas y precios, valida
el payload completo y luego ejecuta las escrituras. Cualquier error revierte el
costo y todos los precios enviados; no debe quedar un costo nuevo con precios
incompatibles.

## Pruebas

- Crear un producto sin campo `price`.
- Consultar un producto sin campo `price`.
- Editar únicamente el costo sin superar ninguna lista.
- Rechazar un costo que supera una lista sin enviar su nuevo precio.
- Rechazar un nuevo precio afectado que siga por debajo del costo.
- Actualizar costo y varias listas afectadas en una sola operación.
- Verificar que las listas no afectadas no sean obligatorias.
- Verificar rollback cuando falla una actualización de precio.
- Verificar que la interfaz no muestra “precio general”.
- Mantener las pruebas existentes de edición independiente de precios por lista.
