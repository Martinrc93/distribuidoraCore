# Arquitectura frontend

Los diagramas completos de arquitectura, navegación, estado, permisos y flujos
funcionales están en [`frontend-diagrams.md`](./frontend-diagrams.md).

## Stack

```text
React + TypeScript + Vite
React Router
TanStack Query
React Hook Form
Zod
Tailwind CSS + shadcn/ui
fetch encapsulado
Vitest + React Testing Library
Playwright
```

## Estructura

```text
src/
├── app/
│   ├── config/
│   ├── layouts/
│   ├── providers/
│   └── router/
├── features/
│   ├── auth/
│   ├── users/
│   ├── sellers/
│   ├── customers/
│   ├── catalog/
│   ├── inventory/
│   ├── orders/
│   ├── sales/
│   └── payments/
├── shared/
│   ├── api/
│   ├── components/
│   ├── forms/
│   ├── permissions/
│   ├── tables/
│   ├── types/
│   └── utils/
├── main.tsx
└── styles/
```

Cada feature puede contener `api`, `components`, `hooks`, `pages`, `schemas`,
`types` y `queries`. Los componentes compartidos no deben importar detalles
internos de una feature.

## Tipos de estado

| Estado | Herramienta | Ejemplos |
|---|---|---|
| Server state | TanStack Query | productos, stock, pedidos, deuda |
| Form state | React Hook Form | alta de cliente, pedido, pago |
| Validación | Zod | requests, formularios y respuestas seleccionadas |
| URL state | React Router | página, filtros, orden, búsqueda |
| UI state | Estado local/Context acotado | sidebar, modal, preferencias |
| Auth state | Context acotado + cliente HTTP | usuario actual y sesión |

No se copiarán todas las entidades del backend a un store global. Las
mutaciones invalidan o actualizan las query keys afectadas.

## Pedido sin borrador persistido

La pantalla de creación mantiene el pedido localmente hasta confirmar. El
backend no recibe una operación de creación preliminar. La confirmación envía
un comando completo y recibe el pedido confirmado, la venta y el resultado de
pago.

La pantalla debe advertir antes de abandonar si existen cambios no guardados.
El autosave persistente queda fuera del alcance inicial.

## Permisos en la interfaz

El frontend puede ocultar acciones que el usuario no puede ejecutar, pero nunca
reemplaza la autorización del backend. Las acciones se representan con
permisos, no con comparaciones de nombres de roles.

## Tablas y paginación

Todas las colecciones se muestran paginadas:

```text
page = 0
size = 20
maxSize = 100
```

Filtros, orden y página deben estar en la URL cuando sean relevantes para que
la vista pueda compartirse y recuperarse.

## UX obligatoria

Las pantallas de consulta y mutación deben contemplar:

- Carga.
- Estado vacío.
- Error recuperable.
- Error de autorización.
- Confirmación de operaciones destructivas o irreversibles.
- Feedback de mutaciones.
- Reintento cuando corresponda.
- Diseño usable en desktop y mobile.
