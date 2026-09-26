import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiGetBlob, apiPost, ApiError } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Panel } from '../../shared/components/Panel'

type DeliveryPayment = { method: 'CASH' | 'BANK_TRANSFER'; amount: string }
type NotificationStatus = { requestId: string; status: string; attemptCount: number; requestedAt: string; sentAt?: string | null; lastError?: string | null }
type Props = { orderId: string; orderNumber: string; orderStatus: string; saleBalance: number; onChanged: () => void }

const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)
const statusName: Record<string, string> = { QUEUED: 'En cola', SENDING: 'Enviando', SENT: 'Enviado', FAILED: 'Falló', RETRY_EXHAUSTED: 'Reintentos agotados' }
const createKey = () => globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`

function apiMessage(cause: unknown, fallback: string) {
  if (cause instanceof ApiError && cause.status === 403) return 'Tu usuario no tiene permiso para realizar esta acción.'
  if (cause instanceof ApiError && cause.status === 404) return 'El pedido ya no está disponible.'
  if (cause instanceof ApiError && cause.status === 409) return cause.detail || 'El estado del pedido no permite esta acción.'
  return cause instanceof Error ? cause.message : fallback
}

export default function OrderLifecycleActions({ orderId, orderNumber, orderStatus, saleBalance, onChanged }: Props) {
  const queryClient = useQueryClient()
  const canDeliver = hasAuthority('SALE_DELIVER') || hasAuthority('ADMIN_ALL')
  const canCancel = hasAuthority('ADMIN_ALL')
  const canNotify = hasAuthority('ORDER_CREATE') || hasAuthority('ADMIN_ALL')
  const [showDelivery, setShowDelivery] = useState(false)
  const [deliveryResult, setDeliveryResult] = useState<'DELIVERED' | 'FAILED'>('DELIVERED')
  const [observation, setObservation] = useState('')
  const [payments, setPayments] = useState<DeliveryPayment[]>([])
  const [transferReference, setTransferReference] = useState('')
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
  const notificationQuery = useQuery({
    queryKey: ['order-notification-status', orderId, requestId],
    queryFn: () => apiGet<NotificationStatus>(`/api/orders/${orderId}/notifications/${requestId}`),
    enabled: Boolean(requestId),
    retry: false,
  })

  async function submitDelivery(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if (deliveryResult === 'FAILED' && !observation.trim()) { setError('Agregá una observación para un intento fallido.'); return }
    if (deliveryResult === 'FAILED' && payments.length) { setError('Un intento fallido no puede incluir cobros.'); return }
    if (transferReference.trim() && !payments.some((payment) => payment.method === 'BANK_TRANSFER')) { setError('La referencia requiere al menos un cobro por transferencia.'); return }
    const amount = payments.reduce((sum, payment) => sum + Number(payment.amount), 0)
    if (payments.some((payment) => !payment.amount.trim() || !Number.isFinite(Number(payment.amount)) || Number(payment.amount) <= 0)) { setError('Cada cobro debe ser mayor a cero.'); return }
    if (amount > saleBalance) { setError(`El cobro supera el saldo pendiente de ${money(saleBalance)}.`); return }
    setBusy(true)
    setError('')
    try {
      await apiPost(`/api/orders/${orderId}/delivery-attempts`, {
        result: deliveryResult,
        observation: observation.trim() || null,
        ...(payments.length ? { payments: payments.map((payment) => ({ method: payment.method, amount: Number(payment.amount) })) } : {}),
        ...(transferReference.trim() ? { transferReference: transferReference.trim() } : {}),
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: [`/api/orders/${orderId}`] }),
        queryClient.invalidateQueries({ queryKey: ['/api/orders'] }),
        queryClient.invalidateQueries({ queryKey: ['/api/sales'] }),
        queryClient.invalidateQueries({ queryKey: ['/api/payments'] }),
      ])
      setShowDelivery(false)
      setFeedback(deliveryResult === 'DELIVERED' ? 'Entrega registrada.' : 'Intento fallido registrado.')
      onChanged()
    } catch (cause) {
      setError(apiMessage(cause, 'No se pudo registrar el intento de entrega.'))
    } finally {
      setBusy(false)
    }
  }

  async function cancelOrder() {
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
        {canDeliver && orderStatus === 'CONFIRMED' && <Button onClick={() => { setShowDelivery(true); setError('') }}>Registrar entrega</Button>}
        {canCancel && orderStatus === 'CONFIRMED' && <Button variant="secondary" onClick={() => { setShowCancel(true); setError('') }}>Cancelar pedido</Button>}
        {canCancel && orderStatus === 'CANCELLED' && <Button onClick={() => { setShowReactivate(true); setError('') }}>Reactivar pedido</Button>}
        {canNotify && <><Button variant="secondary" onClick={() => void downloadDocument('a4')}>Descargar A4</Button><Button variant="secondary" onClick={() => void downloadDocument('ticket')}>Descargar ticket</Button><Button variant="secondary" onClick={() => { setShowNotification((value) => !value); setError('') }}>Compartir comprobante</Button></>}
      </div>
    </Panel>
    {showDelivery && <div role="dialog" aria-modal="true" aria-labelledby="delivery-title" className="modal-backdrop"><Panel title="Registrar intento de entrega"><form className="form-grid" onSubmit={submitDelivery}>
      <h2 id="delivery-title">Pedido {orderNumber}</h2>
      <label className="field"><span>Resultado de entrega</span><select className="select" value={deliveryResult} onChange={(event) => { setDeliveryResult(event.target.value as 'DELIVERED' | 'FAILED'); setError('') }} disabled={busy}><option value="DELIVERED">Entregado</option><option value="FAILED">No entregado</option></select></label>
      <label className="field"><span>Observación</span><textarea className="input textarea" value={observation} onChange={(event) => setObservation(event.target.value)} maxLength={2000} disabled={busy} /></label>
      {deliveryResult === 'DELIVERED' && <>
        <h3>Cobros recibidos (opcional)</h3>
        {payments.map((payment, index) => <div className="payment-entry" key={index}>
          <label className="field"><span>Medio de cobro</span><select className="select" value={payment.method} onChange={(event) => setPayments((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, method: event.target.value as DeliveryPayment['method'] } : item))}><option value="CASH">Efectivo</option><option value="BANK_TRANSFER">Transferencia</option></select></label>
          <label className="field"><span>Importe cobrado</span><input className="input" aria-label="Importe cobrado" type="text" inputMode="decimal" value={payment.amount} onChange={(event) => setPayments((current) => current.map((item, itemIndex) => itemIndex === index ? { ...item, amount: event.target.value } : item))} /></label>
          {payments.length > 1 && <Button type="button" variant="link" onClick={() => setPayments((current) => current.filter((_item, itemIndex) => itemIndex !== index))}>Quitar cobro</Button>}
        </div>)}
        <Button type="button" variant="secondary" onClick={() => setPayments((current) => [...current, { method: 'CASH', amount: '' }])}>Agregar cobro</Button>
        {payments.some((payment) => payment.method === 'BANK_TRANSFER') && <label className="field"><span>Referencia de transferencia</span><input className="input" aria-label="Referencia de transferencia" value={transferReference} onChange={(event) => setTransferReference(event.target.value)} maxLength={100} /></label>}
        <p className="helper-text">Saldo pendiente: {money(saleBalance)}. Los cobros no pueden superar este importe.</p>
      </>}
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions"><Button type="button" variant="secondary" onClick={() => setShowDelivery(false)} disabled={busy}>Cancelar</Button><Button type="submit" disabled={busy}>{busy ? 'Guardando...' : 'Confirmar intento'}</Button></div>
    </form></Panel></div>}
    {showCancel && <div role="dialog" aria-modal="true" aria-labelledby="cancel-title" className="modal-backdrop"><Panel title="Cancelar pedido"><h2 id="cancel-title">¿Cancelar el pedido {orderNumber}?</h2><p>El backend revertirá el stock si el pedido cumple las condiciones de cancelación.</p>{error && <p className="error-text" role="alert">{error}</p>}<div className="page-actions"><Button variant="secondary" onClick={() => setShowCancel(false)} disabled={busy}>Volver</Button><Button onClick={cancelOrder} disabled={busy}>{busy ? 'Cancelando...' : 'Confirmar cancelación'}</Button></div></Panel></div>}
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
