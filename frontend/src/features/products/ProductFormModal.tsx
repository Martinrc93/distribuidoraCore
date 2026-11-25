import { useEffect, useId, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGet, apiPost, apiPut, ApiError, type AffectedPriceList } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { SupplierSelect } from '../../shared/components/EntitySelect'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { parseDecimalInput } from '../../shared/decimal'
import { formatMoney } from '../../shared/format'
import { useDiscardChanges } from '../../shared/useDiscardChanges'

export type Product = {
  id: string
  name: string
  description?: string | null
  category: string
  categoryId?: string | null
  brandId?: string | null
  presentation?: string | null
  cost?: number
  stock?: number
  status?: string
  suppliers?: Array<{ id: string; name: string }>
}

export type PriceList = { id: string; code: string; name: string; status: string; isDefault?: boolean }
export type CatalogOption = { id: string; name: string; status: string }
type Follower = { priceListId: string; code: string; name: string }
type ProductBody = Record<string, unknown>
type ProductFormValues = {
  description: string
  categoryId: string
  brandId: string
  cost: string
  initialStock: string
  prices: Record<string, string>
  supplierIds: string[]
}

export function productErrorMessage(cause: unknown, fallback: string) {
  const status = cause instanceof ApiError ? cause.status : undefined
  const message = cause instanceof ApiError ? cause.detail ?? '' : cause instanceof Error ? cause.message : ''
  if (status !== undefined) {
    if (status === 400) return message || 'Revisá los datos ingresados.'
    if (status === 403) return 'No tenés permisos para realizar esta operación.'
    if (status === 404) return 'El producto ya no existe o no está disponible.'
    if (status === 409) return message || 'El producto ya existe.'
    return message || fallback
  }
  if (/403|forbidden|permiso/i.test(message)) return 'No tenés permisos para realizar esta operación.'
  if (/404|not found|no existe/i.test(message)) return 'El producto ya no existe o no está disponible.'
  if (/409|conflict|ya existe|duplic/i.test(message)) return message || 'El producto ya existe.'
  if (/400|invalid|inválid/i.test(message)) return message || 'Revisá los datos ingresados.'
  return message || fallback
}

function initialFormValues(initial?: Product): ProductFormValues {
  return {
    description: initial?.description ?? initial?.name ?? '',
    categoryId: initial?.categoryId ?? '',
    brandId: initial?.brandId ?? '',
    cost: initial?.cost === undefined ? '' : String(initial.cost),
    initialStock: '',
    prices: {},
    supplierIds: initial?.suppliers?.map((supplier) => supplier.id) ?? [],
  }
}

function CostPriceFixDialog({ cost, lists, pending, defaultListCode, onCancel, onConfirm }: {
  cost: number
  lists: AffectedPriceList[]
  pending: boolean
  defaultListCode?: string
  onCancel: () => void
  onConfirm: (prices: Array<{ priceListId: string; price: number }>) => void
}) {
  const [values, setValues] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const money = (value: number) => formatMoney(value, 4)

  function submit(event: FormEvent) {
    event.preventDefault()
    const prices: Array<{ priceListId: string; price: number }> = []
    for (const list of lists) {
      const raw = values[list.priceListId]?.trim() ?? ''
      const price = parseDecimalInput(raw)
      if (!raw || !Number.isFinite(price) || price < 0) { setError(`Ingresá un precio válido para ${list.code}.`); return }
      if (price < cost) { setError(`El precio para ${list.code} no puede ser menor al nuevo costo (${money(cost)}).`); return }
      prices.push({ priceListId: list.priceListId, price })
    }
    onConfirm(prices)
  }

  return <FormModal size="compact" label="Corregir precios por el nuevo costo" busy={pending} onClose={onCancel}>
    <Panel title="Corregir precios por el nuevo costo">
      <form className="form-grid cost-price-fix" onSubmit={submit}>
        <p className="col-span-full m-0 text-[13px] text-muted"><CurrencyText>{`El nuevo costo (${money(cost)}) supera el precio de ${lists.length === 1 ? 'esta lista' : 'estas listas'}. Ingresá precios iguales o mayores al costo para guardar el producto.`}</CurrencyText></p>
        {lists.map((list) => <div className="field col-span-full" key={list.priceListId}>
          <label htmlFor={`cost-fix-${list.priceListId}`}>Nuevo precio para {list.code}</label>
          <input id={`cost-fix-${list.priceListId}`} className="input currency-input" type="text" inputMode="decimal" value={values[list.priceListId] ?? ''} placeholder={`Mínimo ${money(cost)}`}
            aria-describedby={`cost-fix-${list.priceListId}-current`}
            onChange={(event) => { setValues((current) => ({ ...current, [list.priceListId]: event.target.value })); setError('') }} disabled={pending} />
          <small id={`cost-fix-${list.priceListId}-current`} className="helper-text m-0 font-normal"><CurrencyText>{`Precio actual: ${money(Number(list.currentPrice))}`}</CurrencyText></small>
        </div>)}
        {defaultListCode && lists.some((list) => list.code === defaultListCode) && <p className="helper-text col-span-full m-0">Las listas que siguen a {defaultListCode} van a tomar su nuevo precio.</p>}
        {error && <p className="error-text col-span-full" role="alert"><CurrencyText>{error}</CurrencyText></p>}
        <div className="page-actions col-span-full">
          <Button variant="secondary" type="button" onClick={onCancel} disabled={pending}>Volver</Button>
          <Button type="submit" disabled={pending}>{pending ? 'Guardando...' : 'Guardar precios y costo'}</Button>
        </div>
      </form>
    </Panel>
  </FormModal>
}

