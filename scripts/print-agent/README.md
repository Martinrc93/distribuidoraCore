# Servicio local de impresión (Windows)

Permite que el navegador detecte las impresoras instaladas en esta PC y envíe automáticamente cada PDF **A4** o **Ticket 80 mm** a la impresora elegida, sin abrir el diálogo de impresión cada vez.

No requiere instalar programas externos ni permisos de administrador: utiliza PowerShell y las APIs nativas de impresión de Windows 10/11.

## Uso rápido en una PC con Windows

1. **Inicio automático con Windows (recomendado):** ejecutar `instalar-inicio-windows.cmd`. Inicia el servicio en segundo plano y crea un acceso directo en el inicio de sesión de Windows del usuario actual.
2. **Solo para esta sesión:** ejecutar `iniciar-impresion.cmd`.
3. Abrir el sistema en el navegador, ir a **Administración → Configuración → Impresoras** y elegir la impresora para **A4** y la impresora para **Ticket 80 mm**. La elección se guarda en el navegador de esa PC.
4. Para detenerlo y quitarlo del inicio automático, ejecutar `desinstalar-inicio-windows.cmd`.

## Seguridad y red

- Escucha únicamente en `http://127.0.0.1:47891` (no se expone a la red local ni a internet).
- Solo imprime en impresoras instaladas en Windows y únicamente acepta documentos PDF válidos (`%PDF-`) o páginas de prueba de `/test`.
- Si el servicio no está activo (o desde un celular), el sistema abre el diálogo de impresión del navegador como respaldo.
