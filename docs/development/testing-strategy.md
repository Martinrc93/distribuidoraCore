# Estrategia de testing

## Objetivo

Probar comportamiento y reglas de negocio, no detalles accidentales de
implementación.

## Backend

### Unit tests

Usar JUnit 5 y Mockito para:

- Estados de pedidos y ventas.
- Cálculo de precios y descuentos acumulables.
- Aplicación de pagos.
- Límite de crédito y advertencias.
- Reglas de stock negativo y múltiplos de `0.5`.
- Permisos y políticas de autorización.
- Tokens de activación y expiración.

### Application tests

Probar casos de uso completos con dobles controlados:

- Confirmar pedido.
- Editar pedido confirmado.
- Cancelar venta no pagada.
- Registrar pagos parciales y combinados.
- Marcar entrega.
- Registrar intento no entregado.

### Repository tests

Usar Testcontainers con PostgreSQL real para:

- Migraciones Flyway.
- Constraints e índices relevantes.
- Paginación y filtros.
- Locking del balance de stock.
- Ledger de cuenta corriente.
- Idempotency keys.

### API tests

Probar:

- Códigos HTTP.
- Formato de errores.
- Autenticación y permisos.
- Validaciones.
- Paginación.
- CORS.
- Idempotencia.

### Architecture tests

Usar ArchUnit o Spring Modulith para verificar que:

- Un módulo no acceda a repositories internos de otro.
- Controllers no accedan directamente a persistencia.
- El dominio no dependa de web.
- Los adapters externos no entren en el dominio.

## Frontend

- Vitest para funciones puras.
- React Testing Library para comportamiento de componentes.
- MSW o equivalente para mocks HTTP.
- Playwright para flujos críticos.

Flujos E2E iniciales:

- Activación de usuario.
- Login y bloqueo.
- Crear y confirmar pedido.
- Confirmación con stock negativo.
- Pago parcial.
- Pago combinado.
- Advertencia de límite de crédito.
- Entrega e intento no entregado.
- Cancelación de venta no pagada.
- Descarga e impresión de documento.

## Qué no testear de forma excesiva

- Getters y setters triviales.
- Código generado por MapStruct.
- Configuración estándar de Spring sin reglas propias.
- Snapshots visuales masivos sin comportamiento asociado.

## Criterio de aceptación

Un cambio no se considera completo hasta tener tests adecuados para reglas
modificadas, migraciones probadas y verificación de regresión relevante.
