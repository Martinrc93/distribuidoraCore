# Comandos de desarrollo

Ejecutar desde la raíz del proyecto `distribuidora`.

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
`http://localhost:8080` y PostgreSQL en `localhost:5432`. Flyway ejecuta las
migraciones automáticamente cuando el backend logra conectarse a PostgreSQL.

Para detener los servicios:

```powershell
docker compose down
```

Para eliminar también los datos locales de PostgreSQL:

```powershell
docker compose down -v
```
