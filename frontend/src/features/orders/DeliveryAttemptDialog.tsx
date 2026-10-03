import { useEffect, useId, useRef, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { Button } from '../../shared/components/Button'

export type DeliveryAttemptDraft = {
  result: 'DELIVERED' | 'FAILED'
  observation: string
  payments: Array<{ method: 'CASH' | 'BANK_TRANSFER'; amount: string }>
  transferReference: string
  includePreviousDebt: boolean
  previousDebtAmount: string
}

type Props = {
  orderNumber: string
  saleBalance: number
  previousDebtAvailable?: number
  value: DeliveryAttemptDraft
  pending: boolean
  error: string
  onChange: (value: DeliveryAttemptDraft) => void
  onSubmit: (event: FormEvent) => void
  onClose: () => void
  mode?: 'attempt' | 'delivery'
  returnFocusId?: string
}

const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)
export const deliveryAmount = (value: string) => /^\d+(?:[.,]\d{1,4})?$/.test(value.trim()) ? Number(value.trim().replace(',', '.')) : NaN

export function DeliveryAttemptDialog({ orderNumber, saleBalance, previousDebtAvailable = 0, value, pending, error, onChange, onSubmit, onClose, mode = 'attempt', returnFocusId }: Props) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const result = useRef<HTMLSelectElement>(null)
  const collected = value.payments.reduce((sum, payment) => {
    const amount = deliveryAmount(payment.amount)
    return sum + (Number.isFinite(amount) && amount > 0 ? amount : 0)
  }, 0)
  const firstTransfer = value.payments.findIndex(payment => payment.method === 'BANK_TRANSFER')
  const previousAmount = value.includePreviousDebt ? deliveryAmount(value.previousDebtAmount) : 0
  const selectedPrevious = Number.isFinite(previousAmount) && previousAmount > 0 ? previousAmount : 0
  const totalDue = saleBalance + selectedPrevious

  useEffect(() => {
    const element = dialog.current!
    const trigger = returnFocusId ? document.getElementById(returnFocusId) : document.activeElement instanceof HTMLElement ? document.activeElement : null
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    element.showModal()
    const firstField = result.current ?? element.querySelector<HTMLElement>('input, select, textarea, .delivery-attempt-add button')
    firstField?.focus()
    return () => {
      element.close()
      document.body.style.overflow = previousOverflow
      if (trigger?.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [returnFocusId])

  function updatePayments(payments: DeliveryAttemptDraft['payments']) {
    onChange({ ...value, payments, transferReference: payments.some(payment => payment.method === 'BANK_TRANSFER') ? value.transferReference : '' })
  }

  function fillPayment(amount: number) {
    updatePayments([{ method: value.payments[0]?.method ?? 'CASH', amount: String(Number(amount.toFixed(4))).replace('.', ',') }])
  }

  return createPortal(
    <dialog ref={dialog} className="delivery-attempt-dialog" aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`} aria-modal="true" aria-busy={pending} onCancel={event => { event.preventDefault(); if (!pending) onClose() }}>
      <header className="delivery-attempt-header">
        <div><h2 id={`${id}-title`}>{mode === 'delivery' ? 'Confirmar entrega' : 'Registrar intento de entrega'}</h2><p id={`${id}-description`}>Pedido <strong>{orderNumber}</strong></p></div>
        <button className="delivery-attempt-close" type="button" aria-label="Cerrar registro de entrega" onClick={onClose} disabled={pending}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg></button>
      </header>
      <form className="delivery-attempt-form" onSubmit={onSubmit}>
        <div className="delivery-attempt-body">
          <div className="delivery-attempt-fields">
            {mode !== 'delivery' && <label className="field"><span>Resultado de entrega</span><select ref={result} className="select" value={value.result} onChange={event => onChange({ ...value, result: event.target.value as DeliveryAttemptDraft['result'], observation: '', ...(event.target.value === 'FAILED' ? { payments: [], transferReference: '', includePreviousDebt: false, previousDebtAmount: '' } : {}) })} disabled={pending}><option value="DELIVERED">Entregado</option><option value="FAILED">No entregado</option></select></label>}
            {value.result === 'FAILED' && <label className="field"><span>Observación</span><textarea className="input textarea" aria-label="Observación" value={value.observation} onChange={event => onChange({ ...value, observation: event.target.value })} aria-describedby={`${id}-observation-help`} aria-required="true" aria-invalid={Boolean(error) && !value.observation.trim()} maxLength={2000} disabled={pending} /></label>}
          </div>
          {value.result === 'FAILED' && <p className="helper-text" id={`${id}-observation-help`}>Indica el motivo por el que no se pudo entregar el pedido.</p>}
          {value.result === 'DELIVERED' ? <section className="delivery-attempt-collections" aria-labelledby={`${id}-collections-title`}>
            <div className="delivery-attempt-collections-header"><h3 id={`${id}-collections-title`}>Pago</h3><div className="delivery-attempt-balance"><span>Saldo pendiente de esta venta</span><strong>{money(saleBalance)}</strong></div></div>
            {(previousDebtAvailable > 0 || value.includePreviousDebt) && <div className="delivery-attempt-previous">
              <label className="radio-row"><input type="checkbox" checked={value.includePreviousDebt} disabled={pending} onChange={event => onChange({ ...value, includePreviousDebt: event.target.checked, previousDebtAmount: event.target.checked ? value.previousDebtAmount || String(previousDebtAvailable).replace('.', ',') : '' })} />Agregar deuda anterior</label>
              {value.includePreviousDebt && <label className="field"><span>Importe de deuda anterior</span><input className="input" inputMode="decimal" aria-label="Importe de deuda anterior" value={value.previousDebtAmount} aria-describedby={`${id}-previous-help`} disabled={pending} onChange={event => onChange({ ...value, previousDebtAmount: event.target.value })} /><span className="helper-text" id={`${id}-previous-help`}>Disponible: {money(previousDebtAvailable)}</span></label>}
            </div>}
            <div className="delivery-attempt-quick-pay"><Button type="button" variant="secondary" disabled={pending || totalDue <= 0} onClick={() => fillPayment(totalDue)}>Pagar total</Button>{saleBalance > 0 && selectedPrevious > 0 && <Button type="button" variant="link" disabled={pending} onClick={() => fillPayment(saleBalance)}>Pagar solo esta venta</Button>}</div>
            {value.payments.length === 0 && saleBalance <= 0 && <p className="delivery-attempt-empty">Esta venta no tiene saldo pendiente.</p>}
            <div className="delivery-attempt-payments">
              {value.payments.map((payment, index) => <div className="delivery-attempt-payment" key={index}>
                <label className="field"><span>Medio de pago</span><select className="select" value={payment.method} disabled={pending} onChange={event => updatePayments(value.payments.map((item, itemIndex) => itemIndex === index ? { ...item, method: event.target.value as typeof payment.method } : item))}><option value="CASH">Efectivo</option><option value="BANK_TRANSFER">Transferencia</option></select></label>
                <label className="field"><span>Importe del pago</span><input className="input" type="text" inputMode="decimal" value={payment.amount} aria-describedby={`${id}-payment-help`} disabled={pending} onChange={event => updatePayments(value.payments.map((item, itemIndex) => itemIndex === index ? { ...item, amount: event.target.value } : item))} /></label>
                <Button type="button" variant="link" disabled={pending} aria-label={`Quitar pago ${index + 1}`} onClick={() => updatePayments(value.payments.filter((_item, itemIndex) => itemIndex !== index))}>Quitar</Button>
                {index === firstTransfer && <label className="field delivery-attempt-reference"><span>Número de transferencia</span><input className="input" value={value.transferReference} onChange={event => onChange({ ...value, transferReference: event.target.value })} aria-required="true" maxLength={100} disabled={pending} /></label>}
              </div>)}
            </div>
            <div className="delivery-attempt-add"><Button type="button" variant="secondary" disabled={pending || totalDue <= 0} onClick={() => updatePayments([...value.payments, { method: 'CASH', amount: '' }])}>Agregar pago</Button></div>
            <p className="helper-text" id={`${id}-payment-help`}>El pago se aplica primero a esta venta y luego a la deuda anterior seleccionada.</p>
            <dl className="delivery-attempt-summary" aria-live="polite"><div><dt>Total a pagar</dt><dd>{money(totalDue)}</dd></div><div><dt>Saldo después del pago</dt><dd className={collected > totalDue ? 'text-danger' : ''}>{money(totalDue - collected)}</dd></div></dl>
          </section> : <p className="delivery-attempt-empty">Los intentos no entregados se registran sin pagos.</p>}
          {error && <p className="delivery-attempt-error error-text" role="alert">{error}</p>}
        </div>
        <footer className="delivery-attempt-footer"><Button type="button" variant="secondary" onClick={onClose} disabled={pending}>Cancelar</Button><Button type="submit" disabled={pending}>{pending ? 'Guardando...' : mode === 'delivery' ? 'Confirmar entrega' : 'Confirmar intento'}</Button></footer>
      </form>
    </dialog>, document.body,
  )
}
