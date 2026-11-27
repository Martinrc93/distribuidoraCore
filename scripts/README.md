# Comandos de desarrollo

Ejecutar desde la raíz del proyecto `distribuidora`.

## Reinicio y carga inicial del Docker local de `support`

El script requiere Docker Compose, PowerShell 7.2 o posterior y el Chromium de
Playwright. Desde `frontend`, instalar con `npm ci` y `npx playwright install chromium`.

```powershell
# Ensayo completo y capturas para un manual con datos ficticios.
.\scripts\support-onboarding.ps1 -Mode Sample

# Reinicio del Docker local. Ejecutar una vez acordado qué cuenta conservar.
.\scripts\support-onboarding.ps1 -Mode Reset -AdminEmail admin@distribuidora.local -ConfirmReset

# Reinicio del entorno de pruebas existente en el puerto 3200.
.\scripts\support-onboarding.ps1 -Mode Reset -Target Support3200 -ConfirmReset

# Carga ficticia y prueba de venta en el Docker del puerto 3200.
.\scripts\support-onboarding.ps1 -Mode Load -Target Support3200 -ExampleData

# Carga inicial. Usar una copia del ejemplo completada con los datos reales.
.\scripts\support-onboarding.ps1 -Mode Load -DataPath .\output\carga-inicial.json
```

`Sample` crea su propio proyecto Compose con puertos de loopback dinámicos.
Ejecuta el reinicio, carga el [ejemplo JSON](support-onboarding.example.json),
confirma un pedido, lo prepara, lo entrega con un cobro y verifica stock y pago.
Retira sus contenedores y volumen al terminar.

`Reset` conserva el identificador y la contraseña actual del admin seleccionado.
Pide la contraseña sin mostrarla y la comprueba iniciando sesión con la base
nueva. Primero verifica un backup cifrado en `backups/` con la [clave de respaldo
ya configurada](../docs/operations/postgres-backup-restore.md). Para preparar el
almacén local por primera vez, ejecutar `.\scripts\initialize-backup-key.ps1`.
Crea una base
provisional con Flyway y cambia los nombres de ambas bases en una transacción.
Conserva la original hasta verificar que la aplicación arranca, solo queda el
admin y los datos comerciales están vacíos. Deja `SEED_DEMO=false` en `.env`.
La variable de entorno de la sesión de PowerShell tiene precedencia sobre `.env`.

`Support3200` usa los contenedores existentes de `distribuidora-support-tests`,
su volumen y la contraseña configurada para `admin@demo`. Comprueba el acceso
antes y después del reinicio y conserva las imágenes y el objetivo de Flyway
del entorno. El backup de este modo lleva el prefijo
`distribuidora-support-tests-before-onboarding-`.

`Load` aplica la carga mediante la API. Requiere una base sin pedidos ni ventas.
El archivo define datos del comercio, zonas, marcas, categorías, proveedores,
listas, productos, vendedores y clientes. `business.logoPath` es opcional y se
resuelve desde el archivo JSON. Las claves de `products.prices` y `customers.priceList`
usan `GENERAL` o el nombre de una lista de `priceLists`. El campo `sellers.passwordEnv`
indica la variable de entorno que contiene la contraseña del usuario a crear.
Definir una variable por vendedor y mantener las contraseñas fuera del JSON. La
carga se detiene ante un error y permite repetir el mismo archivo para completar
registros faltantes. Los saldos de apertura se concilian aparte. El pedido de
prueba se genera en `Sample` y al cargar `Support3200` con `-ExampleData`.
En ese último caso, el pedido y su cobro quedan registrados en el Docker del
puerto 3200.

Cada ejecución escribe en `output/support-onboarding/<ejecución>/` un
`verificacion.json` y dos PNG por etapa ejecutada. `01-verificacion` muestra el
estado comprobado y `02-manual` muestra la pantalla o formulario a documentar.
Para compartir capturas, usar las de `Sample`, que contienen datos ficticios.

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

También incluye 12 proveedores demo y 120 pedidos a proveedores, con 10 pedidos
por proveedor entre hoy menos 29 días y hoy. Los productos demo tienen cero, uno
o dos proveedores: una quinta parte queda sin asociación para probar el campo
opcional. Los pedidos guardan cantidades, nombres y costos históricos inferiores
a los actuales, para probar «Cargar pedido anterior» con costos actualizados.
La gestión de proveedores y sus pedidos conserva el acceso exclusivo del admin.
No se crean reglas de descuento, pagos ni movimientos de stock por compras.

Al repetir el comando, se agregan los datos de compras faltantes y se actualizan
solo las fechas de sus pedidos demo, conservando IDs, líneas e importes. Los
proveedores y pedidos cargados manualmente se conservan. Las contraseñas de
usuarios existentes no se cambian. La compilación utiliza una carpeta aislada
en `frontend/test-results/seed-runtime` para poder ejecutar la seed con el
backend abierto sin bloquear sus archivos `.class`.

Cada cliente tiene un número estable de cuatro dígitos. Sus pedidos concatenan
ese número y un correlativo propio de cinco dígitos, sin separadores; por ejemplo,
cliente `0001`, cuarto pedido `00004`: `000100004`. La seed usa la misma
numeración que el alta normal. Las referencias `PED-…` se conservan internamente
como alias para identificar sus registros al actualizar fechas sin renumerarlos.

## Iniciar el entorno local

```powershell
.\dev.cmd start
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

Para ejecutar **dos instancias de main** con bases independientes y una pantalla
inicial de selección, usa `.\dev-instances.cmd update` para generar las imágenes
desde `origin/main`. Los siguientes arranques usan `.\dev-instances.cmd start`,
sin reconstruir ni tomar cambios de `support`.
La entrada queda en `http://localhost:3100/`, con los sistemas bajo `/a/` y `/b/`.
Configuración y operación: [instancias de desarrollo](../docs/development/development-instances.md).

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
