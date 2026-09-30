# Estándares de desarrollo

## General

- Preferir implementaciones simples y explícitas.
- Mantener clases pequeñas y con una única responsabilidad.
- Evitar abstracciones sin un consumidor real.
- No introducir dependencias sin justificar el problema que resuelven.
- Nombrar conceptos de negocio con el mismo vocabulario usado en la
  documentación.

## Java

- Usar Java LTS.
- Preferir `final` cuando mejore la claridad.
- Usar `BigDecimal` para importes.
- Usar `Instant` para timestamps técnicos.
- No usar `double` para dinero.
- Preferir records para DTOs inmutables apropiados.
- No usar `@Data` en entidades JPA.
- No exponer entidades JPA desde controllers.
- Validar requests con Bean Validation.
- Validar invariantes de negocio en dominio/casos de uso.

## Spring

- Controllers delgados.
- Casos de uso explícitos.
- `@Transactional` en el límite de aplicación adecuado.
- No poner transacciones en adapters HTTP externos.
- Usar method security para permisos.
- No leer repositories de otro módulo.
- No usar `ddl-auto=update`.

## Persistencia

- Migraciones únicamente mediante Flyway.
- Índices justificados por consultas reales.
- Constraints para integridad estructural.
- Paginar siempre las colecciones.
- No borrar datos históricos.
- Usar locking explícito para balances de stock.

## Frontend

- Organizar por feature.
- Server state en TanStack Query.
- Form state en React Hook Form.
- Validación compartida conceptualmente con backend, sin confiar solo en Zod.
- No duplicar toda la API en Zustand u otro store.
- Componentes compartidos sin dependencias de features concretas.
- Estados de loading, error y vacío explícitos.

### Modales

- Usar un overlay que cubra la pantalla y un panel centrado, con ancho máximo y altura limitada al viewport; en mobile, reducir márgenes y permitir scroll dentro del panel.
- Separar el panel en encabezado, contenido y pie. El encabezado lleva un título claro y una explicación breve; el contenido organiza los campos en una columna; el pie separa las acciones con un borde y las alinea a la derecha en desktop.
- No reutilizar la grilla general de formularios para el contenido del modal. Usar clases específicas para evitar que los campos y botones terminen en la misma fila.
- Usar `role="dialog"`, `aria-modal="true"` y `aria-labelledby` apuntando al título visible. Al abrir, enfocar el primer campo o una acción segura en una confirmación; mantener el foco visible y permitir cerrar con `Escape` cuando no haya una operación en curso.
- Mostrar errores de guardado dentro del modal para que sigan visibles sobre el overlay. Deshabilitar acciones mientras se guarda y mostrar el estado de carga en el botón principal.
- Nombrar cada acción por su resultado. Usar el estilo destructivo solo para bajas o acciones irreversibles; ofrecer una salida clara como “Cancelar”.
- Si se agrega animación, respetar `prefers-reduced-motion`.
- Tomar `CatalogAdminPage` y las clases `.catalog-modal-*` como referencia visual para encabezado, campo, pie, foco y adaptación mobile.

## Errores y logs

- Usar códigos de error estables para el frontend.
- No exponer stack traces.
- No loguear passwords, tokens ni documentos completos.
- Incluir correlation ID en logs.
- Registrar auditoría de negocio separada de logs técnicos.

## Pull requests

Cada cambio debe indicar:

- Problema resuelto.
- Alcance.
- Tests ejecutados.
- Migraciones incluidas.
- Impacto en API o datos.
- Riesgos conocidos.
