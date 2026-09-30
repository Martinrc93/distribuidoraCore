# ADR-003: Stack y organización del frontend

## Status

Accepted

## Context

El frontend será una SPA independiente para operar pedidos, ventas, clientes,
productos, inventario, pagos y administración. Debe separar correctamente
server state, estado de formularios, estado de URL y estado de interfaz.

## Decision

Se utilizará:

- React con TypeScript.
- Vite como herramienta de build.
- React Router para routing y layouts.
- TanStack Query para server state, cache, mutations e invalidación.
- React Hook Form para formularios.
- Zod para schemas y validación.
- Tailwind CSS y shadcn/ui para UI accesible y personalizable.
- `fetch` encapsulado para HTTP.
- Vitest y React Testing Library para tests unitarios y de componentes.
- Playwright para flujos end-to-end.

La organización será por feature:

```text
src/
├── app/
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
└── main.tsx
```

No se incorporará un store global inicialmente. El server state será manejado
por TanStack Query; el form state por React Hook Form; los filtros y la
paginación por la URL; y el estado visual será local o estará acotado a un
layout.

Zustand se evaluará únicamente si aparece estado de cliente transversal que no
pertenezca a ninguna de esas categorías.

## Alternatives considered

### Redux

Descartado por complejidad innecesaria para el alcance inicial.

### Axios

No se incorpora inicialmente porque `fetch` encapsulado cubre HTTP, headers,
errores, autenticación y correlation IDs sin una dependencia adicional.

### Estado global para entidades del backend

Descartado porque duplicaría server state y generaría problemas de cache,
invalidación y consistencia.

## Consequences

### Estado de implementación de UI (2026-09-30)

Tailwind CSS 4 está integrado mediante `@tailwindcss/vite`. La migración conserva
los componentes propios, sus clases semánticas y los valores originales utilizando
`@apply` en `frontend/src/styles.css`; Preflight se omite para preservar los estilos
base del navegador. shadcn/ui sigue siendo parte de la decisión original y no está
instalado. Para nuevas interfaces se debe consultar la
[guía de estilos](../development/ui-style-guide.md).

### Positivas

- Menos duplicación de estado.
- Features autónomas y fáciles de localizar.
- Formularios y validación tipados.
- Buen soporte para tablas paginadas y mutations.

### Negativas

- El equipo debe conocer correctamente los límites de TanStack Query.
- La UI deberá manejar explícitamente loading, vacío, error y paginación.
- Algunas pantallas complejas pueden requerir composición cuidadosa de queries.
