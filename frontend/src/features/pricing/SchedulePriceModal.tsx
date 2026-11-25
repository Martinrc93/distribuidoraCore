import type { FormEvent } from 'react'
import { Button } from '../../shared/components/Button'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { OrderDateFilter } from '../orders/OrderDateFilter'
import type { ProductPrice } from './pricingTypes'

type Props = {
  editingProduct: ProductPrice
  listName: string
  defaultListName: string
  isDefaultList: boolean
  showFollowState: boolean
  price: string
  effectiveOn: string
  isoEffectiveOn: string | null
  savingPrice: boolean
  error: string
  onChangePrice: (value: string) => void
  onChangeEffectiveOn: (value: string) => void
  onSubmit: (event: FormEvent) => void
  onClose: () => void
}

export function SchedulePriceModal({
  editingProduct,
  listName,
  defaultListName,
  isDefaultList,
  showFollowState,
  price,
  effectiveOn,
  isoEffectiveOn,
  savingPrice,
  error,
  onChangePrice,
  onChangeEffectiveOn,
  onSubmit,
  onClose,
}: Props) {
  const invalidDate = isoEffectiveOn === null
  const describedBy = invalidDate
    ? 'scheduled-price-date-error scheduled-price-date-help'
    : 'scheduled-price-date-help'

  return <FormModal size="compact" label="Programar precio" busy={savingPrice} onClose={onClose}>
    <Panel title="Programar precio" description={`${editingProduct.name} · ${listName}`}>
      <form className="grid grid-cols-1 gap-[14px]" onSubmit={onSubmit}>
        <label className="field">
          <span>Precio de {editingProduct.name}</span>
          <input className="input currency-input w-full min-w-0" type="text" inputMode="decimal" value={price} onChange={(event) => onChangePrice(event.target.value)} required readOnly={savingPrice} />
        </label>
        <div className="grid gap-[6px]">
          <OrderDateFilter
            id="scheduled-price-date"
            label="Fecha de vigencia"
            value={effectiveOn}
            isoValue={isoEffectiveOn ?? ''}
            invalid={invalidDate}
            describedBy={describedBy}
            disabled={savingPrice}
            onChange={onChangeEffectiveOn}
          />
          <small id="scheduled-price-date-help" className="text-muted text-[11px] font-normal leading-[1.5]">Dejalo vacío para aplicar el precio desde hoy.</small>
          {invalidDate && <p id="scheduled-price-date-error" className="error-text m-0" role="alert">Ingresá una fecha válida con formato dd/mm/aaaa.</p>}
        </div>
        {error && <p className="error-text m-0" role="alert"><CurrencyText>{error}</CurrencyText></p>}
        {showFollowState && editingProduct.followStatus && <p className="helper-text m-0">Este precio sigue a {defaultListName}. Al guardarlo pasa a ser propio y deja de seguir sus cambios.</p>}
        {isDefaultList && <p className="helper-text m-0">Si otras listas siguen este precio, se te va a avisar antes de guardar.</p>}
        <div className="page-actions justify-end [border-top:1px_solid_var(--line)] pt-[14px]">
          <Button variant="secondary" type="button" onClick={onClose} disabled={savingPrice}>Cancelar</Button>
          <Button type="submit" disabled={savingPrice || invalidDate}>{savingPrice ? 'Guardando...' : 'Guardar precio'}</Button>
        </div>
      </form>
    </Panel>
  </FormModal>
}
