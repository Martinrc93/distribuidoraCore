# Comandos de desarrollo

Ejecutar desde la raíz del proyecto `distribuidora`.

## Iniciar el entorno local

```powershell
.dev.cmd start
```

El comando inicia PostgreSQL mediante Docker Compose y espera a que esté listo.
Luego abre el backend Spring Boot y el frontend Vite en ventanas separadas. El
backend se conecta al PostgreSQL del contenedor en `localhost:5433`, para no
confundirlo con una instalación local de PostgreSQL que use el puerto `5432`.

- Frontend: `http://localhost:5173`
- Backend: `http://localhost:8080`
- PostgreSQL Docker: `localhost:5433` (`distribuidora` / `distribuidora`)

Para detener frontend y backend, cerrá sus ventanas o presioná `Ctrl+C` en cada
una. Para detener PostgreSQL conservando los datos, ejecutá
`docker compose stop db` desde la raíz del repositorio.

## Frontend

```powershell
.\scripts\dev.ps1 front
```

Alternativa:

```text
scripts\dev-front.cmd
```

El frontend queda disponible en `http://localhost:5173`.

## Backend

```powershell
.\scripts\dev.ps1 back
```

Alternativa:

```text
scripts\dev-back.cmd
```

El backend utiliza el puerto `8080` y requiere PostgreSQL disponible con las
variables `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` correspondientes.

## Frontend y backend

```powershell
.\scripts\dev.ps1 both
```

Alternativa:

```text
scripts\dev.cmd both
```

El comando abre una ventana de PowerShell para cada proceso. Para detenerlos,
cerrar ambas ventanas o presionar `Ctrl+C` dentro de cada una.

## Docker Compose

Desde la raíz del proyecto:

```powershell
docker compose up --build
```

La SPA queda disponible en `http://localhost:3000`, el backend en
`http://localhost:8080` y PostgreSQL en `localhost:5433`. Flyway ejecuta las
migraciones automáticamente cuando el backend logra conectarse a PostgreSQL.

Para detener los servicios:

```powershell
docker compose down
```

Para eliminar también los datos locales de PostgreSQL:

```powershell
docker compose down -v
```
