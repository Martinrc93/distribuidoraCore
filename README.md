# Distribuidora

Sistema de gestión comercial para pedidos, ventas, stock, cuentas corrientes,
compras, gastos y comisiones. Usa un frontend React y un backend Spring Boot
con PostgreSQL.

## Arranque local

Con Docker y Docker Compose instalados, iniciar los servicios desde la raíz del repositorio.

```sh
docker compose up --build --wait
```

La aplicación queda disponible en `http://localhost:3000` y PostgreSQL en
`localhost:5433`. Para desarrollo con recarga automática, usar
`.\dev.cmd start` en Windows. Los requisitos de desarrollo son Java 21,
Maven 3.9 y Node.js 22 o posterior.

## Pruebas

En `backend/`, ejecutar `mvn clean verify` (unitarias, `*IT`, Checkstyle,
SpotBugs, ArchUnit y JaCoCo) con Docker y las variables
`POSTGRES_TEST_URL`, `POSTGRES_TEST_USERNAME` y `POSTGRES_TEST_PASSWORD`
apuntando a una base PostgreSQL 16 descartable. Las pruebas de migración pueden modificar
su esquema y las de Testcontainers usan bases independientes.

Ejecutar los controles del frontend desde `frontend/`.

```sh
npm ci
npm run lint
npm run build
npm run test:coverage
npx playwright install chromium
npm run test:mocked
```

`npm run test:e2e` ejecuta el flujo comercial de escritorio y móvil con un
backend real. Requiere `E2E_DB_URL`, `E2E_DB_USERNAME` y
`E2E_DB_PASSWORD` para una base PostgreSQL descartable.

## Operación

Aplicar `compose.production.yaml` sobre `compose.yaml` para ejecutar con
PostgreSQL y backend en la red interna de Compose. Definir `POSTGRES_PASSWORD`,
`JWT_SECRET` y, si corresponde, `ADMIN_EMAIL` y `ADMIN_PASSWORD`. Configurar
`APP_PUBLIC_URL` con la URL externa. Si un proxy TLS convierte HTTPS en HTTP
antes de llegar al frontend, incluir el origen HTTPS en `CORS_ALLOWED_ORIGINS`.
Los orígenes explícitos adicionales se separan con comas.

- [API REST](docs/api/api-guidelines.md)
- [Arquitectura actual](docs/architecture/current-state.md)
- [Backup y restauración](docs/operations/postgres-backup-restore.md)
- [Instancias de desarrollo](docs/development/development-instances.md)
- [Guía de interfaz](docs/development/ui-style-guide.md)
- [Avance de remediación](docs/development/remediation-roadmap-2026-11-24.md)
