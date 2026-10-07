# Cálculo de descuentos manuales

> Enviar descuentos manuales requiere `ADMIN_ALL`. Solo se aplican los porcentajes explícitos durante la confirmación/edición del pedido; cero conserva el precio completo. V32 retira las reglas automáticas sin recalcular los importes históricos.

```mermaid
flowchart TD
    A[Resolver precio y lista efectiva] --> B{Descuento manual positivo?}
    B -->|Sí| C[Validar ADMIN_ALL y porcentaje]
    B -->|No, cero| D[Conservar precio completo]
    C --> E[Aplicar descuento de línea]
    D --> F[Sumar líneas]
    E --> F
    F --> G{Descuento manual de pedido positivo?}
    G -->|Sí| H[Validar ADMIN_ALL y porcentaje]
    G -->|No, cero| I[Conservar subtotal]
    H --> J[Aplicar descuento al subtotal restante]
    I --> K[Guardar porcentajes e importes en snapshots]
    J --> K
```
