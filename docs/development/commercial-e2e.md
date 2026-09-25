# E2E comercial local

El E2E usa un PostgreSQL descartable y recorre el flujo con la UI real en
Chromium de escritorio y emulación móvil: login, alta de cliente, producto con
precios por lista activa, ajuste de stock, confirmación del pedido, detalle del
pedido y detalle de venta. También verifica navegación por teclado, foco visible
y que el documento no desborde el ancho de pantalla móvil.

## Preparar y ejecutar

1. Crear una base de datos vacía dedicada al E2E en PostgreSQL 16. No apuntar a
   una base con datos de usuario: Flyway crea el esquema y las pruebas escriben
   datos comerciales.
2. Desde `frontend`, configurar `E2E_DB_URL`, `E2E_DB_USERNAME` y
   `E2E_DB_PASSWORD` para esa base.
3. Ejecutar `npm install` y luego `npm run test:e2e`.

La configuración inicia el backend en `127.0.0.1:8080`, Vite en
`127.0.0.1:5173` y crea un administrador de prueba. Maven compila en
`backend/target-codex-e2e`, separado de `backend/target`. Los dos proyectos
Playwright corren en serie contra la base configurada.

En Windows se puede usar Brave ya instalado configurando
`PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` con la ruta al ejecutable; en otros
entornos Playwright usa Chromium instalado según su configuración habitual.
