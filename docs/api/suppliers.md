# Proveedores

El catálogo de proveedores permite listar, buscar, crear y editar datos de
contacto. Todos los endpoints requieren autenticación y `ADMIN_ALL`.
La pantalla está disponible en Catálogo → Proveedores (`/suppliers`).

| Campo | Requerido | Límite |
| --- | --- | --- |
| `name` | Sí | 200 caracteres |
| `phone` | No | 80 caracteres |
| `email` | No; validar formato cuando se informa | 200 caracteres |
| `address` | No | 500 caracteres |

Los textos se recortan y los campos opcionales vacíos se guardan como `null`.
Los nombres repetidos están permitidos; cada proveedor se identifica por UUID.

- `GET /api/suppliers?page=0&size=20&search=`: devuelve `content`, `page`,
  `size`, `totalElements` y `totalPages`. El tamaño admite entre 1 y 100.
  La búsqueda es por nombre, sin distinguir mayúsculas; `%` y `_` son literales.
  El orden es por nombre e ID.
- `POST /api/suppliers`: acepta los cuatro campos y devuelve `201` con `{ "id": "UUID" }`.
- `PUT /api/suppliers/{id}`: reemplaza los datos de contacto y devuelve `204`.
  Omitir o vaciar un campo opcional lo limpia. Un ID inexistente devuelve `404`.

La migración `V33__create_suppliers.sql` crea el schema `supplier` y la tabla
`supplier.suppliers`, con timestamps de creación/actualización. Las escrituras
registran `SUPPLIER_CREATE` y `SUPPLIER_UPDATE` en auditoría dentro de la transacción.
Las compras y recepciones de mercadería siguen pendientes.

## Verificación de interfaz

Desde `frontend`, `npx playwright test --config playwright.suppliers.config.ts`
verifica alta, edición, opcionales, descarte, teclado y contenido largo a 320,
640, 760 y 1280 px mediante API simulada, sin requerir backend o base de datos.
