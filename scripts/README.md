# Comandos de desarrollo

Ejecutar desde la raíz del proyecto `distribuidora`.

## Seed demo con fechas actuales

```powershell
.\scripts\seed.ps1
```

El comando crea los datos demo y distribuye los 1.000 pedidos y ventas entre
30 días antes y 30 días después de la fecha actual de Buenos Aires, incluyendo
ambos extremos y el día actual. La fecha se calcula una sola vez por ejecución.
Si la seed ya existe, actualiza las fechas de esos pedidos, ventas y sus pagos,
débitos y movimientos de stock originales, sin duplicar datos ni modificar
importes, saldos o transacciones posteriores.

Requiere Java 21 y Maven. Si no se define `DB_URL`, inicia PostgreSQL con Docker
Compose y usa `localhost:5433`. Para otra base local, definir `DB_URL`,
`DB_USERNAME` y `DB_PASSWORD`. `DEMO_PASSWORD` permite configurar la contraseña
de los usuarios demo nuevos; el perfil local usa `ChangeMe123!` por defecto.
La ejecución termina al completar la seed y no ocupa el puerto `8080`.

La seed incluye 3 categorías (Bebidas, Almacén y Limpieza), 6 marcas demo y
10 zonas demo. Los productos demo se distribuyen entre las categorías y marcas,
y los clientes entre las zonas. Las ejecuciones posteriores completan las
asignaciones faltantes y reutilizan los registros existentes sin duplicarlos.

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
