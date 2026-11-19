import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPost, ApiError } from '../../shared/api/client'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Panel } from '../../shared/components/Panel'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DeliveryAttemptDialog, resellerCollectionsNotice } from './DeliveryAttemptDialog'
import { useOrderDelivery } from './useOrderDelivery'
type NotificationStatus = { requestId: string; status: string; attemptCount: number; requestedAt: string; sentAt?: string | null; lastError?: string | null }
type Props = { orderId: string; orderNumber: string; orderStatus: string; saleBalance: number; previousDebtAvailable?: number; accountKind?: string | null; onChanged: () => void }

const statusName: Record<string, string> = { QUEUED: 'En cola', SENDING: 'Enviando', SENT: 'Enviado', FAILED: 'Falló', RETRY_EXHAUSTED: 'Reintentos agotados' }
const createKey = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`

function apiMessage(cause: unknown, fallback: string) {
  if (cause instanceof ApiError && cause.status === 403) return 'Tu usuario no tiene permiso para realizar esta acción.'
  if (cause instanceof ApiError && cause.status === 404) return 'El pedido ya no está disponible.'
  if (cause instanceof ApiError && cause.status === 409) return cause.detail || 'El estado del pedido no permite esta acción.'
  return cause instanceof Error ? cause.message : fallback
}

export default function OrderLifecycleActions({ orderId, orderNumber, orderStatus, saleBalance, previousDebtAvailable = 0, accountKind, onChanged }: Props) {
  const queryClient = useQueryClient()
  const canDeliver = hasAuthority('SALE_DELIVER') || hasAuthority('ADMIN_ALL')
  const canCancel = hasPermission('ORDER_CANCEL')
  const canReactivate = hasPermission('ORDER_REACTIVATE')
  const canExport = hasPermission('ORDER_DOCUMENT_EXPORT')
  const canNotify = hasPermission('ORDER_NOTIFY')
  const [showDelivery, setShowDelivery] = useState(false)
  const [showCancel, setShowCancel] = useState(false)
  const [showReactivate, setShowReactivate] = useState(false)
  const [showNotification, setShowNotification] = useState(false)
  const [channel, setChannel] = useState<'EMAIL' | 'WHATSAPP'>('EMAIL')
  const [recipient, setRecipient] = useState('')
  const [format, setFormat] = useState<'A4' | 'TICKET'>('A4')
  const [notificationKey, setNotificationKey] = useState(createKey)
  const [requestId, setRequestId] = useState('')
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [busy, setBusy] = useState(false)
  const delivery = useOrderDelivery({ orderId, saleBalance, previousDebtAvailable, onSaved: result => {
    setShowDelivery(false)
    setFeedback(result === 'DELIVERED' ? 'Entrega registrada.' : 'Intento fallido registrado.')
    onChanged()
  } })
  const notificationQuery = useQuery({
    queryKey: ['order-notification-status', orderId, requestId],
    queryFn: () => apiGet<NotificationStatus>(`/api/orders/${orderId}/notifications/${requestId}`),
    enabled: Boolean(requestId),
    retry: false,
  })

  async function cancelOrder() {
    if (busy) return
    setBusy(true)
    setError('')
    try {
      await apiPost(`/api/orders/${orderId}/cancel`, {})
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: [`/api/orders/${orderId}`] }),
        queryClient.invalidateQueries({ queryKey: ['/api/orders'] }),
        queryClient.invalidateQueries({ queryKey: ['/api/sales'] }),
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => ['/api/orders', '/api/sales', '/api/customers', '/api/products', '/api/inventory'].some(prefix => String(queryKey[0]).startsWith(prefix)) || ['dashboard', 'order-reviews'].includes(String(queryKey[0])) }),
      ])
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
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: [`/api/orders/${orderId}`] }),
        queryClient.invalidateQueries({ queryKey: ['/api/orders'] }),
        queryClient.invalidateQueries({ queryKey: ['/api/sales'] }),
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => typeof queryKey[0] === 'string' && queryKey[0].startsWith('/api/customers') }),
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => typeof queryKey[0] === 'string' && queryKey[0].startsWith('/api/products?') }),
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => typeof queryKey[0] === 'string' && queryKey[0].startsWith('/api/inventory') }),
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      ])
      setShowReactivate(false)
      setFeedback('Pedido reactivado, pendiente de preparación. Se restauró la deuda.')
      onChanged()
    } catch (cause) {
      setError(apiMessage(cause, 'No se pudo reactivar el pedido.'))
    } finally {
      setBusy(false)
    }
  }


  function updateNotification<K extends 'channel' | 'recipient' | 'format'>(field: K, value: K extends 'channel' ? 'EMAIL' | 'WHATSAPP' : K extends 'format' ? 'A4' | 'TICKET' : string) {
    setError('')
    setRequestId('')
    setNotificationKey(createKey())
    if (field === 'channel') setChannel(value as 'EMAIL' | 'WHATSAPP')
    if (field === 'recipient') setRecipient(value)
    if (field === 'format') setFormat(value as 'A4' | 'TICKET')
  }

  async function requestNotification(event: FormEvent) {
    event.preventDefault()
    const validRecipient = channel === 'EMAIL' ? /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(recipient.trim()) : /^\+[1-9]\d{7,14}$/.test(recipient.trim())
    if (!validRecipient) { setError(channel === 'EMAIL' ? 'Ingresá un email válido.' : 'Usá un número de WhatsApp en formato internacional, por ejemplo +549...'); return }
    setBusy(true)
    setError('')
    try {
      const result = await apiPost<{ requestId: string; status: string }>(`/api/orders/${orderId}/notifications`, { channel, recipient: recipient.trim(), format, idempotencyKey: notificationKey })
      setRequestId(result.requestId)
      setFeedback(`Solicitud de envío ${statusName[result.status] ?? result.status.toLowerCase()}.`)
    } catch (cause) {
      setError(apiMessage(cause, 'No se pudo solicitar el envío. Revisá que el proveedor esté configurado.'))
    } finally {
      setBusy(false)
    }
  }

  return <>
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && !showDelivery && !showCancel && !showReactivate && !showNotification && <p className="error-text" role="alert">{error}</p>}
    <Panel title="Acciones del pedido">
      <div className="page-actions">
        {canDeliver && orderStatus === 'CONFIRMED' && <Button onClick={() => { setShowDelivery(true); setError(''); delivery.clearError() }}>Registrar entrega</Button>}
        {canCancel && orderStatus === 'CONFIRMED' && <Button variant="secondary" onClick={() => { setShowCancel(true); setError('') }}>Cancelar pedido</Button>}
        {canReactivate && orderStatus === 'CANCELLED' && <Button onClick={() => { setShowReactivate(true); setError('') }}>Reactivar pedido</Button>}
        {canExport && <><DocumentActions path={`/api/orders/${orderId}/documents/a4`} filename={`${orderNumber}-a4.pdf`} printLabel="Imprimir remito A4" downloadLabel="Descargar remito A4" onDone={(message) => { setError(''); setFeedback(message) }} /><DocumentActions path={`/api/orders/${orderId}/documents/ticket`} filename={`${orderNumber}-ticket.pdf`} printLabel="Imprimir ticket 80 mm" downloadLabel="Descargar ticket 80 mm" onDone={(message) => { setError(''); setFeedback(message) }} /></>}{canNotify && <Button variant="secondary" aria-expanded={showNotification} onClick={() => { setShowNotification((value) => !value); setError('') }}>Enviar por email o WhatsApp</Button>}
      </div>
    </Panel>
    {showDelivery && <DeliveryAttemptDialog orderNumber={orderNumber} saleBalance={saleBalance} previousDebtAvailable={previousDebtAvailable} value={delivery.draft} pending={delivery.pending} error={delivery.error} onChange={delivery.changeDraft} onSubmit={delivery.submit} onClose={() => setShowDelivery(false)} collectionsNotice={resellerCollectionsNotice(accountKind)} />}
    {showCancel && <ConfirmationDialog title={`¿Cancelar el pedido ${orderNumber}?`} description="Se cancelará el pedido. Si estaba preparado, se devolverán sus productos al stock." confirmLabel="Confirmar cancelación" pendingLabel="Cancelando..." pending={busy} error={error} onCancel={() => setShowCancel(false)} onConfirm={() => void cancelOrder()} />}
    {showReactivate && <ConfirmationDialog title={`¿Reactivar el pedido ${orderNumber} como confirmado?`} description="Se restaurará el saldo de la venta y quedará pendiente de preparación. El stock se descontará al preparar o entregar." confirmLabel="Confirmar reactivación" pendingLabel="Reactivando..." confirmVariant="primary" pending={busy} error={error} onCancel={() => setShowReactivate(false)} onConfirm={() => void reactivateOrder()} />}
    {showNotification && <Panel title="Enviar comprobante por email o WhatsApp"><form className="form-grid" onSubmit={requestNotification}>
      <label className="field"><span>Canal de envío</span><select className="select" value={channel} onChange={(event) => updateNotification('channel', event.target.value as 'EMAIL' | 'WHATSAPP')}><option value="EMAIL">Email</option><option value="WHATSAPP">WhatsApp</option></select></label>
      <label className="field"><span>Destinatario</span><input className="input" aria-label="Destinatario" value={recipient} onChange={(event) => updateNotification('recipient', event.target.value)} placeholder={channel === 'EMAIL' ? 'compras@empresa.com' : '+549...'} required /></label>
      <label className="field"><span>Formato de comprobante</span><select className="select" value={format} onChange={(event) => updateNotification('format', event.target.value as 'A4' | 'TICKET')}><option value="A4">A4</option><option value="TICKET">Ticket</option></select></label>
      {error && <p className="error-text" role="alert">{error}</p>}
      <Button type="submit" disabled={busy}>{busy ? 'Encolando...' : 'Solicitar envío'}</Button>
    </form>
    {requestId && <div className="notification-status"><div className="section-heading"><div><h3>Estado del envío</h3><p>Solicitud {requestId}</p></div><Button type="button" variant="secondary" onClick={() => void notificationQuery.refetch()}>Consultar estado</Button></div>
      {notificationQuery.isLoading ? <p>Consultando estado...</p> : notificationQuery.isError ? <p className="error-text">{notificationQuery.error.message}</p> : notificationQuery.data && <p role="status"><strong>{statusName[notificationQuery.data.status] ?? notificationQuery.data.status}</strong> · {notificationQuery.data.attemptCount} intentos{notificationQuery.data.lastError ? ` · ${notificationQuery.data.lastError}` : ''}</p>}
    </div>}</Panel>}
  </>
}
