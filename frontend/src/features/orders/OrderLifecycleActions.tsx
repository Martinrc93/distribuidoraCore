import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiGetBlob, apiPost, ApiError } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Panel } from '../../shared/components/Panel'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DeliveryAttemptDialog } from './DeliveryAttemptDialog'
import { useOrderDelivery } from './useOrderDelivery'
type NotificationStatus = { requestId: string; status: string; attemptCount: number; requestedAt: string; sentAt?: string | null; lastError?: string | null }
type Props = { orderId: string; orderNumber: string; orderStatus: string; saleBalance: number; previousDebtAvailable?: number; onChanged: () => void }

const statusName: Record<string, string> = { QUEUED: 'En cola', SENDING: 'Enviando', SENT: 'Enviado', FAILED: 'Falló', RETRY_EXHAUSTED: 'Reintentos agotados' }
const createKey = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`

function apiMessage(cause: unknown, fallback: string) {
  if (cause instanceof ApiError && cause.status === 403) return 'Tu usuario no tiene permiso para realizar esta acción.'
  if (cause instanceof ApiError && cause.status === 404) return 'El pedido ya no está disponible.'
  if (cause instanceof ApiError && cause.status === 409) return cause.detail || 'El estado del pedido no permite esta acción.'
  return cause instanceof Error ? cause.message : fallback
}

export default function OrderLifecycleActions({ orderId, orderNumber, orderStatus, saleBalance, previousDebtAvailable = 0, onChanged }: Props) {
  const queryClient = useQueryClient()
  const canDeliver = hasAuthority('SALE_DELIVER') || hasAuthority('ADMIN_ALL')
  const canCancel = hasAuthority('ADMIN_ALL')
  const canNotify = hasAuthority('ORDER_CREATE') || hasAuthority('ADMIN_ALL')
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
      ])
      setShowCancel(false)
      setFeedback('Pedido cancelado y stock revertido.')
      onChanged()
    } catch (cause) {
      setError(apiMessage(cause, 'No se pudo cancelar el pedido.'))
    } finally {
      setBusy(false)
    }
  }

  async function reactivateOrder() {
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
      setFeedback('Pedido reactivado y confirmado. Se volvió a descontar el stock y restaurar la deuda.')
      onChanged()
    } catch (cause) {
      setError(apiMessage(cause, 'No se pudo reactivar el pedido.'))
    } finally {
      setBusy(false)
    }
  }

  async function downloadDocument(type: 'a4' | 'ticket') {
    setError('')
    try {
      await apiGetBlob(`/api/orders/${orderId}/documents/${type}`, `${orderNumber}-${type}.pdf`)
      setFeedback(type === 'a4' ? 'Documento A4 descargado.' : 'Ticket descargado.')
    } catch (cause) {
      setError(apiMessage(cause, 'No se pudo descargar el documento.'))
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
        {canCancel && orderStatus === 'CANCELLED' && <Button onClick={() => { setShowReactivate(true); setError('') }}>Reactivar pedido</Button>}
        {canNotify && <><Button variant="secondary" onClick={() => void downloadDocument('a4')}>Descargar A4</Button><Button variant="secondary" onClick={() => void downloadDocument('ticket')}>Descargar ticket</Button><Button variant="secondary" onClick={() => { setShowNotification((value) => !value); setError('') }}>Compartir comprobante</Button></>}
      </div>
    </Panel>
    {showDelivery && <DeliveryAttemptDialog orderNumber={orderNumber} saleBalance={saleBalance} previousDebtAvailable={previousDebtAvailable} value={delivery.draft} pending={delivery.pending} error={delivery.error} onChange={delivery.changeDraft} onSubmit={delivery.submit} onClose={() => setShowDelivery(false)} />}
    {showCancel && <ConfirmationDialog title={`¿Cancelar el pedido ${orderNumber}?`} description="Se cancelará el pedido y se devolverán sus productos al stock si cumple las condiciones de cancelación." confirmLabel="Confirmar cancelación" pendingLabel="Cancelando..." pending={busy} error={error} onCancel={() => setShowCancel(false)} onConfirm={() => void cancelOrder()} />}
    {showReactivate && <div role="dialog" aria-modal="true" aria-labelledby="reactivate-title" className="modal-backdrop"><Panel title="Reactivar pedido"><h2 id="reactivate-title">¿Reactivar el pedido {orderNumber} como confirmado?</h2><p>Se volverá a descontar el stock de sus productos y se restaurará el saldo de la venta en la cuenta corriente.</p>{error && <p className="error-text" role="alert">{error}</p>}<div className="page-actions"><Button variant="secondary" onClick={() => setShowReactivate(false)} disabled={busy}>Volver</Button><Button onClick={reactivateOrder} disabled={busy}>{busy ? 'Reactivando...' : 'Confirmar reactivación'}</Button></div></Panel></div>}
    {showNotification && <Panel title="Compartir comprobante"><form className="form-grid" onSubmit={requestNotification}>
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
