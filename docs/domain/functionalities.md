# Guía funcional para desarrolladores

## Propósito

Este documento describe el comportamiento funcional esperado del sistema de
gestión empresarial. Su objetivo es servir como referencia interna para quienes
desarrollen backend, frontend, integraciones y tests.

No reemplaza los ADR ni define detalles de implementación. Cuando exista una
decisión técnica, prevalecen los documentos de `docs/adr/`.

## Alcance actual

El sistema es una aplicación para una empresa por instancia. Incluye:

- Autenticación y gestión de usuarios.
- Roles y permisos.
- Perfiles de vendedores.
- Clientes y asignación comercial opcional.
- Productos, marcas, categorías y presentaciones.
- Listas de precios.
- Stock y movimientos.
- Pedidos y ventas.
- Pagos parciales y combinados.
- Cuenta corriente de clientes.
- Entregas e intentos de entrega.
- Documentos e impresión bajo demanda.
- Envío opcional por WhatsApp.
- Auditoría de operaciones.

No se implementan actualmente impuestos, facturación electrónica, proveedores,
compras, múltiples depósitos, reportes, dashboard, notificaciones generales ni
portal de clientes.

## Módulos funcionales

### Identity

Responsable de la identidad y el acceso al sistema:

- Crear usuarios.
- Activar usuarios mediante email.
- Cambiar y recuperar contraseñas.
- Bloquear usuarios después de tres intentos fallidos.
- Desbloquear usuarios.
- Inhabilitar usuarios mediante baja lógica.
- Gestionar roles y permisos.
- Emitir refresh tokens rotativos y revocables almacenados como hash SHA-256.
- Detectar reuso de refresh tokens revocados e invalidar sesiones preventivamente.
- Revocar sesiones.

Un usuario inhabilitado no puede iniciar sesión ni ser asignado como vendedor.
Sus referencias históricas permanecen válidas.

### Seller

Representa la capacidad comercial de un usuario.

- Un usuario puede tener como máximo un perfil de vendedor.
- Un vendedor no es un cliente.
- Un vendedor puede tener también permisos de administrador.
- El administrador crea y administra vendedores.
- El perfil de vendedor puede estar activo o inactivo.
- Un vendedor inactivo no puede recibir nuevas asignaciones.

La asignación de vendedor a un cliente es opcional. Cuando existe, puede
preseleccionarse durante la creación del pedido. La asignación histórica del
pedido se conserva al confirmarlo.

### Customer

Responsable de los clientes comerciales:

- Alta y modificación de clientes.
- Datos de contacto.
- Dirección de entrega.
- Lista de precios opcional.
- Vendedor asignado opcional.
- Consulta de cuenta corriente.
- Consulta de ventas, pagos y saldo pendiente.

No existe acceso directo del cliente al sistema. La información de deuda solo
está disponible para usuarios internos autorizados.

### Catalog

Responsable de productos y precios.

#### Producto

Para crear un producto se requiere:

- Marca.
- Presentación.
- Categoría.
- Costo mayor que cero.
- Al menos una lista de precios activa con precio.

La presentación es texto libre. El nombre visible se genera concatenando marca
y presentación:

```text
nombre = marca + " " + presentación
```

El producto es el artículo vendible y la unidad controlada por inventario.

La edición del costo valida atómicamente contra los precios de todas las listas
activas. Si el nuevo costo supera el precio de una o más listas activas, la
operación exige enviar nuevos precios mayores o iguales al costo para todas las
listas afectadas; de lo contrario, la edición de costo se procesa sin exigir
precios. El costo y los precios afectados se persisten en una sola transacción.
El backend rechaza el costo e informa las listas afectadas si faltan precios de
reemplazo.

#### Listas de precios

- Se crean diez listas iniciales: `Lista general` (`GENERAL`), `Lista 2` (`LISTA_2`) a `Lista 10` (`LISTA_10`).
- Las tres primeras quedan activas; de la 4 a la 10 quedan inactivas.
- El administrador puede cambiar sus nombres.
- El administrador puede agregar listas hasta un máximo total de diez.
- El máximo incluye listas activas e inactivas.
- Una lista puede modificarse.
- Una lista puede darse de baja.
- Una lista dada de baja puede reactivarse.
- Una lista dada de baja no se usa en nuevos pedidos.
- Los pedidos históricos conservan el precio aplicado.
- Un cliente puede tener una lista asignada, pero no es obligatorio.
- `GENERAL` (`Lista general`) es la lista predeterminada cuando el cliente no tiene una lista
  asignada; no se crea una cuarta lista automática.
- La lista puede cambiarse durante la creación del pedido.
- Si falta el precio en la lista activa seleccionada, se prueban las listas
  activas anteriores por identificador descendente.
- Los cambios de precio son inmediatos y no tienen vigencia futura.

No se guardan precios históricos de la lista como historial independiente; el
historial comercial se conserva mediante snapshots en pedidos y ventas.

