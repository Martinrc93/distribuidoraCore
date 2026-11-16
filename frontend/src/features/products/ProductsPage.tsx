import { CurrencyText } from '../../shared/components/CurrencyText'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { apiGet, apiPatch, apiPost, apiPut, ApiError, type AffectedPriceList, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge, type BadgeTone } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { StockAdjustmentDialog, buildStockAdjustment, type StockAdjustmentMode } from '../../shared/components/StockAdjustmentDialog'
import { SearchableSelect } from '../../shared/components/SearchableSelect'
import { SupplierSelect } from '../../shared/components/EntitySelect'
import { apiGetAllPages } from '../../shared/api/pagination'
import { useUrlListState } from '../../shared/useUrlListState'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { FormModal } from '../../shared/components/FormModal'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'

type Product = {
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
type Movement = { id: string; movementType: string; quantity: number; reason: string; referenceType?: string; referenceId?: string; date: string }
type Row = Record<string, string>

type PriceList = { id: string; code: string; name: string; status: string; isDefault?: boolean }
type Follower = { priceListId: string; code: string; name: string }
type ProductBody = Record<string, unknown>
type CatalogOption = { id: string; name: string; status: string }
type ProductFormValues = {
  description: string
  categoryId: string
  brandId: string
  cost: string
  prices: Record<string, string>
  supplierIds: string[]
}

const PRICE_LIST_QUERY_KEY = ['/api/pricing/lists?page=0&size=20']
const CATEGORIES_QUERY_KEY = ['/api/categories']
const BRANDS_QUERY_KEY = ['/api/brands']

function initialFormValues(initial?: Product): ProductFormValues {
  return {
    description: initial?.description ?? initial?.name ?? '',
    categoryId: initial?.categoryId ?? '',
    brandId: initial?.brandId ?? '',
    cost: initial?.cost === undefined ? '' : String(initial.cost),
    prices: {},
    supplierIds: initial?.suppliers?.map((supplier) => supplier.id) ?? [],
  }
}

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
}

