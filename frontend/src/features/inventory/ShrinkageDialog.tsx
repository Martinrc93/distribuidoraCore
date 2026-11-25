import { useId, useRef, useState, type FormEvent } from 'react'
import { apiPost, ApiError } from '../../shared/api/client'
import { createIdempotencyKey } from '../../shared/api/createIdempotencyKey'
import { Button } from '../../shared/components/Button'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { formatNumber } from '../../shared/format'
import { useDiscardChanges } from '../../shared/useDiscardChanges'

const units = { format: (value: number) => formatNumber(value, 2) }

type Props = {
  product: { id: string; name: string; stock?: number }
  onClose: () => void
  onSaved: () => Promise<void>
}

export function ShrinkageDialog({ product, onClose, onSaved }: Props) {
  const id = useId()
  const attempt = useRef<string | undefined>(undefined)
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const { requestDiscard, discardDialog } = useDiscardChanges({
    hasChanges: Boolean(quantity || reason), onDiscard: onClose, disabled: saving, protectUnload: true,
  })
  const stock = Number(product.stock ?? 0)
  const input = quantity.trim()
  const amount = /^\d+(?:[.,](?:0+|50*))?$/.test(input) ? Number(input.replace(',', '.')) : NaN
  const validAmount = amount > 0 && amount <= 999_999_999_999_999.5 && Number.isInteger(amount * 2)
  const projectedStock = validAmount ? stock - amount : null

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    if (!validAmount) { setError('Ingresá una cantidad positiva en múltiplos de 0,5.'); return }
    const description = reason.trim()
    if (!description) { setError('Ingresá el motivo de la merma.'); return }
    setSaving(true)
    setError('')
    try {
      attempt.current ??= createIdempotencyKey()
      await apiPost(`/api/inventory/${product.id}/shrinkages`, {
        idempotencyKey: attempt.current, quantity: amount, reason: description,
      })
      await onSaved()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        setError('No se pudo registrar la merma. Revisá el estado del producto y sus movimientos antes de volver a intentarlo.')
      } else {
        setError(cause instanceof Error ? cause.message : 'No se pudo registrar la merma.')
      }
    } finally {
      setSaving(false)
    }
  }

  return <>
    <FormModal label="Registrar merma" size="compact" busy={saving} onClose={requestDiscard}>
      <Panel title="Registrar merma">
        <form className="stock-adjustment-body" onSubmit={submit}>
          <p className="m-0 text-[13px] text-muted [overflow-wrap:anywhere]">{product.name}</p>
          <dl className="stock-adjustment-summary">
            <div><dt>Stock actual</dt><dd>{units.format(stock)}</dd></div>
            <div><dt id={`${id}-stock`}>Stock estimado</dt><dd>
              <output aria-labelledby={`${id}-stock`} className={projectedStock !== null && projectedStock < 0 ? 'text-danger' : ''}>
                {projectedStock === null ? '—' : units.format(projectedStock)}
              </output>
            </dd></div>
          </dl>
          <div className="stock-adjustment-fields">
            <div className="field">
              <label htmlFor={`${id}-quantity`}>Cantidad perdida</label>
              <input id={`${id}-quantity`} className="input" inputMode="decimal" required disabled={saving}
                value={quantity} onChange={(event) => { setQuantity(event.target.value); attempt.current = undefined; setError('') }}
                aria-describedby={`${id}-help`} />
              <p id={`${id}-help`} className="helper-text">Ingresá una cantidad positiva en múltiplos de 0,5.</p>
            </div>
            <div className="field">
              <label htmlFor={`${id}-reason`}>Motivo (obligatorio)</label>
              <textarea id={`${id}-reason`} className="input textarea" required maxLength={500} disabled={saving}
                value={reason} onChange={(event) => { setReason(event.target.value); attempt.current = undefined; setError('') }} />
            </div>
          </div>
          {projectedStock !== null && projectedStock < 0 && <p className="warning-text stock-adjustment-feedback" role="status">El saldo estimado quedará negativo ({units.format(projectedStock)}).</p>}
          {error && <p className="error-text stock-adjustment-feedback" role="alert">{error}</p>}
          <div className="page-actions justify-end">
            <Button type="button" variant="secondary" onClick={requestDiscard} disabled={saving}>Cancelar</Button>
            <Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Confirmar merma'}</Button>
          </div>
        </form>
      </Panel>
    </FormModal>
    {discardDialog}
  </>
}
