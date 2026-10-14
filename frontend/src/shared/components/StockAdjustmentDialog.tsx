import { useEffect, useId, useRef, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { useDiscardChanges } from '../useDiscardChanges'
import { lockBodyScroll } from '../lockBodyScroll'
import { Button } from './Button'

type Props = {
  title?: string
  productName: string
  stock: number
  quantity: string
  reason: string
  pending: boolean
  error: string
  onQuantityChange: (value: string) => void
  onReasonChange: (value: string) => void
  onSubmit: (event: FormEvent) => void
  onCancel: () => void
}

const units = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 })

/** Mount only while a product adjustment is open. */
export function StockAdjustmentDialog({ title = 'Ajustar stock', productName, stock, quantity, reason, pending, error, onQuantityChange, onReasonChange, onSubmit, onCancel }: Props) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const quantityInput = useRef<HTMLInputElement>(null)
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: Boolean(quantity || reason), onDiscard: onCancel, disabled: pending })
  const amount = Number(quantity)
  const validAmount = Boolean(quantity.trim()) && Number.isFinite(amount) && amount !== 0 && Number.isInteger(amount * 2)
  const projectedStock = validAmount ? stock + amount : null

  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const releaseScroll = lockBodyScroll()
    element.showModal()
    quantityInput.current?.focus()
    return () => {
      element.close()
      releaseScroll()
      if (trigger?.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])

  return <>{createPortal(
    <dialog ref={dialog} className="confirmation-dialog stock-adjustment-dialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-product`} aria-busy={pending} onCancel={(event) => { event.preventDefault(); requestDiscard() }} onKeyDown={(event) => {
      if (event.key !== 'Tab') return
      const controls = event.currentTarget.querySelectorAll<HTMLElement>('input:not([disabled]), textarea:not([disabled]), button:not([disabled])')
      const first = controls[0]
      const last = controls[controls.length - 1]
      if (!first) event.preventDefault()
      else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }}>
      <header className="confirmation-dialog-header">
        <h2 id={`${id}-title`}>{title}</h2>
        <p id={`${id}-product`} className="stock-adjustment-product">{productName}</p>
      </header>
      <form onSubmit={onSubmit} aria-describedby={error ? `${id}-error` : undefined}>
        <div className="confirmation-dialog-body stock-adjustment-body">
          <dl className="stock-adjustment-summary">
            <div><dt>Stock actual</dt><dd>{units.format(stock)}</dd></div>
            <div><dt id={`${id}-result-label`}>Stock resultante</dt><dd><output aria-labelledby={`${id}-result-label`} aria-live="polite" className={projectedStock !== null && projectedStock < 0 ? 'text-danger' : ''}>{projectedStock === null ? '—' : units.format(projectedStock)}</output></dd></div>
          </dl>
          <div className="stock-adjustment-fields">
            <div className="field">
              <label htmlFor={`${id}-quantity`}>Cantidad de ajuste</label>
              <input ref={quantityInput} id={`${id}-quantity`} className="input" type="text" inputMode="decimal" value={quantity} onChange={(event) => onQuantityChange(event.target.value)} aria-describedby={`${id}-quantity-help${error ? ` ${id}-error` : ''}`} required disabled={pending} placeholder="Ej.: +10 o -0.5" />
              <p id={`${id}-quantity-help`} className="helper-text">Un valor positivo suma stock y uno negativo lo descuenta. Solo múltiplos de 0,5.</p>
            </div>
            <div className="field">
              <label htmlFor={`${id}-reason`}>Motivo</label>
              <textarea id={`${id}-reason`} className="input textarea" value={reason} onChange={(event) => onReasonChange(event.target.value)} required maxLength={500} disabled={pending} placeholder="Ej.: corrección de conteo o mercadería dañada" />
            </div>
          </div>
          {projectedStock !== null && projectedStock < 0 && <p className="warning-text stock-adjustment-feedback" role="status">Advertencia: el saldo quedará negativo ({units.format(projectedStock)}).</p>}
          {error && <p id={`${id}-error`} className="error-text stock-adjustment-feedback" role="alert">{error}</p>}
        </div>
        <footer className="confirmation-dialog-footer">
          <Button variant="secondary" type="button" onClick={requestDiscard} disabled={pending}>Cancelar</Button>
          <Button type="submit" disabled={pending}>{pending ? 'Guardando...' : 'Confirmar ajuste'}</Button>
        </footer>
      </form>
    </dialog>, document.body,
  )}{discardDialog}</>
}