export function ProductFormModal({
  initial,
  activeLists,
  categories,
  brands,
  optionsLoading,
  optionsError,
  onDone,
  onSuccess,
  onBusyChange,
  onChangeStatus,
}: {
  initial?: Product
  activeLists: PriceList[]
  categories: CatalogOption[]
  brands: CatalogOption[]
  optionsLoading: boolean
  optionsError: boolean
  onDone: () => void
  onSuccess: (message: string) => void
  onBusyChange: (busy: boolean) => void
  onChangeStatus: (product: Product) => void
}) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<ProductFormValues>(() => initialFormValues(initial))
  const [error, setError] = useState('')
  const [stockError, setStockError] = useState('')
  const stockInputId = useId()
  const stockErrorId = `${stockInputId}-error`
  const [saving, setSaving] = useState(false)
  const [affectedLists, setAffectedLists] = useState<AffectedPriceList[]>([])
  const suppliersQuery = useQuery({
    queryKey: queryKeys.suppliers.options('product'),
    queryFn: ({ signal }) => apiGetAllPages<{ id: string; name: string }>('/api/suppliers?page=0&size=100', signal),
    retry: false,
  })
  const supplierOptions = [...new Map([...(initial?.suppliers ?? []), ...(suppliersQuery.data?.content ?? [])].map((supplier) => [supplier.id, supplier])).values()]
  const suppliersChanged = [...form.supplierIds].sort().join(',') !== [...(initial?.suppliers?.map((supplier) => supplier.id) ?? [])].sort().join(',')
  const categoryOptions = categories.filter((category) => category.status === 'ACTIVE' || category.id === initial?.categoryId)
  const brandOptions = brands.filter((brand) => brand.status === 'ACTIVE' || brand.id === initial?.brandId)
  const brandName = brands.find((brand) => brand.id === form.brandId)?.name ?? ''
  const generatedName = brandName && form.description.trim() ? `${brandName} ${form.description.trim()}` : ''
  const canSetInitialStock = !initial && hasAuthority('STOCK_ADJUST')
  const hasChanges = JSON.stringify(form) !== JSON.stringify(initialFormValues(initial))
  const { requestDiscard: requestClose, discardDialog } = useDiscardChanges({ hasChanges, onDiscard: onDone, disabled: saving })

  useEffect(() => {
    setForm(initialFormValues(initial))
    setAffectedLists([])
    setError('')
    setStockError('')
  }, [initial])

  function change(field: keyof Omit<ProductFormValues, 'prices' | 'supplierIds'>, value: string) {
    setForm((current) => ({ ...current, [field]: value }))
  }

  function changePrice(priceListId: string, value: string) {
    setForm((current) => ({ ...current, prices: { ...current.prices, [priceListId]: value } }))
  }

  const visiblePriceLists: Array<{ id: string; code: string }> = initial ? [] : activeLists
  const defaultList = activeLists.find((list) => list.isDefault)
  const isOptionalPrice = (listId: string) => !initial && Boolean(defaultList) && listId !== defaultList?.id
  const [followerWarning, setFollowerWarning] = useState<{ followers: Follower[]; body: ProductBody }>()

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    const cost = parseDecimalInput(form.cost)
    if (!form.description.trim() || !form.categoryId || !form.brandId) {
      setError('Completá la descripción y seleccioná una categoría y una marca.')
      return
    }
    if (!form.cost.trim() || !Number.isFinite(cost) || cost < 0) {
      setError('El costo debe ser un número no negativo.')
      return
    }
    if (optionsError) {
      setError('No se pudieron cargar las listas y categorías. Reintentá antes de guardar.')
      return
    }
    const rawStock = canSetInitialStock ? form.initialStock.trim() : ''
    const initialStock = rawStock ? Number(rawStock.replace(',', '.')) : undefined
    if (initialStock !== undefined && (!/^\d+(?:[.,](?:0+|50*))?$/.test(rawStock) || initialStock > 999_999_999_999_999.5)) {
      setStockError('Ingresá una cantidad válida, mayor o igual a 0 y en múltiplos de 0,5.')
      return
    }
    const prices: Array<{ priceListId: string; price: number }> = []
    for (const list of visiblePriceLists) {
      const rawPrice = form.prices[list.id]
      if (!rawPrice?.trim() && isOptionalPrice(list.id)) continue
      const price = parseDecimalInput(rawPrice ?? '')
      if (!rawPrice?.trim() || !Number.isFinite(price) || price < 0) {
        setError(`Ingresá un precio válido para ${list.code}.`)
        return
      }
      if (price < cost) {
        setError(`El precio para ${list.code} debe ser igual o mayor al ${initial ? 'nuevo costo' : 'costo'}.`)
        return
      }
      prices.push({ priceListId: list.id, price })
    }
    await saveProduct(cost, prices, initialStock)
  }

  async function saveProduct(cost: number, prices: Array<{ priceListId: string; price: number }>, initialStock?: number) {
    const body: ProductBody = {
      description: form.description.trim(),
      cost,
      ...(prices.length > 0 ? { prices } : {}),
      categoryId: form.categoryId || null,
      brandId: form.brandId || null,
      ...(suppliersChanged ? { supplierIds: form.supplierIds } : {}),
      ...(initialStock !== undefined ? { initialStock } : {}),
    }
    if (initial && defaultList && prices.some((price) => price.priceListId === defaultList.id)) {
      try {
        const followers = await apiGet<Follower[]>(`/api/pricing/lists/products/${initial.id}/followers`)
        if (followers.length > 0) {
          setFollowerWarning({ followers, body })
          return
        }
      } catch (cause) {
        setError(productErrorMessage(cause, 'No se pudo consultar las listas que siguen a la lista general.'))
        return
      }
    }
    await persist(body, false)
  }

  async function persist(body: ProductBody, acknowledgeFollowers: boolean) {
    setFollowerWarning(undefined)
    setSaving(true)
    onBusyChange(true)
    setError('')
    try {
      if (initial) await apiPut(`/api/products/${initial.id}`, acknowledgeFollowers ? { ...body, acknowledgeFollowers: true } : body)
      else await apiPost('/api/products', body)
      await invalidateDomains(queryClient, 'stock', 'pricing', 'reports')
      onSuccess(initial ? 'Producto actualizado correctamente.' : 'Producto creado correctamente.')
      onDone()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) await invalidateDomains(queryClient, 'stock', 'pricing')
      if (cause instanceof ApiError && cause.affectedPriceLists?.length) {
        setAffectedLists(cause.affectedPriceLists)
      } else {
        setError(productErrorMessage(cause, 'No se pudo guardar el producto.'))
      }
    } finally {
      setSaving(false)
      onBusyChange(false)
    }
  }

  return <FormModal label={initial ? 'Editar producto' : 'Nuevo producto'} busy={saving} onClose={requestClose}><Panel title={initial ? 'Editar producto' : 'Nuevo producto'}>
    <form className="form-grid" onSubmit={submit}>
      <label className="field"><span>Descripción</span><input className="input" value={form.description} onChange={(event) => change('description', event.target.value)} maxLength={200} required disabled={saving} /></label>
      <label className="field"><span>Categoría</span><select className="select" value={form.categoryId} onChange={(event) => setForm((current) => ({ ...current, categoryId: event.target.value }))} required disabled={saving || optionsLoading}>
        <option value="">Seleccionar categoría...</option>{categoryOptions.map((category) => <option value={category.id} key={category.id}>{category.name}{category.status !== 'ACTIVE' ? ' · Inactiva' : ''}</option>)}
      </select></label>
      <label className="field content-start"><span>Marca</span><select className="select" value={form.brandId} onChange={(event) => change('brandId', event.target.value)} required disabled={saving || optionsLoading}>
        <option value="">Seleccionar marca...</option>{brandOptions.map((brand) => <option value={brand.id} key={brand.id}>{brand.name}{brand.status !== 'ACTIVE' ? ' · Inactiva' : ''}</option>)}
      </select></label>
      <div className="field min-w-0 content-start"><span>Nombre</span><output className="read-only-field min-w-0 [overflow-wrap:anywhere]" aria-label="Nombre" aria-describedby="product-name-help" aria-live="polite">{generatedName || 'Marca + descripción'}</output><small id="product-name-help" className="helper-text m-0 font-normal">Se genera automáticamente con la marca y la descripción.</small></div>
      <label className="field"><span>Costo</span><input className="input currency-input" type="text" inputMode="decimal" value={form.cost} onChange={(event) => change('cost', event.target.value)} required disabled={saving} /></label>
      {visiblePriceLists.map((list) => <label className="field" key={list.id}><span>Precio para {list.code}{isOptionalPrice(list.id) ? ' (opcional)' : ''}</span><input className="input currency-input" type="text" inputMode="decimal" value={form.prices[list.id] ?? ''} onChange={(event) => changePrice(list.id, event.target.value)} required={!isOptionalPrice(list.id)} placeholder={isOptionalPrice(list.id) ? `Igual a ${defaultList?.code}` : undefined} disabled={saving} /></label>)}
      {!initial && defaultList && activeLists.length > 1 && <p className="helper-text col-span-full m-0">Las listas sin precio toman el de {defaultList.code} y lo siguen hasta que les pongas un precio propio.</p>}
      {canSetInitialStock && <div className="field content-start"><label htmlFor={stockInputId}>Stock inicial (opcional)</label><input id={stockInputId} className="input" type="text" inputMode="decimal" placeholder="0" value={form.initialStock}
        onChange={(event) => { change('initialStock', event.target.value); setStockError('') }} disabled={saving} aria-invalid={Boolean(stockError)} aria-describedby={stockError ? stockErrorId : undefined} />
        {stockError && <span id={stockErrorId} className="error-text" role="alert">{stockError}</span>}
      </div>}
      <div className="col-span-full min-w-0 grid gap-[8px]">
        <SupplierSelect mode="selection" label="Proveedores (opcional)" emptyLabel="Agregar proveedor..." value="" options={supplierOptions.filter((supplier) => !form.supplierIds.includes(supplier.id))}
          loading={suppliersQuery.isLoading} disabled={saving || suppliersQuery.isError} onChange={(id) => { if (id) setForm((current) => ({ ...current, supplierIds: [...current.supplierIds, id] })) }} />
        {suppliersQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los proveedores. <Button variant="link" type="button" disabled={saving} onClick={() => void suppliersQuery.refetch()}>Reintentar proveedores</Button></p>}
        {form.supplierIds.length > 0 ? <ul className="m-0 grid gap-[6px] list-none p-0">{form.supplierIds.map((id) => <li className="flex items-center justify-between gap-[8px] rounded-[5px] border border-line bg-canvas px-[12px] py-[6px]" key={id}>
          <span className="min-w-0 text-[13px] [overflow-wrap:anywhere]">{supplierOptions.find((supplier) => supplier.id === id)?.name ?? 'Proveedor no disponible'}</span>
          <Button variant="link" type="button" disabled={saving} aria-label={`Quitar proveedor ${supplierOptions.find((supplier) => supplier.id === id)?.name ?? id}`} onClick={() => setForm((current) => ({ ...current, supplierIds: current.supplierIds.filter((supplierId) => supplierId !== id) }))}>Quitar</Button>
        </li>)}</ul> : <p className="helper-text m-0">El producto puede guardarse sin proveedores asignados.</p>}
      </div>
      {optionsError && <p className="error-text" role="alert">No se pudieron cargar las listas, marcas o categorías activas.</p>}
      {error && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
      <div className="page-actions">{initial && <Button variant="secondary" type="button" onClick={() => onChangeStatus(initial)} disabled={saving}>{initial.status === 'INACTIVE' ? 'Activar producto' : 'Desactivar producto'}</Button>}<Button variant="secondary" type="button" onClick={requestClose} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving || optionsLoading}>{saving ? 'Guardando...' : initial ? 'Guardar cambios' : 'Guardar producto'}</Button></div>
    </form>
  </Panel>{discardDialog}
    {followerWarning && <ConfirmationDialog
      title={`El precio también cambia en ${followerWarning.followers.map((follower) => follower.name).join(', ')}`}
      description={`${followerWarning.followers.length === 1 ? 'Esa lista sigue' : 'Esas listas siguen'} el precio de ${defaultList?.name ?? 'la lista general'} para este producto, así que va a cambiar igual. Si confirmás, no se volverá a avisar para este producto en ${followerWarning.followers.length === 1 ? 'esa lista' : 'esas listas'}.`}
      confirmLabel="Guardar y no volver a avisar" confirmVariant="primary" cancelLabel="Volver" pendingLabel="Guardando..."
      pending={saving}
      onCancel={() => setFollowerWarning(undefined)} onConfirm={() => void persist(followerWarning.body, true)}
    />}
    {affectedLists.length > 0 && <CostPriceFixDialog cost={parseDecimalInput(form.cost)} lists={affectedLists} pending={saving}
      defaultListCode={defaultList?.code}
      onCancel={() => setAffectedLists([])}
      onConfirm={(prices) => { setAffectedLists([]); void saveProduct(parseDecimalInput(form.cost), prices) }} />}</FormModal>
}
