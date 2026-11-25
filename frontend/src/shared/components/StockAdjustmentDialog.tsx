import { trapDialogFocus } from '../dialogFocus'
import { useEffect, useId, useRef, type FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { useDiscardChanges } from '../useDiscardChanges'
import { formatNumber } from '../format'
import { lockBodyScroll } from '../lockBodyScroll'
import { Button } from './Button'

/** `target` replaces the current stock with the counted quantity; `delta` adds or subtracts a signed amount. */
export type StockAdjustmentMode = 'target' | 'delta'

type Props = {
  title?: string
  productName: string
  stock: number
  mode: StockAdjustmentMode
  quantity: string
  reason: string
  pending: boolean
  error: string
  onModeChange: (mode: StockAdjustmentMode) => void
  onQuantityChange: (value: string) => void
  onReasonChange: (value: string) => void
  onSubmit: (event: FormEvent) => void
  onCancel: () => void
}

const units = { format: (value: number) => formatNumber(value, 2) }
const parseQuantity = (value: string) => value.trim() ? Number(value.trim().replace(',', '.')) : NaN

/** Validates the draft and builds the request body for `POST /api/inventory/{productId}/adjustments`. */
export function buildStockAdjustment(mode: StockAdjustmentMode, quantity: string, reason: string, stock: number):
  { body: { quantity?: number; targetQuantity?: number; reason?: string } } | { error: string } {
  const amount = parseQuantity(quantity)
  if (!Number.isFinite(amount)) return { error: mode === 'target' ? 'Ingresá la cantidad final.' : 'Ingresá una cantidad distinta de cero.' }
  if (!Number.isInteger(amount * 2)) return { error: 'La cantidad debe ser múltiplo de 0,5.' }
  if (mode === 'delta' && amount === 0) return { error: 'Ingresá una cantidad distinta de cero.' }
  if (mode === 'target' && amount === stock) return { error: 'El stock ya tiene esa cantidad.' }
  const trimmedReason = reason.trim()
  return { body: { ...(mode === 'target' ? { targetQuantity: amount } : { quantity: amount }), ...(trimmedReason ? { reason: trimmedReason } : {}) } }
}

/** Mount only while a product adjustment is open. */
export function StockAdjustmentDialog({ title = 'Ajustar stock', productName, stock, mode, quantity, reason, pending, error, onModeChange, onQuantityChange, onReasonChange, onSubmit, onCancel }: Props) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const quantityInput = useRef<HTMLInputElement>(null)
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: Boolean(quantity || reason), onDiscard: onCancel, disabled: pending })
  const amount = parseQuantity(quantity)
  const validAmount = Number.isFinite(amount) && Number.isInteger(amount * 2) && (mode === 'target' || amount !== 0)
  const projectedStock = validAmount ? (mode === 'target' ? amount : stock + amount) : null
  const difference = projectedStock === null ? null : projectedStock - stock

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
    <dialog ref={dialog} className="confirmation-dialog stock-adjustment-dialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-product`} aria-busy={pending} onCancel={(event) => { event.preventDefault(); requestDiscard() }} onKeyDown={trapDialogFocus}>
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
          <fieldset className="stock-adjustment-mode" disabled={pending}>
            <legend>Tipo de ajuste</legend>
            <label className="radio-row"><input type="radio" name={`${id}-mode`} value="target" checked={mode === 'target'} onChange={() => { onModeChange('target'); quantityInput.current?.focus() }} />Indicar cantidad final</label>
            <label className="radio-row"><input type="radio" name={`${id}-mode`} value="delta" checked={mode === 'delta'} onChange={() => { onModeChange('delta'); quantityInput.current?.focus() }} />Sumar o restar</label>
          </fieldset>
          <div className="stock-adjustment-fields">
            <div className="field">
              <label htmlFor={`${id}-quantity`}>{mode === 'target' ? 'Cantidad final' : 'Cantidad de ajuste'}</label>
              <input ref={quantityInput} id={`${id}-quantity`} className="input" type="text" inputMode="decimal" value={quantity} onChange={(event) => onQuantityChange(event.target.value)} aria-describedby={`${id}-quantity-help${error ? ` ${id}-error` : ''}`} required disabled={pending} placeholder={mode === 'target' ? 'Ej.: 24' : 'Ej.: +10 o -0.5'} />
              <p id={`${id}-quantity-help`} className="helper-text">{mode === 'target'
                ? <>Reemplaza el stock actual por la cantidad indicada. {difference !== null && difference !== 0 ? `Diferencia: ${difference > 0 ? '+' : ''}${units.format(difference)}. ` : ''}Solo múltiplos de 0,5.</>
                : 'Un valor positivo suma stock y uno negativo lo descuenta. Solo múltiplos de 0,5.'}</p>
            </div>
            <div className="field">
              <label htmlFor={`${id}-reason`}>Motivo (opcional)</label>
              <textarea id={`${id}-reason`} className="input textarea" value={reason} onChange={(event) => onReasonChange(event.target.value)} maxLength={500} disabled={pending} placeholder="Ej.: corrección de conteo o mercadería dañada" />
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
