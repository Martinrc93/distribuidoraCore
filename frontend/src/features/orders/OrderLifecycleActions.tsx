import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { apiGet, apiPost, ApiError } from '../../shared/api/client'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Panel } from '../../shared/components/Panel'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DeliveryAttemptDialog, resellerCollectionsNotice } from './DeliveryAttemptDialog'
import { OrderNotificationDialog, type NotificationChannel } from './OrderNotificationDialog'
import { useOrderDelivery } from './useOrderDelivery'
type NotificationStatus = { requestId: string; status: string; attemptCount: number; requestedAt: string; sentAt?: string | null; lastError?: string | null }
type Props = {
  orderId: string
  orderNumber: string
  customerId?: string
  orderStatus: string
  saleBalance: number
  previousDebtAvailable?: number
  accountKind?: string | null
  onChanged: () => void
  statusBadges?: ReactNode
  children?: ReactNode
  notes?: ReactNode
}

const statusName: Record<string, string> = { QUEUED: 'En cola', SENDING: 'Enviando', SENT: 'Enviado', FAILED: 'Falló', RETRY_EXHAUSTED: 'Reintentos agotados' }

function apiMessage(cause: unknown, fallback: string) {
  if (cause instanceof ApiError && cause.status === 403) return 'Tu usuario no tiene permiso para realizar esta acción.'
  if (cause instanceof ApiError && cause.status === 404) return 'El pedido ya no está disponible.'
  if (cause instanceof ApiError && cause.status === 409) return cause.detail || 'El estado del pedido no permite esta acción.'
  return cause instanceof Error ? cause.message : fallback
}

