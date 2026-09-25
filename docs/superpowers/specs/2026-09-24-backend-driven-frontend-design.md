# Diseño: frontend integrado con las capacidades del backend

## Objetivo

Convertir las pantallas preparadas del frontend en flujos operativos que usen
los endpoints ya implementados, respeten los permisos y contratos reales del
backend y permitan identificar con claridad las funciones que no tienen soporte
de API.

## Alcance

Implementar, en módulos funcionales:

- Catálogo: productos sin precio general, marcas y categorías.
- Pricing: administración de listas y precios por producto/lista; edición de
  costos con precios requeridos para listas afectadas.
- Operación comercial: creación, consulta, detalle y edición administrativa de
  pedidos; ventas y snapshots.
- Inventario: historial de movimientos y ajustes manuales.
- Cuenta corriente: pagos específicos o FIFO, cobros durante la entrega,
  transferencias, devoluciones y deuda.
- Ciclo de venta: entrega, intentos fallidos, cancelación, documentos PDF,
  notificaciones y consulta del estado de cada solicitud.
- Administración: invitación/alta, bloqueo, desbloqueo y revocación de sesiones
  de usuarios; CRUD y reasignaciones de vendedores.
- Configuración del límite de crédito global y visualización de advertencias.
- Renovación/revocación de sesión según los endpoints de autenticación
  disponibles.

## Fuera de alcance por falta de soporte o por ser operación de despliegue

- Consulta de auditoría: no existe endpoint de lectura de eventos de auditoría.
- Administración de roles y permisos: no existe API de CRUD de roles/permisos.
- Multi-depósito: la funcionalidad sigue pendiente en backend.
- Corrección/reversión de un pago registrado: no existe endpoint de comando.
- Estado de cuenta detallado de cliente por deuda/saldo: no existe una consulta
  dedicada por `customerId`; el saldo agregado sí está disponible.
- Consulta de estado global de outbox: el backend no expone un endpoint
  operativo para el worker; solo se puede consultar el estado de solicitudes de
  notificación individuales.
- Configurar URLs/tokens de proveedores desde la aplicación: se configuran en
  el entorno y no se guardan en PostgreSQL. La UI podrá solicitar envíos y
  mostrar errores de proveedor no configurado.
- Programación/replicación externa de backups, primera ejecución remota de CI y
  validación de límites modulares: tareas operativas/de backend, no funciones de
  usuario final.
- Historial de costos, que fue retirado del alcance funcional.

## Dirección visual

La interfaz representa un libro de despacho para una distribuidora: compacta,
legible y enfocada en datos y acciones de trabajo, evitando paneles decorativos
repetidos.

- Fondo mineral: `#F3F5F2`.
- Tinta petróleo: `#193A43`.
- Verde depósito: `#34745D`.
- Cobre para atención/acciones secundarias: `#A96A2C`.
- Papel para superficies: `#FFFFFF`.
- Rojo operativo para errores: `#A33D35`.
- Tipografía de interfaz: Aptos/Segoe UI con stack del sistema; importes y
  cantidades usan cifras tabulares.

La estructura conserva navegación lateral y shell responsive. Cada módulo tiene
título y acción contextual, avisos relevantes, filtros y una tabla densa que se
convierte en tarjetas legibles en mobile. Los flujos transaccionales muestran
catálogo/formulario en el área principal y un resumen de importes persistente en
desktop, apilado debajo en mobile.

```text
┌ Navegación ─────┬ Título de módulo ─────────────── Acción ┐
│ Operación       ├ Resumen contextual / alertas             ┤
│ Catálogo        ├ Filtros y búsqueda                       ┤
│ Administración  ├ Libro de datos / tarjetas compactas     ┤
│                 └ Detalle o formulario cuando corresponda ┘
```

La paleta fría y los acentos verdes/cobre se eligieron para el contexto de
despacho y lectura de importes; evita el beige cálido, los gradientes y la
repetición de tarjetas como decoración.

## Arquitectura

- Extraer de `frontend/src/app/App.tsx` páginas y flujos a `frontend/src/features`
  por dominio, conservando React Router y el shell.
- Mantener el cliente HTTP autenticado compartido; extenderlo para respuestas
  JSON/204, errores de validación con metadata y refresh de sesión.
- Definir tipos TypeScript por contrato de API y transformar DTOs a modelos de
  presentación en los límites de cada módulo.
- Usar TanStack Query para lecturas, invalidación y actualización posterior a
  comandos. No replicar reglas de autoridad comercial para precio, stock,
  descuentos, pagos o límites de crédito.
- Leer claims del JWT solo para ocultar o mostrar acciones. El backend conserva
  la autoridad final y los `401`, `403`, `404` y `409` deben producir feedback
  accionable.
- Formularios controlados sin incorporar por ahora otra dependencia de forms.
- Confirmar bajas/cancelaciones, evitar envíos dobles y conservar la misma clave
  idempotente al reintentar una confirmación sin cambiar su contenido.

## Orden de implementación

1. Alinear shell, sesión, API client, tipos comunes, permisos y estilos.
2. Migrar producto a precios por lista y añadir marcas, categorías y gestión de
   listas.
3. Implementar pedido: selección de cliente/lista/productos, pagos, preview,
   confirmación idempotente, detalle y edición administrativa.
4. Completar inventario, ventas, pagos, cuenta corriente y devoluciones.
5. Completar entrega/cancelación, documentos y notificaciones.
6. Completar usuarios, vendedores, reasignaciones y configuración de crédito.
7. Actualizar checklist frontend por criterio verificado y dejar explícitas las
   tareas fuera de alcance.

## Estados y accesibilidad

Cada pantalla cubre carga, error de lectura, vacío específico y éxito/error de
mutación. Los formularios conservan valores ante errores de validación. Se
proveen labels, foco visible, mensajes asociados a campos, controles por teclado,
confirmaciones entendibles y diseño mobile.

## Verificación

- Tests Vitest para contratos del API client, validación y mutaciones por módulo.
- Tests de componentes para permisos, loading/empty/error/success, formularios,
  confirmaciones y render mobile.
- `npm test -- --run` y `npm run build` después de cada bloque funcional.
- Flujos de integración del backend se usan como fuente para payloads y
  expectativas; las funciones sin endpoint no se marcan como implementadas.

## Criterio de cierre

Cada criterio del checklist se marca completo solo cuando la pantalla consume el
endpoint real, maneja sus permisos y errores, actualiza las queries, funciona en
desktop y mobile y tiene pruebas de interacción adecuadas. Las ausencias de API
quedan listadas y no se simulan con datos locales.
