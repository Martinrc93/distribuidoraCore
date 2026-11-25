import type { KeyboardEvent, MouseEvent, RefObject } from 'react'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { SearchableSelect, type SearchableSelectHandle } from '../../shared/components/SearchableSelect'
import { formatNumber } from '../../shared/format'

type ProductOption = { id: string; name: string; status?: string; stock?: number }
type PriceResolution = {
  data?: { productId: string; unitPrice: number }
  isLoading: boolean
  isFetching: boolean
  isError: boolean
  error: unknown
  refetch: () => unknown
}

type Props = {
  isEdit: boolean
  canDiscount: boolean
  canOverride: boolean
  customerId: string
  hasSelectedList: boolean
  submitting: boolean
  products: ProductOption[]
  productsById: Map<string, ProductOption>
  lines: Array<{ productId: string }>
  lineColumns: TableColumn[]
  lineRows: Array<Record<string, string>>
  invalidQuantityLines: Array<{ productId: string }>
  selectedProductId: string
  selectedProductQuantity: string
  selectedProductDiscount: string
  selectedProductPriceOverride: string | undefined
  selectedPriceQuery: PriceResolution
  invalidSelectedQuantity: boolean
  productSearchRef: RefObject<SearchableSelectHandle | null>
  quantityInputRef: RefObject<HTMLInputElement | null>
  onSelectProduct: (id: string) => void
  onChangeQuantity: (value: string) => void
  onChangeDiscount: (value: string) => void
  onChangePriceOverride: (value: string) => void
  onAddButtonClick: (event: MouseEvent<HTMLButtonElement>) => void
  onAddFromKeyboard: (event: KeyboardEvent<HTMLInputElement>) => void
  onPickerKeyDown?: (event: KeyboardEvent<HTMLElement>) => void
  onBlurPicker: () => void
  onPointerDownPicker: () => void
}

export function OrderProductPicker({
  isEdit,
  canDiscount,
  canOverride,
  customerId,
  hasSelectedList,
  submitting,
  products,
  productsById,
  lines,
  lineColumns,
  lineRows,
  invalidQuantityLines,
  selectedProductId,
  selectedProductQuantity,
  selectedProductDiscount,
  selectedProductPriceOverride,
  selectedPriceQuery,
  invalidSelectedQuantity,
  productSearchRef,
  quantityInputRef,
  onSelectProduct,
  onChangeQuantity,
  onChangeDiscount,
  onChangePriceOverride,
  onAddButtonClick,
  onAddFromKeyboard,
  onPickerKeyDown,
  onBlurPicker,
  onPointerDownPicker,
}: Props) {
  const selectedProduct = selectedProductId ? productsById.get(selectedProductId) : undefined
  const selectedPriceError = selectedPriceQuery.error instanceof Error ? selectedPriceQuery.error.message : ''

  return <>
    <div className={`product-picker order-product-picker${canDiscount ? ' order-product-picker-discount' : ''}`}
      onKeyDown={onPickerKeyDown} onBlur={onBlurPicker} onPointerDown={onPointerDownPicker}>
      <SearchableSelect
        ref={productSearchRef}
        onEmptyEnter={onPickerKeyDown}
        key={customerId}
        label="Producto"
        className="order-product-choice"
        fullWidth
        preserveSearch
        allLabel="Seleccionar producto..."
        unavailableLabel="Producto no disponible"
        loadingLabel="Cargando productos…"
        options={products.filter((item) => item.status === 'ACTIVE' && !lines.some((line) => line.productId === item.id)).map((item) => ({
          id: item.id,
          name: item.name,
          ...(item.stock !== undefined ? { detail: `Stock: ${formatNumber(item.stock, 2)}` } : {}),
        }))}
        value={selectedProductId}
        onChange={onSelectProduct}
        disabled={!customerId || !hasSelectedList || submitting}
      />
      {canOverride
        ? <label className="field"><span>Precio</span><input className="input currency-input" aria-label="Precio" aria-busy={selectedPriceQuery.isFetching} placeholder={selectedProductId && selectedPriceQuery.isFetching ? 'Consultando...' : selectedProductId && selectedPriceQuery.isError ? 'No disponible' : '—'} type="text" inputMode="decimal" value={selectedProductPriceOverride ?? (selectedProductId && selectedPriceQuery.data ? String(selectedPriceQuery.data.unitPrice).replace('.', ',') : '')} onChange={(event) => onChangePriceOverride(event.target.value)} disabled={submitting || !customerId || !hasSelectedList || !selectedProductId || selectedPriceQuery.isFetching || selectedPriceQuery.isError || !selectedPriceQuery.data} /></label>
        : <div className="field"><span>Precio</span><span className="read-only-field currency-input" aria-label="Precio" aria-live="polite" aria-busy={selectedPriceQuery.isFetching}>{!selectedProductId ? '—' : selectedPriceQuery.isFetching ? 'Consultando...' : selectedPriceQuery.isError ? 'No disponible' : selectedPriceQuery.data ? String(selectedPriceQuery.data.unitPrice).replace('.', ',') : '—'}</span></div>}
      <label className="field"><span>Cantidad</span><input ref={quantityInputRef} onKeyDown={onAddFromKeyboard} className="input order-compact-input" size={4} type="text" inputMode="decimal" aria-invalid={invalidSelectedQuantity} aria-describedby={invalidSelectedQuantity ? 'order-selected-quantity-error' : undefined} value={selectedProductQuantity} onChange={(event) => onChangeQuantity(event.target.value)} disabled={!selectedProductId || submitting} /></label>
      {canDiscount && <label className="field"><span>Descuento</span><input onKeyDown={onAddFromKeyboard} className="input order-compact-input" size={4} type="text" inputMode="decimal" value={selectedProductDiscount} onChange={(event) => onChangeDiscount(event.target.value)} disabled={!selectedProductId || submitting} /></label>}
      <Button type="button" variant={isEdit ? 'secondary' : 'primary'} aria-label="Agregar producto" onClick={onAddButtonClick} disabled={submitting || !customerId || !hasSelectedList || !selectedProductId || invalidSelectedQuantity || selectedPriceQuery.isFetching || selectedPriceQuery.isError || !selectedPriceQuery.data}>{isEdit ? 'Agregar producto' : <><span aria-hidden="true">+&nbsp;</span>Agregar</>}</Button>
      {selectedProductId && selectedProduct?.stock !== undefined && <p className="order-product-choice helper-text m-0" aria-live="polite">Stock disponible: {formatNumber(selectedProduct.stock, 2)}{Number(selectedProduct.stock) <= 0 ? ' · sin stock (el pedido puede dejarlo negativo)' : ''}</p>}
      {invalidSelectedQuantity && <p id="order-selected-quantity-error" className="error-text" role="alert">La cantidad debe ser positiva y múltiplo de 0,5.</p>}
      {selectedProductId && selectedPriceQuery.isError && <p className="order-product-choice error-text" role="alert">No se pudo consultar el precio de la lista. {selectedPriceError} <Button type="button" variant="link" onClick={() => void selectedPriceQuery.refetch()}>Reintentar precio</Button></p>}
    </div>
    <DataTable className="order-lines order-lines-table" columns={lineColumns} rows={lineRows} emptyContent={<p className="table-empty-message">Elegí un producto para consultar su precio en la lista seleccionada.</p>} />
    {invalidQuantityLines.map((line) => <p key={line.productId} id={`order-quantity-${line.productId}-error`} className="error-text" role="alert">La cantidad de {productsById.get(line.productId)?.name ?? 'el producto'} debe ser positiva y múltiplo de 0,5.</p>)}
  </>
}
