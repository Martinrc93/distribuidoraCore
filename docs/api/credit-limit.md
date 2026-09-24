# Configuración del límite de crédito global

## Consultar configuración

`GET /api/settings/credit-limit`

Requiere `ADMIN_ALL`. Respuesta `200 OK`:

```json
{
  "creditLimit": 100000.0000,
  "enabled": true,
  "updatedAt": "2026-09-24T03:00:00Z",
  "updatedBy": "00000000-0000-0000-0000-000000000001"
}
```

## Cambiar o desactivar

`PUT /api/settings/credit-limit`

```json
{ "creditLimit": 100000.00 }
```

`creditLimit` admite cero o importes positivos con hasta cuatro decimales. Enviar `null` desactiva el aviso. El cambio se persiste en `app.business_settings`, guarda actor/fecha y genera auditoría `GLOBAL_CREDIT_LIMIT_UPDATE`. El límite no bloquea pedidos.

## Advertencia al confirmar un pedido

La confirmación calcula:

```text
saldo actual del cliente + nueva deuda a cuenta corriente
```

Si el límite está habilitado y el saldo proyectado lo supera, la respuesta `201` incluye:

```json
"creditLimitWarning": {
  "creditLimit": 100000.0000,
  "projectedBalance": 125000.0000,
  "exceededBy": 25000.0000
}
```

La confirmación sigue su curso. Se registra `CREDIT_LIMIT_WARNING` en la misma transacción; el snapshot del límite y del saldo proyectado queda en el pedido para que un reintento idempotente devuelva la misma advertencia. Sin exceso, `creditLimitWarning` es `null`.

El detalle de pedido expone `creditLimitExceeded`, `creditLimitSnapshot` y `projectedBalanceSnapshot`.

## Errores relevantes

- `400 INVALID_REQUEST`: importe negativo o con más de cuatro decimales.
- `401`: JWT ausente o inválido.
- `403 FORBIDDEN`: falta `ADMIN_ALL` para consultar/cambiar la configuración.

## Persistencia

V18 crea `app.business_settings` con límite inicialmente desactivado y guarda en `orders.orders` el resultado/snapshot de advertencia calculado al confirmar.