### Inventory

Responsable del saldo y la trazabilidad del stock.

El stock no vive únicamente en el producto. Se mantienen:

- Saldo actual por producto.
- Movimientos inmutables.
- Referencia al origen del movimiento.
- Usuario y fecha de la operación.

Reglas:

- El saldo puede ser negativo.
- Las cantidades aceptan decimales.
- Las cantidades deben ser múltiplos de `0.5`.
- Solo el administrador puede realizar ajustes manuales.
- Todo ajuste requiere auditoría.
- No se editan ni eliminan movimientos históricos.
- La confirmación de una venta registra una salida.
- La cancelación de una venta no pagada registra una reversión.
- La edición de un pedido confirmado calcula un delta y registra movimientos
  compensatorios.

Tipos iniciales de movimiento:

```text
SALE
SALE_CANCELLATION
MANUAL_ENTRY
MANUAL_ADJUSTMENT
RETURN
```

La estructura debe permitir incorporar depósitos en el futuro, pero no se
implementa multi-depósito actualmente.

### Order

Responsable del pedido operativo.

El pedido no se guarda como borrador en backend. El flujo es:

```text
Formulario frontend
→ seleccionar cliente
→ cargar líneas
→ aplicar lista, precios y descuentos
→ confirmar
→ persistir pedido CONFIRMED
```

Antes de confirmar no existe un pedido persistido.

Al confirmar se ejecuta una transacción que debe:

1. Validar cliente y productos.
2. Resolver la lista de precios.
3. Aplicar overrides permitidos.
4. Calcular descuentos.
5. Validar cantidades.
6. Registrar la salida de stock.
7. Crear la venta.
8. Registrar pagos o deuda.
9. Guardar el pedido como `CONFIRMED`.
10. Registrar auditoría y eventos outbox.

La operación debe ser idempotente para no crear ventas duplicadas ante doble
click o reintento de red.

#### Estados

Los únicos estados de negocio son:

```text
CONFIRMED
DELIVERED
CANCELLED
```

Reglas:

- `CONFIRMED` es el estado inicial persistido.
- `CONFIRMED` puede pasar a `DELIVERED`.
- `CONFIRMED` puede pasar a `CANCELLED` solo si la venta no tiene pagos.
- `DELIVERED` es terminal.
- `CANCELLED` es terminal.
- Una venta entregada no puede revertirse.
- Una venta pagada no puede cancelarse.
- Solo el administrador puede cancelar.
- El administrador puede modificar cualquier pedido confirmado.
- El vendedor no puede modificar pedidos.

Una modificación que reduzca el nuevo total por debajo de lo ya pagado debe
rechazarse. No se generan saldos a favor ni pagos excedentes.

### Sale

Responsable del registro comercial generado al confirmar un pedido.

- Siempre nace desde un pedido confirmado.
- Tiene relación con el pedido de origen.
- Conserva snapshots de productos, precios, descuentos y totales.
- No se recalcula por cambios posteriores en listas de precios.
- Puede tener uno o varios pagos.
- Puede quedar parcialmente pagada.
- Una venta parcialmente pagada puede entregarse.
- Una venta pagada no puede cancelarse.

Los descuentos son acumulables y se calculan en este orden:

```text
precio de lista
→ override manual autorizado
→ descuento de línea
→ subtotal
→ descuento total
→ total final
```

El backend es la fuente de verdad del cálculo.

### Payment y cuenta corriente

Responsables de pagos y deuda de clientes.

Métodos iniciales:

```text
CASH
BANK_TRANSFER
CUSTOMER_ACCOUNT
```

Reglas:

- Se permiten pagos parciales.
- Se permiten pagos combinados.
- Vendedores y administradores pueden registrar pagos.
- No se permiten pagos mayores al saldo pendiente.
- No se permiten saldos a favor.
- Los pagos no se eliminan físicamente.
- Una corrección se realiza mediante una operación compensatoria auditada.
- La cuenta corriente pertenece al cliente.
- No existe portal de cliente.

En transferencias se pueden registrar opcionalmente:

- Número de transferencia.
- Nombre de la cuenta.

La deuda se representa mediante un ledger. Una venta a cuenta genera débito y
un pago genera crédito. El usuario puede aplicar un pago a una deuda específica
o dejar que el sistema utilice FIFO sobre el saldo más antiguo.

#### Límite de crédito

- Es global para todos los clientes.
- Se almacena en configuración de base de datos.
- Se modifica desde el apartado de configuración.
- No bloquea la creación ni confirmación del pedido.
- Muestra una advertencia cuando el saldo proyectado supera el límite.
- La advertencia queda auditada.

El saldo proyectado es:

```text
deuda actual + importe pendiente del nuevo pedido
```

### Delivery

La entrega es una acción sobre una venta confirmada.