function dateTime(value?: string) {
  return value ? new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

function errorMessage(cause: unknown, fallback: string) {
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

function StatusBadge({ value }: { value: string }) {
  const tone: BadgeTone = value === 'ACTIVE' || value === 'Activo' ? 'strong' : 'muted'
  return <Badge tone={tone}>{value === 'ACTIVE' ? 'Activo' : value === 'INACTIVE' ? 'Inactivo' : value}</Badge>
}

function ProductForm({
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
  const [saving, setSaving] = useState(false)
  const [affectedLists, setAffectedLists] = useState<AffectedPriceList[]>([])
  const suppliersQuery = useQuery({ queryKey: ['/api/suppliers', 'product-options'], queryFn: () => apiGetAllPages<{ id: string; name: string }>('/api/suppliers?page=0&size=100'), retry: false })
  const supplierOptions = [...new Map([...(initial?.suppliers ?? []), ...(suppliersQuery.data?.content ?? [])].map((supplier) => [supplier.id, supplier])).values()]
  const suppliersChanged = [...form.supplierIds].sort().join(',') !== [...(initial?.suppliers?.map((supplier) => supplier.id) ?? [])].sort().join(',')
  const categoryOptions = categories.filter((category) => category.status === 'ACTIVE' || category.id === initial?.categoryId)
  const brandOptions = brands.filter((brand) => brand.status === 'ACTIVE' || brand.id === initial?.brandId)
  const brandName = brands.find((brand) => brand.id === form.brandId)?.name ?? ''
  const generatedName = brandName && form.description.trim() ? `${brandName} ${form.description.trim()}` : ''
  const hasChanges = JSON.stringify(form) !== JSON.stringify(initialFormValues(initial))
  const { requestDiscard: requestClose, discardDialog } = useDiscardChanges({ hasChanges, onDiscard: onDone, disabled: saving })

  useEffect(() => {
    setForm(initialFormValues(initial))
    setAffectedLists([])
    setError('')
  }, [initial])

  function change(field: keyof Omit<ProductFormValues, 'prices' | 'supplierIds'>, value: string) {
    setForm((current) => ({
      ...current,
      [field]: value,
    }))
  }

  function changePrice(priceListId: string, value: string) {
    setForm((current) => ({ ...current, prices: { ...current.prices, [priceListId]: value } }))
  }

  // When editing, prices are asked only if the new cost exceeds them, through CostPriceFixDialog.
  const visiblePriceLists: Array<{ id: string; code: string }> = initial ? [] : activeLists
  const defaultList = activeLists.find((list) => list.isDefault)
  // On creation only the default list price is required: empty lists take it and follow it.
  const isOptionalPrice = (listId: string) => !initial && Boolean(defaultList) && listId !== defaultList?.id
  const [followerWarning, setFollowerWarning] = useState<{ followers: Follower[]; body: ProductBody }>()

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    const cost = Number(form.cost)
    if (!form.description.trim() || !form.categoryId || !form.brandId) {
      setError('Completa la descripción y selecciona una categoría y una marca.')
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
    const prices: Array<{ priceListId: string; price: number }> = []
    for (const list of visiblePriceLists) {
      const rawPrice = form.prices[list.id]
      if (!rawPrice?.trim() && isOptionalPrice(list.id)) continue
      const price = Number(rawPrice?.trim().replace(',', '.'))
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
    await saveProduct(cost, prices)
  }

  async function saveProduct(cost: number, prices: Array<{ priceListId: string; price: number }>) {
    const body: ProductBody = {
      description: form.description.trim(),
      cost,
      ...(prices.length > 0 ? { prices } : {}),
      categoryId: form.categoryId || null,
      brandId: form.brandId || null,
      ...(suppliersChanged ? { supplierIds: form.supplierIds } : {}),
    }
    if (initial && defaultList && prices.some((price) => price.priceListId === defaultList.id)) {
      // Changing the default price also changes the lists that follow it: warn once per product and list.
      try {
        const followers = await apiGet<Follower[]>(`/api/pricing/lists/products/${initial.id}/followers`)
        if (followers.length > 0) {
          setFollowerWarning({ followers, body })
          return
        }
      } catch (cause) {
        setError(errorMessage(cause, 'No se pudo consultar las listas que siguen a la lista general.'))
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
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/products?') })
      await queryClient.invalidateQueries({ queryKey: PRICE_LIST_QUERY_KEY })
      onSuccess(initial ? 'Producto actualizado correctamente.' : 'Producto creado correctamente.')
      onDone()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/products?') })
      if (cause instanceof ApiError && cause.affectedPriceLists?.length) {
        // The new cost exceeds some list prices: fix them right away in a modal.
        setAffectedLists(cause.affectedPriceLists)
      } else {
        setError(errorMessage(cause, 'No se pudo guardar el producto.'))
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
      {visiblePriceLists.map((list) => <label className="field" key={list.id}><span>Precio para {list.code}{isOptionalPrice(list.id) ? " (opcional)" : ""}</span><input className="input currency-input" type="text" inputMode="decimal" value={form.prices[list.id] ?? ''} onChange={(event) => changePrice(list.id, event.target.value)} required={!isOptionalPrice(list.id)} placeholder={isOptionalPrice(list.id) ? `Igual a ${defaultList?.code}` : undefined} disabled={saving} /></label>)}
      {!initial && defaultList && activeLists.length > 1 && <p className="helper-text col-span-full m-0">Las listas sin precio toman el de {defaultList.code} y lo siguen hasta que les pongas un precio propio.</p>}
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
    {affectedLists.length > 0 && <CostPriceFixDialog cost={Number(form.cost)} lists={affectedLists} pending={saving}
      defaultListCode={defaultList?.code}
      onCancel={() => setAffectedLists([])}
      onConfirm={(prices) => { setAffectedLists([]); void saveProduct(Number(form.cost), prices) }} />}</FormModal>
}

/** Asks for new prices for the lists whose price would be below the new cost, before saving the product. */
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
  const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 4 }).format(value)

  function submit(event: FormEvent) {
    event.preventDefault()
    const prices: Array<{ priceListId: string; price: number }> = []
    for (const list of lists) {
      const raw = values[list.priceListId]?.trim() ?? ''
      const price = Number(raw.replace(/\.(?=\d{3}(\D|$))/g, '').replace(',', '.'))
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

export default function ProductsPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const search = getFilter('search')
  const brandId = getFilter('brandId')
  const categoryId = getFilter('categoryId')
  const stock = ['POSITIVE', 'NEGATIVE'].includes(getFilter('stock')) ? getFilter('stock') : ''
  const searchParam = search.trim() ? `&search=${encodeURIComponent(search.trim())}` : ''
  const catalogParams = (brandId ? `&brandId=${encodeURIComponent(brandId)}` : '')
    + (categoryId ? `&categoryId=${encodeURIComponent(categoryId)}` : '')
    + (stock ? `&stock=${stock}` : '')
  const productPath = `/api/products?page=${page}&size=${pageSize}${searchParam}${catalogParams}`
  const productQueryKey = [productPath]
  const query = useQuery({ queryKey: productQueryKey, queryFn: () => apiGet<ApiPage<Product>>(productPath) })
  const listsQuery = useQuery({ queryKey: PRICE_LIST_QUERY_KEY, queryFn: () => apiGet<ApiPage<PriceList>>('/api/pricing/lists?page=0&size=20'), enabled: isAdmin })
  const categoriesQuery = useQuery({ queryKey: CATEGORIES_QUERY_KEY, queryFn: () => apiGet<CatalogOption[]>('/api/categories') })
  const brandsQuery = useQuery({ queryKey: BRANDS_QUERY_KEY, queryFn: () => apiGet<CatalogOption[]>('/api/brands') })
  const [formProduct, setFormProduct] = useState<Product | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [statusProduct, setStatusProduct] = useState<Product | undefined>()
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [mutating, setMutating] = useState(false)
  const [formSaving, setFormSaving] = useState(false)
  const [selectedProduct, setSelectedProduct] = useState<Product | undefined>()
  const [adjustmentProduct, setAdjustmentProduct] = useState<Product | undefined>()
  const [quantity, setQuantity] = useState('')
  const [adjustmentMode, setAdjustmentMode] = useState<StockAdjustmentMode>('target')
  const [reason, setReason] = useState('')
  const [adjustmentError, setAdjustmentError] = useState('')
  const [adjustmentSaving, setAdjustmentSaving] = useState(false)
  const products = query.data?.content ?? []
  const activeLists = (listsQuery.data?.content ?? []).filter((list) => list.status === 'ACTIVE')
  const categories = categoriesQuery.data ?? []
  const brands = brandsQuery.data ?? []
  const optionsLoading = listsQuery.isLoading || categoriesQuery.isLoading || brandsQuery.isLoading
  const optionsError = listsQuery.isError || categoriesQuery.isError || brandsQuery.isError
  const hasFilters = Boolean(search.trim() || brandId || categoryId || stock)
  const brandNames = new Map(brands.map((brand) => [brand.id, brand.name]))
  const movementsPath = selectedProduct ? `/api/inventory/${selectedProduct.id}/movements?page=0&size=20` : ''
  const movementsKey = [movementsPath]
  const movementsQuery = useQuery({ queryKey: movementsKey, queryFn: () => apiGet<ApiPage<Movement>>(movementsPath), enabled: Boolean(selectedProduct) })

  async function submitAdjustment(event: FormEvent) {
    event.preventDefault()
    if (!adjustmentProduct || adjustmentSaving) return
    const request = buildStockAdjustment(adjustmentMode, quantity, reason, Number(adjustmentProduct.stock ?? 0))
    if ('error' in request) { setAdjustmentError(request.error); return }
    setAdjustmentSaving(true)
    setAdjustmentError('')
    try {
      await apiPost(`/api/inventory/${adjustmentProduct.id}/adjustments`, request.body)
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => typeof queryKey[0] === 'string' && (queryKey[0].startsWith('/api/products?') || queryKey[0].startsWith('/api/inventory')) })
      if (selectedProduct?.id === adjustmentProduct.id) await queryClient.invalidateQueries({ queryKey: movementsKey })
      setAdjustmentProduct(undefined)
      setQuantity('')
      setReason('')
      setFeedback('Ajuste de stock registrado.')
    } catch (cause) {
      setAdjustmentError(cause instanceof ApiError && cause.status === 403 ? 'No tenés permiso para ajustar stock.' : cause instanceof Error ? cause.message : 'No se pudo registrar el ajuste.')
    } finally { setAdjustmentSaving(false) }
  }

  async function changeStatus() {
    if (!statusProduct || mutating) return
    setMutating(true)
    setActionError('')
    try {
      const status = statusProduct.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await apiPatch(`/api/products/${statusProduct.id}/status`, { status })
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/products?') })
      setStatusProduct(undefined)
      setFormProduct(undefined)
      setFeedback(status === 'ACTIVE' ? 'Producto activado correctamente.' : 'Producto desactivado correctamente.')
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/products?') })
      setActionError(errorMessage(cause, 'No se pudo actualizar el estado del producto.'))
    } finally {
      setMutating(false)
    }
  }

  const rows = products.map((product) => ({
    id: product.id,
    name: product.name,
    category: product.category,
    brand: product.brandId ? brandNames.get(product.brandId) ?? '—' : '—',
    cost: money(product.cost),
    stock: String(product.stock ?? 0),
    status: product.status ?? 'ACTIVE',
    suppliers: product.suppliers?.map((supplier) => supplier.name).join(', ') ?? '',
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Producto', emphasis: true, render: (value, row) => <div className="min-w-0"><span className="block truncate" title={value}>{value}</span>{isAdmin && row.suppliers && <span className="block truncate text-[11px] font-normal text-muted" title={row.suppliers}>Proveedores: {row.suppliers}</span>}</div> },
    { key: 'category', label: 'Categoría' },
    { key: 'brand', label: 'Marca' },
    ...(isAdmin ? [{ key: 'cost', label: 'Costo', align: 'right' as const, render: (value: string) => <span className="font-bold">{value}</span> }] : []),
    { key: 'stock', label: 'Stock', align: 'right', render: (value) => <span className={`font-bold${Number(value) < 0 ? ' negative-number' : ''}`}>{value}</span> },
    { key: 'actions', label: 'Estado y acciones', align: 'right', render: (_value: string, row: Record<string, string>) => {
      const product = products.find((candidate) => candidate.id === row.id)
      if (!product) return null
      return <div className="product-row-controls">
        <StatusBadge value={product.status ?? 'ACTIVE'} />
        <div className="table-row-actions">
          <Button variant="secondary" onClick={() => setSelectedProduct(product)} aria-label={`Ver movimientos de ${product.name}`}>Ver movimientos</Button>
          {hasAuthority('STOCK_ADJUST') && <Button variant="secondary" onClick={() => { setAdjustmentProduct(product); setAdjustmentMode('target'); setQuantity(''); setReason(''); setAdjustmentError('') }} aria-label={`Ajustar stock de ${product.name}`}>Ajustar stock</Button>}
          {isAdmin && <Button variant="secondary" onClick={() => { setFormProduct(product); setShowForm(false); setFeedback('') }} disabled={formSaving || mutating} aria-label={`Editar ${product.name}`}>Editar</Button>}
        </div>
      </div>
    } },
  ]

  const movementRows: Row[] = (movementsQuery.data?.content ?? []).map((movement) => ({
    id: movement.id, type: movement.movementType, quantity: String(movement.quantity), reason: movement.reason,
    reference: movement.referenceType ? `${movement.referenceType} ${movement.referenceId ?? ''}`.trim() : '—', date: dateTime(movement.date),
  }))
  const movementColumns: TableColumn[] = [
    { key: 'date', label: 'Día y horario' },
    { key: 'type', label: 'Movimiento' },
    { key: 'quantity', label: 'Cantidad', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{value}</span> },
    { key: 'reason', label: 'Motivo' },
    { key: 'reference', label: 'Referencia' },
  ]

  return <>
    <PageHeader eyebrow="Catálogo" title="Stock" description="Productos, stock, marcas, categorías y costos. Los precios se administran por lista." actions={isAdmin ? <Button onClick={() => { setFormProduct(undefined); setShowForm((value) => !value); setFeedback('') }} disabled={formSaving || mutating}>+ Nuevo producto</Button> : <Button variant="secondary" href="/price-lists">Ver listas de precios</Button>} />
    {feedback && <p className="success-text" role="status"><CurrencyText>{feedback}</CurrencyText></p>}
    {actionError && <p className="error-text" role="alert"><CurrencyText>{actionError}</CurrencyText></p>}
    {isAdmin && (showForm || formProduct) && <ProductForm initial={formProduct} activeLists={activeLists} categories={categories} brands={brands} optionsLoading={optionsLoading} optionsError={optionsError} onDone={() => { setShowForm(false); setFormProduct(undefined) }} onSuccess={setFeedback} onBusyChange={setFormSaving} onChangeStatus={(product) => { setActionError(''); setStatusProduct(product) }} />}
    <Panel>
      <div className="toolbar items-end">
        <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Buscar productos</span><input className="input search-input [@media(max-width:640px)]:max-w-none" aria-label="Buscar productos" placeholder="Nombre del producto" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
        <SearchableSelect label="Buscar por marca" options={brands} value={brandId} onChange={(value) => setFilter('brandId', value)} allLabel="Todas las marcas" unavailableLabel="Marca no disponible" loadingLabel="Cargando marcas…" loading={brandsQuery.isLoading} disabled={brandsQuery.isError} />
        <SearchableSelect label="Buscar por categoría" options={categories} value={categoryId} onChange={(value) => setFilter('categoryId', value)} allLabel="Todas las categorías" unavailableLabel="Categoría no disponible" loadingLabel="Cargando categorías…" loading={categoriesQuery.isLoading} disabled={categoriesQuery.isError} />
        <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Stock</span><select className="select [@media(max-width:640px)]:w-full [@media(max-width:640px)]:min-w-0" value={stock} onChange={(event) => setFilter('stock', event.target.value)}>
          <option value="">Ambos</option><option value="POSITIVE">Positivo</option><option value="NEGATIVE">Negativo</option>
        </select></label>
      </div>
      {(categoriesQuery.isError || brandsQuery.isError) && <div className="page-actions mb-[18px]"><p className="error-text" role="alert">No se pudieron cargar las marcas o categorías para filtrar.</p><Button variant="secondary" onClick={() => { if (categoriesQuery.isError) void categoriesQuery.refetch(); if (brandsQuery.isError) void brandsQuery.refetch() }}>Reintentar filtros</Button></div>}
      {query.isLoading ? <EmptyState title="Cargando productos" description="Consultando productos a través de la API." /> : query.isError ? <EmptyState title="No se pudieron cargar los productos" description={query.error.message} /> : products.length === 0 ? <EmptyState title={hasFilters ? 'No hay productos para mostrar' : 'Todavía no hay productos'} description={hasFilters ? 'Probá otra búsqueda o cambiá los filtros.' : 'Creá el primer producto para comenzar a gestionar el catálogo.'} action={isAdmin ? <Button onClick={() => setShowForm(true)} disabled={formSaving || mutating}>+ Nuevo producto</Button> : undefined} /> : <><DataTable className={isAdmin ? 'products-table-admin' : 'products-table-standard'} columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} productos</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    {selectedProduct && <div role="dialog" aria-modal="true" aria-labelledby="movements-title" className="modal-backdrop product-movements-backdrop"><Panel title="Movimientos del producto" action={<Button variant="link" onClick={() => setSelectedProduct(undefined)}>Cerrar</Button>}>
      <h2 id="movements-title">{selectedProduct.name}</h2>
      {movementsQuery.isLoading ? <EmptyState title="Cargando movimientos" description="Consultando el historial de stock." /> : movementsQuery.isError ? <EmptyState title="No se pudieron cargar los movimientos" description={movementsQuery.error.message} /> : movementRows.length === 0 ? <EmptyState title="Sin movimientos" description="No hay movimientos registrados para este producto." /> : <DataTable className="product-movements-table" columns={movementColumns} rows={movementRows} />}
    </Panel></div>}
    {adjustmentProduct && <StockAdjustmentDialog
      productName={adjustmentProduct.name} stock={adjustmentProduct.stock ?? 0}
      mode={adjustmentMode} onModeChange={(mode) => { setAdjustmentMode(mode); setAdjustmentError('') }}
      quantity={quantity} reason={reason} pending={adjustmentSaving} error={adjustmentError}
      onQuantityChange={(value) => { setQuantity(value); setAdjustmentError('') }}
      onReasonChange={(value) => { setReason(value); setAdjustmentError('') }}
      onSubmit={submitAdjustment} onCancel={() => setAdjustmentProduct(undefined)}
    />}
    {statusProduct && <ConfirmationDialog
      title={statusProduct.status === 'ACTIVE' ? 'Desactivar producto' : 'Activar producto'}
      description={`¿Querés ${statusProduct.status === 'ACTIVE' ? 'desactivar' : 'activar'} a ${statusProduct.name}?`}
      cancelLabel="Cancelar" confirmLabel="Confirmar" pendingLabel="Guardando..."
      pending={mutating || formSaving} error={actionError}
      onCancel={() => setStatusProduct(undefined)} onConfirm={() => void changeStatus()}
    />}
  </>
}
