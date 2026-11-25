import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { apiGet, apiPatch, apiPost, ApiError } from '../../shared/api/client'
import { createIdempotencyKey } from '../../shared/api/createIdempotencyKey'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { useDiscardChanges } from '../../shared/useDiscardChanges'

export type NotificationChannel = 'EMAIL' | 'WHATSAPP'
type Contact = { email: string | null; phone: string | null }
type Format = 'A4' | 'TICKET'
type Props = {
  orderId: string
  customerId?: string
  channel: NotificationChannel
  onClose: () => void
  onRequested: (request: { requestId: string; status: string }) => void
}

const newKey = createIdempotencyKey
const formatRecipient = (value: string, isEmail: boolean) => isEmail ? value.trim() : value.replace(/[\s().-]/g, '')
const isValid = (value: string, isEmail: boolean) => isEmail
  ? value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
  : /^\+[1-9]\d{7,14}$/.test(value)
const errorMessage = (cause: unknown, fallback: string) => cause instanceof ApiError ? cause.detail || fallback : fallback

export function OrderNotificationDialog({ orderId, customerId, channel, onClose, onRequested }: Props) {
  const isEmail = channel === 'EMAIL'
  const field = isEmail ? 'email' : 'phone'
  const channelName = isEmail ? 'email' : 'WhatsApp'
  const fieldLabel = isEmail ? 'Email del cliente' : 'Teléfono de WhatsApp'
  const queryClient = useQueryClient()
  const path = '/api/orders/' + orderId + '/notifications'
  const contactKey = queryKeys.orders.notificationContact(orderId)
  const contact = useQuery({ queryKey: contactKey, queryFn: ({ signal }) => apiGet<Contact>(path + '/contact', signal), retry: false })
  const stored = contact.data?.[field] ?? ''
  const missing = contact.isSuccess && !isValid(formatRecipient(stored, isEmail), isEmail)
  const title = missing ? (isEmail ? 'Cargar email' : 'Cargar teléfono') : 'Enviar por ' + channelName
  const canSave = missing && Boolean(customerId) && hasPermission('CUSTOMER_EDIT')
  const [draft, setDraft] = useState<string | null>(null)
  const [format, setFormat] = useState<Format>('A4')
  const [key, setKey] = useState(newKey)
  const [pending, setPending] = useState<'save' | 'send' | null>(null)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const recipient = draft ?? stored
  const input = useRef<HTMLInputElement>(null)
  const hintId = useId()
  const { requestDiscard, discardDialog } = useDiscardChanges({
    hasChanges: recipient !== stored || format !== 'A4', disabled: Boolean(pending), onDiscard: onClose,
  })

  useEffect(() => { if (contact.isSuccess) input.current?.focus() }, [contact.isSuccess, saved])

  async function apply(action: 'save' | 'send') {
    if (pending || !contact.isSuccess || (action === 'save' && !canSave)) return
    const value = formatRecipient(recipient, isEmail)
    if (!isValid(value, isEmail)) {
      setError(isEmail ? 'Ingrese un email válido.' : 'Ingrese el número con código de país y el signo +, por ejemplo +5491112345678.')
      input.current?.focus()
      return
    }
    setPending(action)
    setError('')
    try {
      if (action === 'save') {
        await apiPatch('/api/customers/' + customerId + '/contact', { [field]: value })
        queryClient.setQueryData<Contact>(contactKey, (previous) => previous && { ...previous, [field]: value })
        void invalidateDomains(queryClient, 'customers')
        setDraft(null)
        setSaved(true)
      } else {
        const result = await apiPost<{ requestId: string; status: string }>(path, { channel, recipient: value, format, idempotencyKey: key })
        onRequested(result)
      }
    } catch (cause) {
      setError(errorMessage(cause, action === 'save' ? 'No se pudo guardar el dato en la ficha.' : 'No se pudo solicitar el envío.'))
    } finally {
      setPending(null)
    }
  }

  function send(event: FormEvent) {
    event.preventDefault()
    void apply('send')
  }

  return <>
    <FormModal label={title} size="compact" busy={Boolean(pending)} onClose={requestDiscard}>
      <Panel title={title}>
        {contact.isPending && <p className="helper-text" role="status">Consultando el contacto del cliente…</p>}
        {contact.isError && <p className="error-text" role="alert">No se pudo consultar el contacto del cliente. <Button variant="link" onClick={() => void contact.refetch()}>Reintentar</Button></p>}
        {contact.isSuccess ? <form className="grid gap-[14px]" noValidate onSubmit={send}>
          <label className="field"><span>{fieldLabel}</span>
            <input ref={input} className="input" type={isEmail ? 'email' : 'tel'} value={recipient} disabled={Boolean(pending)}
              aria-describedby={!isEmail ? hintId : undefined} autoComplete={isEmail ? 'email' : 'tel'} required
              onChange={(event) => { setDraft(event.target.value); setError(''); setKey(newKey()) }} />
          </label>
          {!isEmail && <p className="helper-text m-0" id={hintId}>Use el código de país. Por ejemplo, +5491112345678.</p>}
          <label className="field"><span>Formato de comprobante</span>
            <select className="select" value={format} disabled={Boolean(pending)}
              onChange={(event) => { setFormat(event.target.value as Format); setError(''); setKey(newKey()) }}>
              <option value="A4">A4</option><option value="TICKET">Ticket</option>
            </select>
          </label>
          {error && <p className="error-text m-0" role="alert">{error}</p>}
          {saved && <p className="success-text m-0" role="status">Contacto guardado en la ficha del cliente.</p>}
          <div className="page-actions justify-end">
            <Button type="button" variant="secondary" onClick={requestDiscard} disabled={Boolean(pending)}>Cerrar</Button>
            {canSave && <Button type="button" variant="secondary" onClick={() => void apply('save')} disabled={Boolean(pending)}>
              {pending === 'save' ? 'Guardando…' : 'Guardar en ficha'}
            </Button>}
            <Button type="submit" disabled={Boolean(pending)}>{pending === 'send' ? 'Solicitando…' : 'Solicitar envío'}</Button>
          </div>
        </form> : <div className="page-actions justify-end"><Button variant="secondary" onClick={requestDiscard}>Cerrar</Button></div>}
      </Panel>
    </FormModal>
    {discardDialog}
  </>
}
