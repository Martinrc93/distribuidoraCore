# Arquitectura frontend

Los diagramas completos de arquitectura, navegación, estado, permisos y flujos
funcionales están en [`frontend-diagrams.md`](./frontend-diagrams.md).

## Stack y estructura

La SPA usa React, TypeScript, Vite, React Router y TanStack Query. El cliente
HTTP compartido agrega credenciales, rota el refresh token y traduce errores
de la API. El frontend usa Tailwind CSS 4 y componentes propios. La
[guía de estilos](../development/ui-style-guide.md) documenta los patrones.

| Ruta | Responsabilidad |
| --- | --- |
| `src/app` | Rutas, shell y límites de carga y error |
| `src/features` | Pantallas y lógica de los dominios comerciales |
| `src/shared/api` | Cliente HTTP, paginación, claves e invalidación de caché |
| `src/shared/auth` | Lectura de permisos y datos de la sesión |
| `src/shared/components` | Controles reutilizables |
| `src/styles.css` | Tokens, estilos base, componentes y variantes responsive |
| `src/test` | Pruebas de navegación y de flujos de negocio |

## Tipos de estado

| Estado | Herramienta | Ejemplos |
| --- | --- | --- |
| Datos del servidor | TanStack Query | Productos, stock, pedidos, deuda |
| Formularios | Estado local y validación de la aplicación | Alta de cliente, pedido, pago |
| URL | React Router | Página, filtros y búsqueda |
| Interfaz | Estado local, contexto o almacenamiento del navegador | Navegación, diálogos y preferencias |
| Sesión | Cliente HTTP, almacenamiento de sesión y suscripción reactiva | Usuario actual, permisos y credenciales |

Las páginas comerciales se cargan bajo demanda. Las mutaciones invalidan las
consultas afectadas. El cierre o vencimiento de la sesión cancela consultas,
limpia la caché y redirige al login.

El navegador que admite Web Locks y BroadcastChannel comparte las rotaciones
entre pestañas con la misma sesión. Las credenciales se guardan en
`sessionStorage` con una clave por instancia.

## Borrador de pedido

El formulario permite guardar un borrador en el navegador, asociado al
usuario y a la instancia. El backend recibe el pedido al confirmarlo. El
comando confirmado devuelve pedido, venta y resultado de cobro.

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
