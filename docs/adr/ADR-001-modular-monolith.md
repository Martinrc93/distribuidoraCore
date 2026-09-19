# ADR-001: Monolito modular

## Status

Accepted

## Context

El sistema necesita cubrir identidad, clientes, catálogo, inventario, pedidos,
ventas, pagos, documentos, notificaciones y auditoría. Inicialmente será una
instancia para una empresa, con un equipo y una base operativa acotada.

Una arquitectura por carpetas globales facilitaría el comienzo, pero permitiría
que los módulos accedan directamente a entidades y repositories ajenos. Los
microservicios agregarían complejidad operativa antes de existir una necesidad
de escalado o despliegue independiente.

## Decision

Se utilizará un monolito modular en Spring Boot, organizado por módulos de
negocio:

```text
identity
seller
customer
catalog
inventory
order
sale
payment
document
notification
audit
```

Cada módulo tendrá una API interna explícita y podrá organizarse en `api`,
`application`, `domain` e `infrastructure` cuando esa separación aporte valor.

Un módulo no podrá acceder directamente a repositories, entidades JPA ni
detalles internos de otro módulo. La comunicación será mediante facades,
casos de uso, comandos y eventos.

## Alternatives considered

### Monolito tradicional por capas globales

Descartado porque debilita los límites de negocio y favorece dependencias
cruzadas entre controllers, services, repositories y entidades.

### Microservicios

Descartados inicialmente por el costo de despliegue, observabilidad,
consistencia distribuida y operación. Podrán evaluarse si un módulo requiere
escalado, despliegue o autonomía operativa independiente.

### Monolito modular

Seleccionado porque mantiene transacciones locales y simplicidad operacional,
pero permite evolucionar los límites del dominio y extraer módulos en el
futuro si existe una razón concreta.

## Consequences

### Positivas

- Un único despliegue y una única base transaccional inicial.
- Transacciones locales para confirmar pedidos, ventas y stock.
- Límites de negocio explícitos.
- Testing e instalación más simples que en microservicios.
- Posibilidad de extraer módulos posteriormente.

### Negativas

- Todos los módulos comparten el ciclo de despliegue.
- Los límites deben protegerse con disciplina, convenciones y tests arquitectónicos.
- Un error grave del proceso puede afectar a toda la aplicación.

## Guardrails

- Tests ArchUnit o Spring Modulith para verificar dependencias.
- APIs internas pequeñas y documentadas.
- Entidades JPA no compartidas entre módulos.
- Eventos para efectos secundarios, no para ocultar invariantes críticas.
