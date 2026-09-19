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
