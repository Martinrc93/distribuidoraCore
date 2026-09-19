# Diseño: documento A4 de venta bajo demanda

## Alcance

Implementar la generación bajo demanda de un PDF A4 para una venta existente.
WhatsApp, email, tickets térmicos, editor de plantillas y almacenamiento
permanente quedan fuera de este bloque.

## Contrato HTTP

```text
GET /api/orders/{orderId}/documents/a4
Accept: application/pdf
-> 200 application/pdf
Content-Disposition: attachment; filename="venta-<numero>.pdf"
```

El endpoint requiere `ORDER_CREATE` o `ADMIN_ALL`, igual que la consulta
operativa de pedidos. Solo se genera el documento cuando existe una venta
confirmada, entregada o cancelada asociada al pedido.

Errores:

- `401` sin autenticación válida.
- `403 FORBIDDEN` sin permiso suficiente.
- `404 NOT_FOUND` si no existe el pedido o la venta asociada.
- `409 CONFLICT` si el pedido todavía no tiene una venta confirmada.
- `500` únicamente para un fallo inesperado del renderer; no se debe modificar
  la venta ni sus saldos.

## Modelo del documento

El renderer recibe un `SaleDocumentModel` construido desde snapshots persistidos,
no desde precios actuales ni desde servicios de cálculo. Incluye:

- número y fecha de la venta;
- datos del cliente y vendedor disponibles en la venta;
- líneas con descripción, cantidad, precio unitario, descuento y subtotal;
- total de la venta;
- pagos registrados;
- saldo pendiente, calculado como `total - pagos`.

Los importes se formatean con escala monetaria y locale estable. El modelo es
inmutable y el renderer no accede a la base de datos.

## Arquitectura

- `document.application.SaleDocumentService` obtiene y bloquea solo las filas
  necesarias para construir el snapshot de lectura.
- `document.rendering.SaleDocumentModel` representa el contenido independiente
  del formato.
- `document.rendering.OpenPdfA4Renderer` genera el PDF con OpenPDF 3.0.5,
  `Document(PageSize.A4)`, tablas y fuentes estándar embebibles.
- `document.api.DocumentController` devuelve bytes PDF con `Content-Type`,
  `Content-Length` y nombre seguro.

La generación es de solo lectura, transaccional únicamente para obtener un
snapshot consistente y no persiste el archivo. La respuesta se construye en
memoria; no se crea un archivo público ni se guarda un BLOB.

## Comportamiento del saldo

Una venta de $100.000 con $40.000 pagados muestra total $100.000, pagado
$40.000 y saldo pendiente $60.000. Una venta totalmente paga muestra saldo
cero. El saldo no altera el balance del cliente ni crea movimientos.

## Pruebas

- renderer: PDF no vacío, encabezado PDF válido, A4, cliente, líneas, totales y
  saldo presentes;
- service: usa snapshots de venta, no precios actuales, y rechaza pedidos sin
  venta;
- controller: permisos, `200`, `application/pdf`, filename y errores 404/409;
- regresión: generar el PDF no crea movimientos, pagos, ledger ni cambios de
  estado;
- build backend completo y smoke HTTP con una venta real.
