import { CurrencyText } from '../../shared/components/CurrencyText'
import { Button } from '../../shared/components/Button'
import { formatMoney } from '../../shared/format'

type Props = {
  isEdit: boolean
  canDiscount: boolean
  submitting: boolean
  orderDiscountPercent: string
  orderDiscountValue: number
  invalidOrderDiscount: boolean
  invalidQuantityCount: number
  previewSubtotal: number
  previewDiscount: number
  previewTotal: number
  selectedPreviousBalance: number
  confirmDisabled: boolean
  confirmLabel: string
  draftStorageKey?: string
  hasUnsavedDraft: boolean
  draftIsSaved: boolean
  draftSaveError: string
  onChangeOrderDiscount: (value: string) => void
  onStepOrderDiscount: (step: 1 | -1) => void
  onSaveDraft: () => void
}

export function OrderCheckoutSummary({
  isEdit,
  canDiscount,
  submitting,
  orderDiscountPercent,
  orderDiscountValue,
  invalidOrderDiscount,
  invalidQuantityCount,
  previewSubtotal,
  previewDiscount,
  previewTotal,
  selectedPreviousBalance,
  confirmDisabled,
  confirmLabel,
  draftStorageKey,
  hasUnsavedDraft,
  draftIsSaved,
  draftSaveError,
  onChangeOrderDiscount,
  onStepOrderDiscount,
  onSaveDraft,
}: Props) {
  const discountField = <>
    <div className="field">
      <label htmlFor="order-discount">Descuento general</label>
      <div className="order-discount-input">
        <div className="order-discount-steps">
          <Button variant="secondary" type="button" aria-label="Aumentar descuento general" disabled={submitting || invalidOrderDiscount || orderDiscountValue >= 100} onClick={() => onStepOrderDiscount(1)}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="m6 15 6-6 6 6" /></svg>
          </Button>
          <Button variant="secondary" type="button" aria-label="Disminuir descuento general" disabled={submitting || invalidOrderDiscount || orderDiscountValue <= 0} onClick={() => onStepOrderDiscount(-1)}>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
          </Button>
        </div>
        <input id="order-discount" className="input" type="text" inputMode="decimal" aria-label="Descuento general (%)" disabled={submitting} aria-invalid={invalidOrderDiscount} aria-describedby={invalidOrderDiscount ? 'order-discount-error' : 'order-discount-hint'} value={orderDiscountPercent}
          onFocus={(event) => event.currentTarget.select()} onClick={(event) => event.currentTarget.select()}
          onKeyDown={(event) => {
            if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
              event.preventDefault()
              onStepOrderDiscount(event.key === 'ArrowUp' ? 1 : -1)
            }
          }} onChange={(event) => onChangeOrderDiscount(event.target.value)} />
        <span className="order-discount-unit" aria-hidden="true">%</span>
      </div>
    </div>
    {invalidOrderDiscount ? <p id="order-discount-error" className="error-text" role="alert">Ingresá un descuento entre 0 y 100%.</p> : <p id="order-discount-hint" className="helper-text">De 0 a 100% sobre el subtotal.</p>}
  </>

  const confirmButton = <Button type="submit" fullWidth disabled={confirmDisabled}>{confirmLabel}</Button>
  const totalBlock = <dl className="order-checkout-total">
    <dt>{selectedPreviousBalance > 0 ? 'Total a cobrar' : 'Total estimado'}</dt>
    <dd className="total-value"><CurrencyText>{invalidQuantityCount ? '—' : formatMoney(previewTotal + selectedPreviousBalance, 2)}</CurrencyText></dd>
  </dl>

  if (isEdit) {
    return <div className={`order-checkout${canDiscount ? '' : ' order-checkout-standard'}`}>
      {canDiscount && <div className="order-checkout-discount">{discountField}</div>}
      <dl className="order-totals">
        <dt>Subtotal</dt><dd><CurrencyText>{invalidQuantityCount ? '—' : formatMoney(previewSubtotal, 2)}</CurrencyText></dd>
        <dt>Descuento general</dt><dd className={previewDiscount > 0 ? 'order-discount-value' : undefined}>{previewDiscount > 0 ? '− ' : ''}<CurrencyText>{formatMoney(previewDiscount, 2)}</CurrencyText></dd>
        {selectedPreviousBalance > 0 && <><dt>Total del pedido</dt><dd><CurrencyText>{invalidQuantityCount ? '—' : formatMoney(previewTotal, 2)}</CurrencyText></dd><dt>Saldo anterior</dt><dd><CurrencyText>{formatMoney(selectedPreviousBalance, 2)}</CurrencyText></dd></>}
      </dl>
      <div className="order-checkout-actions">{totalBlock}{confirmButton}</div>
    </div>
  }

  return <aside className="panel order-create-panel order-create-summary" aria-labelledby="order-summary-title">
    <h2 id="order-summary-title">Resumen del pedido</h2>
    <dl className="order-totals"><dt>Subtotal</dt><dd>{invalidQuantityCount ? '—' : formatMoney(previewSubtotal, 2)}</dd></dl>
    {canDiscount && <div className="order-create-discount">
      {discountField}
      {!invalidOrderDiscount && <span className="order-create-discount-value">{previewDiscount > 0 ? '− ' : ''}{formatMoney(previewDiscount, 2)}</span>}
    </div>}
    {selectedPreviousBalance > 0 && <dl className="order-totals">
      <dt>Total del pedido</dt><dd>{invalidQuantityCount ? '—' : formatMoney(previewTotal, 2)}</dd>
      <dt>Saldo anterior</dt><dd>{formatMoney(selectedPreviousBalance, 2)}</dd>
    </dl>}
    {totalBlock}
    {confirmButton}
    <Button type="button" variant="secondary" fullWidth onClick={onSaveDraft} disabled={submitting || !hasUnsavedDraft || !draftStorageKey}>Guardar borrador</Button>
    {draftSaveError && <p className="error-text" role="alert">{draftSaveError}</p>}
    <div role="status" className="order-create-save-status">{draftIsSaved && 'Borrador guardado en este navegador.'}</div>
  </aside>
}