export default function OrderLifecycleActions({ orderId, orderNumber, customerId, orderStatus, saleBalance, previousDebtAvailable = 0, accountKind, onChanged, statusBadges, children, notes }: Props) {
  const queryClient = useQueryClient()
  const canDeliver = hasAuthority('SALE_DELIVER') || hasAuthority('ADMIN_ALL')
  const canCancel = hasPermission('ORDER_CANCEL')
  const canReactivate = hasPermission('ORDER_REACTIVATE')
  const canExport = hasPermission('ORDER_DOCUMENT_EXPORT')
  const canNotify = hasPermission('ORDER_NOTIFY')
  const [showDelivery, setShowDelivery] = useState(false)
  const [showCancel, setShowCancel] = useState(false)
  const [showReactivate, setShowReactivate] = useState(false)
  const [channel, setChannel] = useState<NotificationChannel>()
  const [notification, setNotification] = useState<{ id: string; channel: NotificationChannel }>()
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [busy, setBusy] = useState(false)
  const delivery = useOrderDelivery({ orderId, saleBalance, previousDebtAvailable, onSaved: result => {
    setShowDelivery(false)
    setFeedback(result === 'DELIVERED' ? 'Entrega registrada.' : 'Intento fallido registrado.')
    onChanged()
  } })
  const notificationQuery = useQuery({
    queryKey: queryKeys.orders.notificationStatus(orderId, notification?.id),
    queryFn: ({ signal }) => apiGet<NotificationStatus>(`/api/orders/${orderId}/notifications/${notification?.id}`, signal),
    enabled: Boolean(notification),
    retry: false,
  })

  async function cancelOrder() {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await apiPost(`/api/orders/${orderId}/cancel`, {})
      await invalidateDomains(queryClient, 'orders', 'sales', 'customers', 'stock', 'reports')
      setShowCancel(false)
      setFeedback('Pedido cancelado. Se revirtió el stock si estaba preparado.')
      onChanged()
    } catch (cause) {
      setError(apiMessage(cause, 'No se pudo cancelar el pedido.'))
    } finally {
      setBusy(false)
    }
  }

  async function reactivateOrder() {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await apiPost(`/api/orders/${orderId}/reactivate`, {})
      await invalidateDomains(queryClient, 'orders', 'sales', 'customers', 'stock', 'reports')
      setShowReactivate(false)
      setFeedback('Pedido reactivado, pendiente de preparación. Se restauró la deuda.')
      onChanged()
    } catch (cause) {
      setError(apiMessage(cause, 'No se pudo reactivar el pedido.'))
    } finally {
      setBusy(false)
    }
  }


  return <>
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && !showDelivery && !showCancel && !showReactivate && <p className="error-text" role="alert">{error}</p>}
    {(statusBadges || children || (canDeliver || canCancel) && orderStatus === 'CONFIRMED' || canReactivate && orderStatus === 'CANCELLED') && <Panel title="Operaciones del pedido">
      <div id="order-operations" tabIndex={-1} className="page-actions items-center">
        {statusBadges && <div className="page-actions max-w-full items-center empty:hidden">{statusBadges}</div>}
        <div className="page-actions ml-auto min-w-0 max-w-full justify-end empty:hidden">
          {children}
          {canDeliver && orderStatus === 'CONFIRMED' && <Button onClick={() => { setShowDelivery(true); setError(''); delivery.clearError() }}>Registrar entrega</Button>}
          {canCancel && orderStatus === 'CONFIRMED' && <Button variant="danger" onClick={() => { setShowCancel(true); setError('') }}>Cancelar pedido</Button>}
          {canReactivate && orderStatus === 'CANCELLED' && <Button onClick={() => { setShowReactivate(true); setError('') }}>Reactivar pedido</Button>}
        </div>
      </div>
      {notes}
    </Panel>}
    {(canExport || canNotify) && <Panel title="Documentos del pedido">
      <div className="page-actions">
        {canExport && <><DocumentActions path={`/api/orders/${orderId}/documents/a4`} filename={`${orderNumber}-a4.pdf`} printLabel="Imprimir remito A4" downloadLabel="Descargar remito A4" onDone={(message) => { setError(''); setFeedback(message) }} /><DocumentActions path={`/api/orders/${orderId}/documents/ticket`} filename={`${orderNumber}-ticket.pdf`} printLabel="Imprimir ticket 80 mm" downloadLabel="Descargar ticket 80 mm" onDone={(message) => { setError(''); setFeedback(message) }} /></>}
        {canNotify && <>
          <Button variant="secondary" aria-haspopup="dialog" onClick={() => setChannel('EMAIL')}>Enviar por email</Button>
          <Button variant="secondary" aria-haspopup="dialog" onClick={() => setChannel('WHATSAPP')}>Enviar por WhatsApp</Button>
        </>}
      </div>
      {notification && <div className="mt-[14px]">
        <div className="section-heading">
          <div className="min-w-0 max-w-full [overflow-wrap:anywhere]"><h3>Estado del envío por {notification.channel === 'EMAIL' ? 'email' : 'WhatsApp'}</h3><p>Solicitud {notification.id}</p></div>
          <Button variant="secondary" onClick={() => void notificationQuery.refetch()}>Consultar estado</Button>
        </div>
        {notificationQuery.isLoading ? <p role="status">Consultando estado…</p> : notificationQuery.isError ? <p className="error-text" role="alert">{notificationQuery.error.message}</p> : notificationQuery.data && <p role="status"><strong>{statusName[notificationQuery.data.status] ?? notificationQuery.data.status}</strong> · {notificationQuery.data.attemptCount} intentos{notificationQuery.data.lastError ? ` · ${notificationQuery.data.lastError}` : ''}</p>}
      </div>}
    </Panel>}
    {showDelivery && <DeliveryAttemptDialog orderNumber={orderNumber} saleBalance={saleBalance} previousDebtAvailable={previousDebtAvailable} value={delivery.draft} pending={delivery.pending} error={delivery.error} onChange={delivery.changeDraft} onSubmit={delivery.submit} onClose={() => setShowDelivery(false)} collectionsNotice={resellerCollectionsNotice(accountKind)} />}
    {showCancel && <ConfirmationDialog title={`¿Cancelar el pedido ${orderNumber}?`} description="Se cancelará el pedido. Si estaba preparado, se devolverán sus productos al stock." confirmLabel="Confirmar cancelación" pendingLabel="Cancelando..." pending={busy} error={error} onCancel={() => setShowCancel(false)} onConfirm={() => void cancelOrder()} />}
    {showReactivate && <ConfirmationDialog title={`¿Reactivar el pedido ${orderNumber} como confirmado?`} description="Se restaurará el saldo de la venta y quedará pendiente de preparación. El stock se descontará al preparar o entregar." confirmLabel="Confirmar reactivación" pendingLabel="Reactivando..." confirmVariant="primary" pending={busy} error={error} onCancel={() => setShowReactivate(false)} onConfirm={() => void reactivateOrder()} />}
    {channel && <OrderNotificationDialog orderId={orderId} customerId={customerId} channel={channel} onClose={() => setChannel(undefined)}
      onRequested={({ requestId, status }) => {
        setNotification({ id: requestId, channel })
        setFeedback(`Solicitud de envío ${statusName[status] ?? status.toLowerCase()}.`)
        setChannel(undefined)
      }} />}
  </>
}
