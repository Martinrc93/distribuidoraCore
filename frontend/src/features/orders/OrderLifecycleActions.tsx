import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { apiGet, apiPost, ApiError } from '../../shared/api/client'
import { DocumentActionIcon } from '../../shared/components/DocumentActionIcon'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DeliveryAttemptDialog, resellerCollectionsNotice } from './DeliveryAttemptDialog'
import { OrderNotificationDialog, type NotificationChannel, type NotificationFormat } from './OrderNotificationDialog'
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
const documents = [
  { format: 'a4', notificationFormat: 'A4', name: 'remito A4', title: 'Remito A4' },
  { format: 'ticket', notificationFormat: 'TICKET', name: 'ticket 80 mm', title: 'Ticket 80 mm' },
] as const
const notificationChannels = [
  { channel: 'WHATSAPP', icon: 'whatsapp', name: 'WhatsApp' },
  { channel: 'EMAIL', icon: 'email', name: 'email' },
] as const

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
  const showOperations = Boolean(statusBadges || children) || (canDeliver || canCancel) && orderStatus === 'CONFIRMED'
    || canReactivate && orderStatus === 'CANCELLED'
  const showDocuments = canExport || canNotify
  const DocumentGroupHeading = showOperations ? 'h3' : 'h2'
  const [showDelivery, setShowDelivery] = useState(false)
  const [showCancel, setShowCancel] = useState(false)
  const [showReactivate, setShowReactivate] = useState(false)
  const [sendTarget, setSendTarget] = useState<{ channel: NotificationChannel; format: NotificationFormat }>()
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
    {(showOperations || showDocuments) && <section className="order-detail-actions">
      {showOperations && <div className="order-detail-operations">
        <div className="panel-header">
          <h2>Operaciones del pedido</h2>
          {statusBadges}
        </div>
        <div id="order-operations" tabIndex={-1} className="page-actions items-center justify-end">
          {children}
          {canDeliver && orderStatus === 'CONFIRMED' && <Button onClick={() => { setShowDelivery(true); setError(''); delivery.clearError() }}>Registrar entrega</Button>}
          {canCancel && orderStatus === 'CONFIRMED' && <Button variant="danger" onClick={() => { setShowCancel(true); setError('') }}>Cancelar pedido</Button>}
          {canReactivate && orderStatus === 'CANCELLED' && <Button onClick={() => { setShowReactivate(true); setError('') }}>Reactivar pedido</Button>}
        </div>
        {notes}
      </div>}
      {showDocuments && <div className="order-documents">
        <div className="order-document-groups">
          {documents.map(({ format, notificationFormat, name, title }) => {
            const [whatsapp, email] = canNotify ? notificationChannels.map(({ channel, icon, name: channelName }) => {
              const label = `Enviar ${name} por ${channelName}`
              return <Button key={channel} type="button" variant="secondary" aria-label={label} title={label}
                aria-haspopup="dialog" onClick={() => setSendTarget({ channel, format: notificationFormat })}>
                <DocumentActionIcon action={icon} />
              </Button>
            }) : []

            return <div key={format} className="order-document-group" role="group" aria-label={title}>
              <DocumentGroupHeading className="order-document-group-title">{title}</DocumentGroupHeading>
              <div className="order-document-controls">
                {canExport ? <DocumentActions path={`/api/orders/${orderId}/documents/${format}`} filename={`${orderNumber}-${format}.pdf`}
                  format={format} iconOnly printLabel={`Imprimir ${name}`} downloadLabel={`Descargar ${name}`}
                  renderActions={({ print, download }) => <>{whatsapp}{print}{email}{download}</>}
                  onDone={(message) => { setError(''); setFeedback(message) }} /> : <>{whatsapp}{email}</>}
              </div>
            </div>
          })}
        </div>
        {notification && <div className="mt-[14px]">
          <div className="section-heading">
            <div className="min-w-0 max-w-full [overflow-wrap:anywhere]"><DocumentGroupHeading>Estado del envío por {notification.channel === 'EMAIL' ? 'email' : 'WhatsApp'}</DocumentGroupHeading><p>Solicitud {notification.id}</p></div>
            <Button variant="secondary" onClick={() => void notificationQuery.refetch()}>Consultar estado</Button>
          </div>
          {notificationQuery.isLoading ? <p role="status">Consultando estado…</p> : notificationQuery.isError ? <p className="error-text" role="alert">{notificationQuery.error.message}</p> : notificationQuery.data && <p role="status"><strong>{statusName[notificationQuery.data.status] ?? notificationQuery.data.status}</strong> · {notificationQuery.data.attemptCount} intentos{notificationQuery.data.lastError ? ` · ${notificationQuery.data.lastError}` : ''}</p>}
        </div>}
      </div>}
    </section>}
    {showDelivery && <DeliveryAttemptDialog orderNumber={orderNumber} saleBalance={saleBalance} previousDebtAvailable={previousDebtAvailable} value={delivery.draft} pending={delivery.pending} error={delivery.error} onChange={delivery.changeDraft} onSubmit={delivery.submit} onClose={() => setShowDelivery(false)} collectionsNotice={resellerCollectionsNotice(accountKind)} />}
    {showCancel && <ConfirmationDialog title={`¿Cancelar el pedido ${orderNumber}?`} description="Se cancelará el pedido. Si estaba preparado, se devolverán sus productos al stock." confirmLabel="Confirmar cancelación" pendingLabel="Cancelando..." pending={busy} error={error} onCancel={() => setShowCancel(false)} onConfirm={() => void cancelOrder()} />}
    {showReactivate && <ConfirmationDialog title={`¿Reactivar el pedido ${orderNumber} como confirmado?`} description="Se restaurará el saldo de la venta y quedará pendiente de preparación. El stock se descontará al preparar o entregar." confirmLabel="Confirmar reactivación" pendingLabel="Reactivando..." confirmVariant="primary" pending={busy} error={error} onCancel={() => setShowReactivate(false)} onConfirm={() => void reactivateOrder()} />}
    {sendTarget && <OrderNotificationDialog orderId={orderId} customerId={customerId} channel={sendTarget.channel}
      documentFormat={sendTarget.format} onClose={() => setSendTarget(undefined)}
      onRequested={({ requestId, status }) => {
        setNotification({ id: requestId, channel: sendTarget.channel })
        setFeedback(`Solicitud de envío ${statusName[status] ?? status.toLowerCase()}.`)
        setSendTarget(undefined)
      }} />}
  </>
}