- Vendedor y administrador pueden marcar `DELIVERED`.
- La fecha y hora se asignan automáticamente; se persisten en UTC y se
  presentan en la zona de negocio UTC-3.
- Se registra el usuario que ejecuta la acción.
- La dirección se toma de los datos del cliente.
- No se requiere firma.
- No se requiere comprobante de entrega.
- Al marcar como entregado se consulta el saldo pendiente de la venta.
- Si se cobra en el momento, se registra el monto y el medio `CASH` o
  `BANK_TRANSFER`.
- Si el medio es `BANK_TRANSFER`, se puede registrar opcionalmente el número de
  transferencia.
- Si el pago es parcial, el importe restante se registra en la cuenta corriente
  del cliente.
- Si no se cobra en el momento, el saldo pendiente se registra en la cuenta
  corriente del cliente.
- Se permiten múltiples intentos.
- Un intento no entregado requiere observación.
- Un intento no entregado no cambia el estado principal.
- El pedido permanece `CONFIRMED` y puede intentarse nuevamente.

Un intento debe conservar usuario, fecha, número de intento, resultado y
observación.

### Document

Responsable de la generación de documentos bajo demanda.

La confirmación de una venta no genera automáticamente un PDF.

Acciones disponibles:

- Descargar.
- Imprimir en A4.
- Imprimir ticket de 58 mm.
- Imprimir ticket de 88 mm.
- Enviar por WhatsApp.

Los documentos se generan desde un modelo inmutable de venta. Los templates no
deben contener reglas de stock, pagos ni precios.

Los archivos son temporales:

- Directorio no público.
- Nombre aleatorio.
- Retención recomendada de una hora.
- Limpieza programada.
- Eliminación después de descargar o enviar.
- Sin persistencia permanente en la primera fase.

Las plantillas visuales de A4 y tickets se definirán posteriormente.

### Notification

Responsable de proveedores externos de email y WhatsApp.

Email inicial recomendado: Brevo Free para activación, recuperación y alertas.

WhatsApp:

- Meta Cloud API de prueba o Twilio Sandbox para desarrollo.
- Producción mediante API oficial o BSP oficial.
- No se utilizarán automatizaciones de WhatsApp Web.
- El proveedor se encapsula detrás de un adapter.
- Un fallo externo no revierte la venta.

### Audit

Responsable de la trazabilidad de operaciones relevantes.

Debe auditar como mínimo:

- Alta, bloqueo, desbloqueo e inhabilitación de usuarios.
- Cambios de roles y permisos.
- Creación y modificación de vendedores.
- Cambios de precios y listas.
- Creación, confirmación, edición y cancelación de pedidos.
- Movimientos y ajustes de stock.
- Pagos, aplicaciones y correcciones.
- Cambios de límite de crédito.
- Intentos y entregas.
- Generación y envío de documentos.

La auditoría es visible para administradores y no se modifica ni elimina desde
la aplicación.

## Permisos funcionales

| Acción | Vendedor | Administrador |
|---|---:|---:|
| Ver clientes asignados | Sí | Sí |
| Ver clientes no asignados | No | Sí |
| Crear pedidos | Sí | Sí |
| Confirmar pedidos | Sí | Sí |
| Modificar pedidos | No | Sí |
| Modificar precios | No | Sí |
| Aplicar descuentos | No | Sí |
| Registrar pagos | Sí | Sí |
| Marcar entregado | Sí | Sí |
| Cancelar ventas | No | Sí |
| Ajustar stock | No | Sí |
| Administrar listas | No | Sí |
| Administrar usuarios | No | Sí |
| Ver auditoría | No | Sí |

El administrador puede ejecutar todas las operaciones, pero toda operación
crítica continúa siendo auditable.

## Reglas transversales para desarrolladores

- El frontend nunca es fuente de verdad para precios, descuentos, stock o pagos.
- El backend debe recalcular y validar valores comerciales.
- No se editan movimientos históricos.
- No se eliminan pagos, ventas ni pedidos históricos.
- Las correcciones se modelan como movimientos o eventos compensatorios.
- Las operaciones críticas deben ser idempotentes.
- Los cambios de estado deben validar transiciones permitidas.
- Las integraciones externas no deben participar de la transacción comercial.
- Los módulos no acceden a repositories internos de otros módulos.
- Toda fecha técnica se guarda en UTC y se presenta en la zona de negocio.
- Todas las colecciones de la API son paginadas.
- La paginación default es `page=0`, `size=20`, con máximo `100`.

## Funcionalidades futuras

Quedan fuera del alcance actual, pero no deben bloquearse arquitectónicamente:

- Impuestos y facturación electrónica ARCA.
- Reportes y dashboard.
- Notificaciones generales.
- Múltiples depósitos.
- Proveedores y compras.
- Medios de pago adicionales.
- Portal de clientes.
- Configurador visual de plantillas.
- Monitoreo avanzado con Prometheus, Grafana y Loki.
