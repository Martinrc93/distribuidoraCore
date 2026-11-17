# Fase 4.5 — cierre y continuidad

Actualizado el 2026-11-16. La Fase 4.4 quedó commiteada en `aefd9ab`:
`feat(orders): collect order and previous debt payments at confirmation`.
La Fase 4.5 quedó commiteada en `3564e74`:
`feat(orders): separate preparation and administrative review`.
La continuidad de 5.2 está en [su handoff](2026-11-16-fase-5-2-handoff.md).

## Decisiones confirmadas por Mateo

- El stock se descuenta al preparar. Entregar desde pendiente prepara y descuenta
  dentro de la misma transacción. Un intento fallido no prepara.
- Los administradores editan confirmados pendientes sin mover stock y preparados
  con deltas. Cancelar revierte el efecto neto; reactivar restaura deuda y deja
  pendiente de preparación.
- Preparar tiene permiso propio permanente `ORDER_PREPARE`, asignado inicialmente
  a ADMIN y SELLER. No reutilizar un permiso ajeno como solución temporal.
- Los vendedores pueden crear, preparar, cobrar y entregar durante el día.
  Los pedidos creados por ellos conservan el distintivo hasta la revisión manual
  del administrador al cierre del día. Esa revisión no bloquea la operación.
- Revisar guarda fecha, administrador y auditoría. No modifica origen, stock,
  importes ni estado comercial. Puede hacerse después de entregar o cancelar.
- Resumen administrativo muestra hasta cinco pendientes de todas las fechas y el
  total, con enlace al listado completo. Pedidos permite filtrarlos y revisarlos.

## Implementación

- V42 agrega preparación PENDING/PREPARED, fecha/usuario, equivalencia histórica y
  fecha/usuario de revisión. No se agregó un estado de despacho innecesario.
- Históricos no cancelados: PREPARED y legacy_fulfillment=true, sin movimientos
  nuevos ni fecha/operador inventados. Cancelados: PENDING.
- OrderFulfillmentService y OrderFulfillmentController coordinan preparación y
  revisión; el acceso del vendedor conserva el alcance por pedido.
- POST /api/orders/{id}/prepare y POST /api/orders/{id}/review devuelven 204.
  La revisión es solo administrativa; ORDER_REVIEW se asigna solo al rol ADMIN.
- OrderOperationalActions y OrderReviewBadge se comparten entre lista, detalle
  y PendingOrderReviews en Resumen. Nuevos filtros fulfillment y review en la URL.
- Contrato completo: [order-fulfillment.md](../../api/order-fulfillment.md).
  ADR-006/007, funcionalidades, proyecciones y guía UI están actualizados.

## Verificación ejecutada

- Backend: 522 pruebas, cero fallos/errores, con PostgreSQL 16.4 desechable.
  Incluye migración V41→V42 en una base separada, rollback, permisos, alcance del
  vendedor y preparación concurrente con edición.
- Se excluyeron siete clases que requieren Docker: DemoDataSeederPostgresTest,
  SingleInventoryMigrationIntegrationTest, DashboardAnalyticsPostgresTest,
  SupplierOrderPostgresTest, CustomerOrderNumberPostgresTest,
  InvitationOutboxPostgresTest y CustomerNumberCapacityPostgresTest.
- También se excluyeron ProductDescriptionPostgresTest y ProductSkuRemovalPostgresTest:
  sus escenarios de migración necesitan bases vacías separadas.
- Frontend: 337 pruebas (42 archivos) y npm run build correctos. El build incluye
  TypeScript. Salidas generadas fuera del repositorio.
- Navegador: cuatro E2E correctos con Brave, a 320, 390 y 1280 px. Comprueban
  preparación, revisión, entrega sin aprobación previa, Escape, foco y ausencia
  de desbordamiento horizontal. Configuración persistente:
  frontend/playwright.order-fulfillment.config.ts.
- PostgreSQL desechable detenido al terminar. No se migró una base real.

## Entorno y reglas para continuar

- Rama support. Preservar el ruido generado en backend/target, frontend/dist y
  frontend/node_modules y los wrappers mvn.cmd sin seguimiento. No incluirlos en commits.
- Commit solo por solicitud explícita de Mateo. Conventional commits en inglés,
  sin Co-Authored-By ni atribución de IA. Nunca push sin autorización.
- Antes de UI, leer docs/development/ui-style-guide.md y los selectores afectados.
- Maven está en C:/Users/Mateo/.maven/apache-maven-3.9.9/bin/mvn.cmd. PostgreSQL:
  C:/Users/Mateo/.pgsql/pgsql/bin. Usar salida de build aislada mediante
  -Disolated.build.dir y un log PostgreSQL fuera del directorio de datos.
- Para pruebas con Java 26 se utilizó el agente explícito de Mockito 5.17.0 y
  -Dnet.bytebuddy.experimental=true, sin modificar dependencias del proyecto.
- La variable de usuario de PostgreSQL es POSTGRES_TEST_USERNAME.

## Pendientes del plan

Fase 4 completa. Fase 5.1 parcial: permisos propios de preparación/revisión listos;
la pantalla de roles (5.2) fue retomada e implementada después de este cierre.
Roles personalizados (5.3) también fueron implementados después de este cierre,
limitados a datos asignados; ver `2026-11-16-fase-5-3-handoff.md`. El desacoplamiento
por área (5.4) sigue pendiente.
No se presentó la Fase 5 completa como implementada.
Siguen pendientes comisiones (requieren decisiones de base de cálculo),
revendedores (requieren diseño/roles) y reportes de Fase 7.
