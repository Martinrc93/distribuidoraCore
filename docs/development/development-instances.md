# Dos instancias de main con una entrada común

`compose.development.yaml` es una configuración independiente y opcional para
ejecutar imágenes de `origin/main` mientras se trabaja en `support`.
`compose.yaml` conserva el arranque habitual de una sola instancia.

## Arranque y selección

Para generar y arrancar las imágenes de la última versión publicada en `main`,
con Docker Desktop en ejecución y desde cualquier rama del repositorio:

```powershell
.\dev-instances.cmd update
```

`update` obtiene `origin/main`, exporta ese commit sin tomar archivos del checkout
y construye cuatro imágenes locales (`distribuidora-main-backend`,
`distribuidora-main-frontend-a`, `distribuidora-main-frontend-b` y
`distribuidora-main-gateway`). Cada imagen lleva el SHA completo como etiqueta y
como metadato `org.opencontainers.image.revision`. Las etiquetas ya construidas
se reutilizan. La actualización no cambia la rama activa ni incorpora archivos
sin commit.

La copia del commit y la versión seleccionada se guardan bajo `.main-instances/`,
excluido de Git y de Docker. Los servicios usan el Compose guardado en esa copia;
la configuración Nginx está dentro de las imágenes, sin montajes del checkout.

Para volver a arrancar la versión guardada:

```powershell
.\dev-instances.cmd start
```

`start` no hace fetch, no compila ni cambia de versión. Los cambios en `support`,
incluso los que modifiquen Dockerfiles, Nginx o Compose, no entran en estas
imágenes. Para actualizar las instancias hay que publicar los cambios deseados
en `main` y ejecutar `update` explícitamente. `status` muestra el commit fijado.

Abre `http://localhost:3100/` y selecciona **Sistema A** o **Sistema B**. La entrada
redirige a `/a/` o `/b/` en el mismo dominio. Los enlaces, archivos estáticos,
rutas internas y llamadas a la API conservan el prefijo de la instancia,
incluidas la renovación de sesión y las descargas. Para cambiar de sistema,
vuelve a la raíz `/` y selecciona el otro.

La selección utiliza rutas relativas: funciona con `localhost`, un dominio
propio o la URL de un túnel, sin compilar esa dirección dentro del frontend.
Para usar el túnel Docker de este entorno, configura `NGROK_AUTHTOKEN` y
`DEV_NGROK_URL` como variables de entorno, o en el archivo local
`.env.development-tunnel.local` de la raíz:

```dotenv
NGROK_AUTHTOKEN=your-ngrok-token
DEV_NGROK_URL=https://your-domain.ngrok-free.dev
```

Este archivo queda excluido de Git y del contexto de compilación Docker.
Con las instancias arrancadas, inicia el túnel:

```powershell
.\dev-instances.cmd tunnel
```

La URL configurada abre el selector; sus rutas `/a/` y `/b/` abren cada sistema
en ese mismo dominio. El túnel apunta a `gateway:80` dentro de Docker, por lo que
no depende del puerto publicado en Windows. Si el dominio ya está ocupado por
otro túnel, detén ese túnel antes de usarlo aquí.

También puedes usar un agente ngrok local apuntando al puerto de la entrada:
`ngrok http 3100`. No ejecutes ambos agentes sobre el mismo dominio.

El comando `start` inicia solo las instancias; `tunnel` inicia ngrok de forma
explícita. `stop` detiene las instancias y su túnel. Solo la entrada publica
un puerto, por defecto en `127.0.0.1`; las bases de datos y los backends quedan
dentro de las redes de Docker de este proyecto.

## Datos y sesiones

Cada sistema contiene frontend, backend y PostgreSQL propios. Las redes
`system-a` y `system-b`, los volúmenes `data-a` y `data-b`, y los secretos e
identificadores de JWT son diferentes. El navegador guarda las credenciales
en claves distintas por prefijo; abrir A y B, incluso en pestañas diferentes,
no reutiliza las credenciales de la otra instancia. La preferencia visual de
tema se comparte en el navegador.

Ambas instancias usan el mismo commit de `main`, con frontends compilados para
sus respectivos prefijos. Esto permite probar datos independientes con una
versión estable mientras el checkout continúa en `support`.
Los datos demo y las credenciales locales se inicializan en cada base.
El administrador de desarrollo es `dev-admin@distribuidora.local` y su
contraseña inicial es `ChangeMe123!`.

Puedes establecer `DEV_ADMIN_PASSWORD` y `DEV_DEMO_PASSWORD` antes del primer
arranque. Cambiar estas variables no reemplaza contraseñas de cuentas existentes.
Esta configuración no conecta servicios de correo externos.

## Puertos y operación

Para usar otro puerto:

```powershell
$env:DEV_GATEWAY_PORT = '3200'
.\dev-instances.cmd start
```

`DEV_GATEWAY_BIND` permite cambiar la dirección de escucha si necesitas acceso
por red local; por defecto es `127.0.0.1`. Usa el puerto elegido también en el
túnel si usas un agente local; el túnel Docker no necesita cambiar. La URL
pública sigue siendo independiente del puerto interno.

```powershell
.\dev-instances.cmd status
.\dev-instances.cmd logs
.\dev-instances.cmd stop
```

`stop` elimina los contenedores y redes de este proyecto, conservando ambos
volúmenes. No detiene los contenedores del Compose habitual ni los de otros
proyectos. El siguiente `start` reutiliza los datos y las imágenes guardadas;
no reconstruye desde la rama de trabajo. `update` también conserva los volúmenes
`data-a` y `data-b` al recrear los contenedores.

El Compose de ejecución no contiene instrucciones `build`. Para operar sin el
script hay que usar la copia y el SHA seleccionados, por ejemplo:

```powershell
$revision = (Get-Content .main-instances/current.json -Raw | ConvertFrom-Json).revision
$env:DISTRIBUIDORA_MAIN_IMAGE_TAG = $revision
docker compose -p distribuidora-dev-instances -f ".main-instances/releases/$revision/compose.development.yaml" up -d --no-build --wait --wait-timeout 300
```

## Verificación

Las pruebas de `appBase`, `instanceClient` e `InstanceLauncher` comprueban los
prefijos, enlaces y credenciales independientes. La verificación contra Docker
usa `frontend/playwright.instances.config.ts`:

```powershell
cd frontend
npx playwright test --config playwright.instances.config.ts
```

Estas pruebas requieren las dos instancias arrancadas en `3100`, con las
credenciales de desarrollo (`DEV_ADMIN_PASSWORD` si se cambió la contraseña).
Comprueban selección por teclado, login, recarga de rutas internas, navegación
y aislamiento entre las APIs, sin modificar los datos comerciales. Las capturas
y trazas se guardan en `frontend/test-results/instances`.
