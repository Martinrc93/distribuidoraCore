# Arquitectura backend

## Stack

- Java LTS.
- Spring Boot.
- Spring Web.
- Spring Security.
- Spring Data JPA e Hibernate.
- PostgreSQL.
- Flyway.
- Maven.
- Bean Validation.
- JUnit 5, Mockito y Testcontainers.
- Spring Boot Actuator.
- OpenAPI.

## Estructura de paquetes

```text
com.company.erp/
├── ErpApplication.java
├── shared/
│   ├── error/
│   ├── web/
│   ├── security/
│   ├── pagination/
│   └── time/
├── identity/
├── seller/
├── customer/
├── catalog/
├── inventory/
├── order/
├── sale/
├── payment/
├── document/
├── notification/
└── audit/
```

Un módulo puede usar esta estructura interna cuando sea necesaria:

```text
module/
├── api/
├── application/
├── domain/
└── infrastructure/
```

No es obligatorio crear las cuatro carpetas en módulos simples. La estructura
debe reflejar complejidad real y no una plantilla ceremonial.

## Dirección de dependencias

```text
web/controller
      ↓
application/use case
      ↓
domain

infrastructure → application/domain
```

El dominio no depende de Spring MVC, controllers ni detalles de PostgreSQL. La
infraestructura implementa persistencia, clientes HTTP y renderers externos.

## APIs internas

Un módulo expone únicamente operaciones que otro módulo necesita. Ejemplos:

```text
InventoryFacade.registerSaleIssue(...)
SaleFacade.createFromConfirmedOrder(...)
PaymentFacade.registerConfirmationPayments(...)
DocumentFacade.renderSale(...)
```

Los contratos deben usar comandos, DTOs o value objects propios. No se exponen
entidades JPA como API entre módulos.

## Persistencia por módulo

Se utilizará un schema PostgreSQL por módulo. El módulo propietario define sus
tablas, repositories y mapeos JPA. Las referencias entre módulos se expresan
preferentemente como IDs y se validan mediante casos de uso.

## Seguridad de aplicación

- Autenticación local con Spring Security.
- Access JWT de 15 minutos.
- Refresh token de 7 días, rotativo y revocable.
- Passwords hasheadas con Argon2id o BCrypt.
- Autorización por permisos mediante method security.
- Administrador con capacidad global `ADMIN_ALL`.
- Auditoría para operaciones sensibles.

## Eventos y transacciones

Las invariantes críticas son síncronas. Los efectos secundarios se procesan
mediante eventos y Transactional Outbox. La outbox se guarda en la misma
transacción que el cambio de negocio y se procesa con un worker del monolito.

## Convenciones backend

- DTOs para entrada y salida HTTP.
- Records Java para DTOs inmutables cuando resulte conveniente.
- MapStruct para mapeos repetitivos entre contratos y modelos.
- Lombok limitado a constructores y getters puntuales; evitar `@Data` en JPA.
- `BigDecimal` para importes.
- `Instant` para timestamps técnicos.
- Bean Validation en requests y reglas de dominio en casos de uso/agregados.
- `ddl-auto=validate`; el esquema se modifica únicamente con Flyway.
- Controllers delgados; no contienen reglas comerciales.
