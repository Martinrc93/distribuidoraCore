import { CurrencyText } from '../../shared/components/CurrencyText'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { apiDelete, apiGet, apiPatch, apiPost, apiPut, ApiError, getAccessToken, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { FormModal } from '../../shared/components/FormModal'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { Badge } from '../../shared/components/Badge'
import { SearchableSelect } from '../../shared/components/SearchableSelect'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { useDiscardChanges } from '../../shared/useDiscardChanges'

type PriceList = { id: string; code: string; name: string; status: string; isDefault: boolean; pendingReviewCount?: number }
/** `followStatus`: PENDING (copied from the default list, not reviewed), CONFIRMED (reviewed, still follows it) or null (own price). */
type ProductPrice = { productId: string; name: string; price: number; effectiveOn?: string; followStatus?: 'PENDING' | 'CONFIRMED' | null; cost?: number | null }
type FollowFilter = '' | 'PENDING' | 'FOLLOWING' | 'OWN'
type CreationPreview = { productsToCopy: number; defaultListName?: string | null }
type Follower = { priceListId: string; code: string; name: string }
type CatalogOption = { id: string; name: string; status: string }
type FollowerWarning = { followers: Follower[]; amount: number; effectiveOn: string; product: ProductPrice }
type HistorySelection = { productId: string; productName: string; listId: string; listName: string }

/** Accepts "1250.5", "1250,50" and "1.250,50" (es-AR); returns NaN for anything else. */
function parsePriceInput(value: string) {
  const text = value.trim().replace(/\s/g, '')
  if (!/^\d{1,3}(\.\d{3})*(,\d{1,4})?$|^\d+([.,]\d{1,4})?$/.test(text)) return NaN
  if (text.includes(',')) return Number(text.replace(/\./g, '').replace(',', '.'))
  // Without a comma, dots grouping exactly three digits are thousands separators ("1.250" = 1250).
  return Number(/^\d{1,3}(\.\d{3})+$/.test(text) ? text.replace(/\./g, '') : text)
}

function followerNames(followers: Follower[]) {
  const names = followers.map((follower) => follower.name)
  return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} y ${names.at(-1)}`
}
type PriceHistory = { effectiveOn: string; price: number; recordedAt: string; updatedAt: string; scheduled: boolean }
const priceHistoryTimestamp = new Intl.DateTimeFormat('es-AR', {
  day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
type ListVisibility = 'ACTIVE' | 'INACTIVE' | 'ALL'
type PriceListPreferences = { visibility: ListVisibility; expanded: boolean }
type Row = Record<string, string>

const defaultPriceListPreferences: PriceListPreferences = { visibility: 'ALL', expanded: true }

function priceListPreferenceKey() {
  const token = getAccessToken()
  try {
    const payload = token?.split('.')[1]
    if (!payload) return 'distribuidora.user-preferences.session.priceLists'
    const base64 = payload.replaceAll('-', '+').replaceAll('_', '/')
    const decoded = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '='))) as { sub?: unknown; email?: unknown }
    const userId = typeof decoded.sub === 'string' ? decoded.sub : typeof decoded.email === 'string' ? decoded.email : 'session'
    return `distribuidora.user-preferences.${encodeURIComponent(userId)}.priceLists`
  } catch {
    return 'distribuidora.user-preferences.session.priceLists'
  }
}

function readPriceListPreferences(key: string): PriceListPreferences {
  try {
    const saved = localStorage.getItem(key)
    if (!saved) return defaultPriceListPreferences
    const value = JSON.parse(saved) as Partial<PriceListPreferences>
    return {
      visibility: value.visibility === 'ACTIVE' || value.visibility === 'INACTIVE' || value.visibility === 'ALL' ? value.visibility : 'ALL',
      expanded: typeof value.expanded === 'boolean' ? value.expanded : true,
    }
  } catch {
    return defaultPriceListPreferences
  }
}

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 4 }).format(Number(value ?? 0))
}

function readableError(cause: unknown, fallback: string) {
  if (cause instanceof ApiError) {
    if (cause.status === 403) return 'No tenés permisos para modificar listas de precios.'
    if (cause.status === 404) return 'La lista o el precio ya no está disponible.'
    return cause.detail || fallback
  }
  return cause instanceof Error ? cause.message : fallback
}

function State({ error }: { error?: Error | null }) {
  return error
    ? <EmptyState title="No se pudieron cargar las listas" description={error.message} />
    : <EmptyState title="Cargando listas" description="Consultando los precios vigentes." />
}

function inlinePriceIndex(current: HTMLInputElement) {
  return [...document.querySelectorAll<HTMLInputElement>('input[data-inline-price]')].indexOf(current)
}

function focusInlinePrice(index: number) {
  document.querySelectorAll<HTMLInputElement>('input[data-inline-price]')[index]?.focus()
}

function focusNextInlinePrice(current: HTMLInputElement) {
  const inputs = [...document.querySelectorAll<HTMLInputElement>('input[data-inline-price]')]
  inputs[inputs.indexOf(current) + 1]?.focus()
}

/**
 * Price edited directly in the table: Enter validates and saves (then moves to the next row),
 * Escape restores the saved value. Unsaved drafts stay marked until saved or restored.
 */
function InlinePriceInput({ product, disabled, validate, onSave }: {
  product: ProductPrice
  disabled: boolean
  validate: (raw: string) => string
  onSave: (raw: string) => Promise<boolean>
}) {
  const saved = new Intl.NumberFormat('es-AR', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(Number(product.price))
  const [draft, setDraft] = useState(saved)
  const [error, setError] = useState('')
  const changed = parsePriceInput(draft) !== Number(product.price) || !draft.trim()
  const errorId = `inline-price-${product.productId}-error`
  return <span className="inline-price">
    <input className={`input currency-input inline-price-input${changed ? ' is-dirty' : ''}`} type="text" inputMode="decimal" data-inline-price
      aria-label={`Precio de ${product.name} en la lista`} aria-invalid={Boolean(error)} aria-describedby={error ? errorId : undefined}
      value={draft} disabled={disabled} onFocus={(event) => event.target.select()}
      onChange={(event) => { setDraft(event.target.value); setError('') }}
      onKeyDown={async (event) => {
        const input = event.currentTarget
        if (event.key === 'Escape') { event.preventDefault(); setDraft(saved); setError(''); return }
        if (event.key !== 'Enter') return
        event.preventDefault()
        if (!changed) { focusNextInlinePrice(input); return }
        const invalid = validate(draft)
        if (invalid) { setError(invalid); return }
        // The row re-renders after saving, so remember its position first.
        const index = inlinePriceIndex(input)
        // Inputs stay disabled while saving; move focus once they are enabled again.
        if (await onSave(draft)) window.setTimeout(() => focusInlinePrice(index + 1), 0)
      }} />
    {error ? <span id={errorId} className="inline-price-error" role="alert">{error}</span>
      : changed && <span className="inline-price-hint">Enter para guardar · Esc para descartar</span>}
  </span>
}

export default function PriceListsPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const historyDialogId = useId()
  const historyHeader = useRef<HTMLElement>(null)
  const [searchParams, setSearchParams] = useSearchParams()
  const preferencesKey = priceListPreferenceKey()
  const [preferences, setPreferences] = useState(() => readPriceListPreferences(preferencesKey))
  const { page: listPage, pageSize, setPage: setListPage } = useUrlListState([], 20, 'page')
  const { page: pricePage, setPage: setPricePage } = useUrlListState([], 20, 'pricePage')
  const { page: historyPage, setPage: setHistoryPage } = useUrlListState([], 20, 'historyPage')
  const listsPath = `/api/pricing/lists?page=${listPage}&size=${pageSize}`
  const listsKey = [listsPath]
  const listsQuery = useQuery({ queryKey: listsKey, queryFn: () => apiGet<ApiPage<PriceList>>(listsPath) })
  const lists = listsQuery.data?.content ?? []
  const hasMultipleLists = (listsQuery.data?.totalElements ?? 0) > 1
  const listVisibility = hasMultipleLists ? preferences.visibility : 'ALL'
  const listExpanded = !hasMultipleLists || preferences.expanded
  const visibleLists = lists.filter((list) => listVisibility === 'ALL' || list.status === listVisibility)
  const [selectedId, setSelectedId] = useState('')
  const selectedList = lists.find((list) => list.id === selectedId)
  const [historySelection, setHistorySelection] = useState<HistorySelection | undefined>()
  const [historyDateToCancel, setHistoryDateToCancel] = useState<string | undefined>()
  const [followFilter, setFollowFilter] = useState<FollowFilter>('')
  const showFollowState = Boolean(selectedList && !selectedList.isDefault)
  const activeFollowFilter = showFollowState ? followFilter : ''
  // Product filters (rubro/category, brand and name) live in the URL so they survive reloads and list changes.
  const priceSearch = searchParams.get('q') ?? ''
  const priceCategoryId = searchParams.get('categoryId') ?? ''
  const priceBrandId = searchParams.get('brandId') ?? ''
  const hasProductFilters = Boolean(priceSearch.trim() || priceCategoryId || priceBrandId)
  const categoriesQuery = useQuery({ queryKey: ['/api/categories'], queryFn: () => apiGet<CatalogOption[]>('/api/categories') })
  const brandsQuery = useQuery({ queryKey: ['/api/brands'], queryFn: () => apiGet<CatalogOption[]>('/api/brands') })
  const productFilterParams = (priceSearch.trim() ? `&search=${encodeURIComponent(priceSearch.trim())}` : '')
    + (priceCategoryId ? `&categoryId=${encodeURIComponent(priceCategoryId)}` : '')
    + (priceBrandId ? `&brandId=${encodeURIComponent(priceBrandId)}` : '')
  const pricesPath = `/api/pricing/lists/${selectedId}/prices?page=${pricePage}&size=${pageSize}${activeFollowFilter ? `&followStatus=${activeFollowFilter}` : ''}${productFilterParams}`

  function setPriceFilter(key: 'q' | 'categoryId' | 'brandId', value: string) {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete('pricePage')
    setSearchParams(next)
  }

  function clearPriceFilters() {
    const next = new URLSearchParams(searchParams)
    for (const key of ['q', 'categoryId', 'brandId', 'pricePage']) next.delete(key)
    setSearchParams(next)
  }
  const pricesKey = [pricesPath]
  const pricesQuery = useQuery({ queryKey: pricesKey, queryFn: () => apiGet<ApiPage<ProductPrice>>(pricesPath), enabled: Boolean(selectedId) })
  const historyBasePath = historySelection ? `/api/pricing/lists/${historySelection.listId}/products/${historySelection.productId}/history` : ''
  const historyPath = historyBasePath ? `${historyBasePath}?page=${historyPage}&size=${pageSize}` : ''
  const historyKey = [historyPath]
  const historyQuery = useQuery({ queryKey: historyKey, queryFn: ({ signal }) => apiGet<ApiPage<PriceHistory>>(historyPath, signal), enabled: Boolean(historyPath), retry: false })
  const cancelHistoryPrice = useMutation({
    mutationFn: async (effectiveOn: string) => {
      await apiDelete(`${historyBasePath}/${effectiveOn}`)
      return effectiveOn
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: historyKey }),
        queryClient.invalidateQueries({ queryKey: pricesKey }),
      ])
      const latestHistory = queryClient.getQueryData<ApiPage<PriceHistory>>(historyKey)
      if (latestHistory && historyPage > 0 && historyPage >= latestHistory.totalPages) setHistoryPage(Math.max(0, latestHistory.totalPages - 1))
      setHistoryDateToCancel(undefined)
    },
  })
  useEffect(() => {
    if (cancelHistoryPrice.isSuccess) historyHeader.current?.querySelector('button')?.focus({ preventScroll: true })
  }, [cancelHistoryPrice.isSuccess])
  const [showCreate, setShowCreate] = useState(false)
  const [name, setName] = useState('')
  const [renameList, setRenameList] = useState<PriceList | undefined>()
  const [rename, setRename] = useState('')
  const [statusList, setStatusList] = useState<PriceList | undefined>()
  const [editingProduct, setEditingProduct] = useState<ProductPrice | undefined>()
  const [price, setPrice] = useState('')
  const [effectiveOn, setEffectiveOn] = useState('')
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [createPreview, setCreatePreview] = useState<CreationPreview | undefined>()
  const [checkingCreate, setCheckingCreate] = useState(false)
  const [followerWarning, setFollowerWarning] = useState<FollowerWarning | undefined>()
  const [savingPrice, setSavingPrice] = useState(false)
  const [confirmingReview, setConfirmingReview] = useState(false)
  const { requestDiscard: closePriceEditor, discardDialog: priceDiscardDialog } = useDiscardChanges({
    hasChanges: Boolean(editingProduct && (effectiveOn || price !== String(editingProduct.price).replace('.', ','))),
    disabled: savingPrice,
    onDiscard: () => { setEditingProduct(undefined); setError('') },
  })
  const refreshPricing = () => queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/pricing/lists') })

  useEffect(() => {
    if (selectedId && visibleLists.some((list) => list.id === selectedId)) return
    const initial = visibleLists.find((list) => list.status === 'ACTIVE' && list.isDefault) ?? visibleLists.find((list) => list.status === 'ACTIVE')
    setSelectedId(initial?.id ?? visibleLists[0]?.id ?? '')
  }, [lists, selectedId, listVisibility])

  useEffect(() => {
    try {
      localStorage.setItem(preferencesKey, JSON.stringify(preferences))
    } catch {
      // Keep the current choices for this page even when browser storage is unavailable.
    }
  }, [preferences, preferencesKey])

  const createMutation = useMutation({
    mutationFn: () => apiPost('/api/pricing/lists', { name: name.trim() }),
    onSuccess: async () => {
      await refreshPricing()
      const copied = createPreview?.productsToCopy ?? 0
      closeCreate()
      setFeedback(copied > 0
        ? `Lista creada con ${copied} ${copied === 1 ? 'producto marcado' : 'productos marcados'} «Sin revisar».`
        : 'Lista creada correctamente.')
    },
    onError: (cause) => { setCreatePreview(undefined); setError(readableError(cause, 'No se pudo crear la lista.')) },
  })
  const creatingList = checkingCreate || createMutation.isPending

  function closeCreate() {
    setCreatePreview(undefined)
    setShowCreate(false)
    setName('')
    setError('')
  }

  async function submitCreate(event: FormEvent) {
    event.preventDefault()
    if (creatingList || createPreview) return
    setError('')
    if (!name.trim()) {
      setError('Completá el nombre de la lista.')
      return
    }
    // With products already loaded, the new list takes the default list prices: ask first.
    setCheckingCreate(true)
    try {
      const preview = await apiGet<CreationPreview>('/api/pricing/lists/creation-preview')
      if (preview.productsToCopy > 0) setCreatePreview(preview)
      else createMutation.mutate()
    } catch (cause) {
      setError(readableError(cause, 'No se pudo verificar los productos cargados.'))
    } finally {
      setCheckingCreate(false)
    }
  }

  async function confirmReview(productIds?: string[]) {
    if (!selectedList || confirmingReview) return
    setConfirmingReview(true)
    setError('')
    try {
      const result = await apiPost<{ confirmed: number }>(`/api/pricing/lists/${selectedList.id}/reviews/confirm`, productIds ? { productIds } : {})
      await refreshPricing()
      setFeedback(result.confirmed === 1 ? 'Precio confirmado sin cambios.' : `${result.confirmed} precios confirmados sin cambios.`)
    } catch (cause) {
      setError(readableError(cause, 'No se pudieron confirmar los precios.'))
    } finally {
      setConfirmingReview(false)
    }
  }

  async function submitRename(event: FormEvent) {
    event.preventDefault()
    if (!renameList || !rename.trim()) {
      setError('Ingresá un nombre para la lista.')
      return
    }
    setError('')
    try {
      await apiPut(`/api/pricing/lists/${renameList.id}`, { name: rename.trim() })
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/pricing/lists?') })
      setRenameList(undefined)
      setFeedback('Nombre de lista actualizado.')
    } catch (cause) {
      setError(readableError(cause, 'No se pudo cambiar el nombre de la lista.'))
    }
  }

  async function changeStatus() {
    if (!statusList) return
    setError('')
    try {
      const status = statusList.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await apiPatch(`/api/pricing/lists/${statusList.id}/status`, { status })
      await refreshPricing()
      setStatusList(undefined)
      setFeedback(status === 'ACTIVE' ? 'Lista activada correctamente.' : 'Lista desactivada correctamente.')
    } catch (cause) {
      setError(readableError(cause, 'No se pudo actualizar el estado de la lista.'))
    }
  }

  async function savePrice(event: FormEvent) {
    event.preventDefault()
    if (!editingProduct || savingPrice || followerWarning) return
    const amount = parsePriceInput(price)
    const invalid = priceError(editingProduct, price, amount)
    if (invalid) {
      setError(invalid)
      return
    }
    setError('')
    await requestPriceSave(editingProduct, amount, effectiveOn)
  }

  /** Validates a typed price: a plain non-negative number, never below the product cost. */
  function priceError(product: ProductPrice, raw: string, amount: number) {
    if (!raw.trim() || !Number.isFinite(amount) || amount < 0) return 'Ingresá un precio válido, igual o mayor a cero.'
    if (product.cost !== undefined && product.cost !== null && amount < Number(product.cost)) {
      return `El precio no puede ser menor al costo (${money(product.cost)}).`
    }
    return ''
  }

  /** Saves a price; on the default list it first warns about lists that follow it. Returns true when saved now. */
  async function requestPriceSave(product: ProductPrice, amount: number, date: string): Promise<boolean> {
    if (!selectedList) return false
    if (selectedList.isDefault) {
      // Lists following the default price change with it: warn once per product and list.
      setSavingPrice(true)
      try {
        const followers = await apiGet<Follower[]>(`/api/pricing/lists/products/${product.productId}/followers`)
        if (followers.length > 0) {
          setFollowerWarning({ followers, amount, effectiveOn: date, product })
          return false
        }
      } catch (cause) {
        setError(readableError(cause, 'No se pudo consultar las listas que siguen a esta.'))
        return false
      } finally {
        setSavingPrice(false)
      }
    }
    return persistPrice(product, amount, date, false)
  }

  async function persistPrice(product: ProductPrice, amount: number, date: string, acknowledgeFollowers: boolean): Promise<boolean> {
    if (!selectedList) return false
    setSavingPrice(true)
    setError('')
    try {
      await apiPut(`/api/pricing/lists/${selectedList.id}/products/${product.productId}`, {
        price: amount,
        ...(date ? { effectiveOn: date } : {}),
        ...(acknowledgeFollowers ? { acknowledgeFollowers: true } : {}),
      })
      await refreshPricing()
      const followers = followerWarning?.followers ?? []
      setFollowerWarning(undefined)
      setEditingProduct(undefined)
      setEffectiveOn('')
      setFeedback(followers.length > 0
        ? `Precio de ${product.name} actualizado, también en ${followerNames(followers)}.`
        : date ? `Precio de ${product.name} programado para ${date}.` : `Precio de ${product.name} actualizado.`)
      return true
    } catch (cause) {
      setFollowerWarning(undefined)
      setError(readableError(cause, 'No se pudo actualizar el precio.'))
      return false
    } finally {
      setSavingPrice(false)
    }
  }

  function closeHistory() {
    setHistorySelection(undefined)
    cancelHistoryPrice.reset()
    setHistoryPage(0)
  }

  const rows: Row[] = (pricesQuery.data?.content ?? []).map((item) => ({
    id: item.productId,
    name: item.name,
    price: money(item.price),
    effectiveOn: item.effectiveOn ?? '—',
    followStatus: item.followStatus ?? '',
  }))
  const defaultListName = lists.find((list) => list.isDefault)?.name ?? 'Lista general'
  const priceColumns: TableColumn[] = [
    { key: 'name', label: 'Producto', emphasis: true },
    { key: 'price', label: 'Precio de lista', align: 'right', render: (value: string, row: Row) => {
      const product = pricesQuery.data?.content.find((item) => item.productId === row.id)
      if (!isAdmin || !product || !selectedList || selectedList.status !== 'ACTIVE') return value
      return <InlinePriceInput key={`${product.productId}-${product.price}`} product={product} disabled={savingPrice}
        validate={(raw) => priceError(product, raw, parsePriceInput(raw))}
        onSave={(raw) => requestPriceSave(product, parsePriceInput(raw), '')} />
    } },
    { key: 'effectiveOn', label: 'Vigente desde' },
    ...(showFollowState ? [{ key: 'followStatus', label: 'Estado', render: (value: string) => value === 'PENDING'
      ? <Badge tone="strong">Sin revisar</Badge>
      : value === 'CONFIRMED' ? <Badge tone="soft">Sin cambios</Badge> : <Badge tone="muted">Precio propio</Badge> } satisfies TableColumn] : []),
    { key: 'actions', label: 'Acciones', align: 'right', render: (_value: string, row: Row) => {
      const product = pricesQuery.data?.content.find((item) => item.productId === row.id)
      if (!product || !selectedList) return null
      return <div className="table-row-actions">
        {isAdmin && showFollowState && product.followStatus === 'PENDING' && <Button variant="secondary" disabled={confirmingReview} onClick={() => void confirmReview([product.productId])} aria-label={`Confirmar precio de ${product.name} sin cambios`}>Confirmar</Button>}
        {isAdmin && <Button variant="secondary" onClick={() => { setEditingProduct(product); setPrice(String(product.price).replace('.', ',')); setEffectiveOn(''); setError('') }} aria-label={`Programar precio de ${product.name}`}>Programar</Button>}
        <Button variant="secondary" aria-haspopup="dialog" aria-label={`Ver historial de precios de ${product.name}`} onClick={() => {
          setHistorySelection({ productId: product.productId, productName: product.name, listId: selectedList.id, listName: selectedList.name })
          setHistoryPage(0)
          setError('')
        }}>Ver historial</Button>
      </div>
    } },
  ]
  const pendingReviewCount = selectedList?.pendingReviewCount ?? 0
  const priceTableClass = ['price-products-table', isAdmin ? 'price-products-table-admin' : 'price-products-table-standard', showFollowState ? 'price-products-table-follow' : ''].filter(Boolean).join(' ')
  const nextStatusIsActive = statusList?.status !== 'ACTIVE'

  return <div className="price-lists-page">
    <PageHeader eyebrow="Catálogo" title="Listas de precios" description="Cada producto tiene un precio por lista; los pedidos resuelven la lista del cliente o la elegida para la operación." actions={isAdmin ? <Button onClick={() => { setShowCreate((value) => !value); setError('') }}>+ Nueva lista</Button> : undefined} />
    {feedback && <p className="success-text" role="status"><CurrencyText>{feedback}</CurrencyText></p>}
    {error && !showCreate && !renameList && !editingProduct && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
    {showCreate && isAdmin && <>
      <FormModal size="compact" label="Nueva lista de precios" busy={creatingList} onClose={closeCreate}>
        <Panel title="Nueva lista de precios">
          <form className="grid gap-[14px]" onSubmit={submitCreate}>
            <label className="field"><span>Nombre de lista</span><input className="input w-full" value={name} onChange={(event) => setName(event.target.value)} required maxLength={120} disabled={creatingList} /></label>
            {error && <p className="error-text m-0" role="alert"><CurrencyText>{error}</CurrencyText></p>}
            <div className="page-actions justify-end">
              <Button variant="secondary" type="button" onClick={closeCreate} disabled={creatingList}>Cancelar</Button>
              <Button type="submit" disabled={creatingList || Boolean(createPreview) || !name.trim()}>{creatingList ? 'Guardando...' : 'Guardar lista'}</Button>
            </div>
          </form>
        </Panel>
      </FormModal>
      {createPreview && <ConfirmationDialog
        title={`¿Crear la lista con los precios de ${createPreview.defaultListName ?? defaultListName}?`}
        description={`Ya hay ${createPreview.productsToCopy} ${createPreview.productsToCopy === 1 ? 'producto cargado' : 'productos cargados'}. La nueva lista va a tomar los precios de ${createPreview.defaultListName ?? defaultListName} y los vas a ver marcados «Sin revisar» hasta que los confirmes o cambies. Mientras no les pongas un precio propio, van a seguir los cambios de ${createPreview.defaultListName ?? defaultListName}.`}
        confirmLabel="Crear lista" confirmVariant="primary" cancelLabel="Volver" pendingLabel="Creando..."
        pending={createMutation.isPending}
        onCancel={() => setCreatePreview(undefined)} onConfirm={() => createMutation.mutate()}
      />}
    </>}
    {renameList && <FormModal size="compact" label={`Renombrar ${renameList.code}`} onClose={() => { setRenameList(undefined); setError('') }}><Panel title={`Renombrar ${renameList.code}`}><form className="form-grid" onSubmit={submitRename}>
      <label className="field"><span>Nuevo nombre</span><input className="input" value={rename} onChange={(event) => setRename(event.target.value)} required maxLength={120} /></label>
      {error && <p className="error-text col-span-full" role="alert"><CurrencyText>{error}</CurrencyText></p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => { setRenameList(undefined); setError('') }}>Cancelar</Button><Button type="submit">Guardar nombre</Button></div>
    </form></Panel></FormModal>}
    {listsQuery.isLoading || listsQuery.isError ? <Panel><State error={listsQuery.error} /></Panel> : lists.length === 0 ? <Panel><EmptyState title="Todavía no hay listas" description="Creá una lista de precios para asignar valores de venta a los productos." action={isAdmin ? <Button onClick={() => setShowCreate(true)}>+ Nueva lista</Button> : undefined} /></Panel> : <>
      <Panel title="Listas de precios" description={`${visibleLists.length} ${visibleLists.length === 1 ? 'lista disponible' : 'listas disponibles'} · Los pedidos usan la lista asignada al cliente.`} action={hasMultipleLists && <div className="price-list-controls">
        <label className="price-list-filter"><span>Mostrar</span><select className="select" aria-label="Mostrar listas" value={listVisibility} onChange={(event) => setPreferences((current) => ({ ...current, visibility: event.target.value as ListVisibility }))}>
          <option value="ALL">Todas</option><option value="ACTIVE">Activas</option><option value="INACTIVE">Inactivas</option>
        </select></label>
        <Button variant="secondary" aria-label={listExpanded ? 'Plegar listas' : 'Desplegar listas'} title={listExpanded ? 'Plegar listas' : 'Desplegar listas'} aria-expanded={listExpanded} aria-controls="price-list-selector" onClick={() => setPreferences((current) => ({ ...current, expanded: !current.expanded }))}>
          <svg className={`price-list-toggle-icon${listExpanded ? ' expanded' : ''}`} viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m5 7.5 5 5 5-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </Button>
      </div>}>
        {listExpanded && (visibleLists.length > 0 ? <div id="price-list-selector" className="price-list-selector" role="tablist" aria-label="Listas disponibles">{visibleLists.map((list) => <button className={`price-list-card${selectedId === list.id ? ' selected' : ''}`} type="button" role="tab" aria-selected={selectedId === list.id} key={list.id} onClick={() => { const next = new URLSearchParams(searchParams); next.delete('pricePage'); next.delete('historyPage'); setSearchParams(next); setSelectedId(list.id); setFollowFilter(''); setHistorySelection(undefined); setFeedback(''); setError('') }}>
          <span className="price-list-card-heading"><strong>{list.name}</strong>{list.isDefault && <span className="price-list-default">Predeterminada</span>}</span>
          <span className="price-list-code">{list.code}</span>
          <span className={`price-list-status${list.status === 'ACTIVE' ? ' active' : ''}`}><i aria-hidden="true" />{list.status === 'ACTIVE' ? 'Activa' : 'Inactiva'}</span>
          {(list.pendingReviewCount ?? 0) > 0 && <span className="price-list-pending">{list.pendingReviewCount} sin revisar</span>}
        </button>)}</div> : <EmptyState title={`No hay listas ${listVisibility === 'ACTIVE' ? 'activas' : 'inactivas'}`} description="Elegí otro filtro para ver las listas disponibles." />)}
        {listExpanded && isAdmin && selectedList && <div className="page-actions price-list-actions"><Button variant="secondary" onClick={() => { setRenameList(selectedList); setRename(selectedList.name); setError('') }}>Renombrar {selectedList.name}</Button><Button variant="secondary" onClick={() => { setError(''); setStatusList(selectedList) }}>{selectedList.status === 'ACTIVE' ? `Desactivar ${selectedList.name}` : `Activar ${selectedList.name}`}</Button></div>}
        {listsQuery.data && listsQuery.data.totalPages > 1 && <div className="pagination"><span>Página {listPage + 1} de {listsQuery.data.totalPages} · {listsQuery.data.totalElements} listas</span><div><Button variant="secondary" onClick={() => setListPage(listPage - 1)} disabled={listPage === 0}>Anterior</Button><Button variant="secondary" onClick={() => setListPage(listPage + 1)} disabled={listPage + 1 >= listsQuery.data.totalPages}>Siguiente</Button></div></div>}
      </Panel>
      <Panel title={selectedList ? `Precios de ${selectedList.name}` : 'Precios'} description={pricesQuery.data ? `${pricesQuery.data.totalElements} productos ${activeFollowFilter || hasProductFilters ? 'con estos filtros' : 'en esta lista'}` : selectedList ? `${selectedList.code}${selectedList.isDefault ? ' · Lista predeterminada' : ''}` : undefined}
        action={selectedList ? <div className="price-list-controls">
          {showFollowState && <label className="price-list-filter"><span>Estado</span><select className="select" aria-label="Filtrar por estado del precio" value={followFilter} onChange={(event) => { setFollowFilter(event.target.value as FollowFilter); setPricePage(0) }}>
            <option value="">Todos</option><option value="PENDING">Sin revisar</option><option value="FOLLOWING">Siguen a {defaultListName}</option><option value="OWN">Precio propio</option>
          </select></label>}
          {isAdmin && showFollowState && pendingReviewCount > 0 && <Button variant="secondary" disabled={confirmingReview} onClick={() => void confirmReview()}>{confirmingReview ? 'Confirmando...' : `Confirmar ${pendingReviewCount} sin revisar`}</Button>}
          {/* The PDF uses the product filters (category, brand, name) currently applied to the table. */}
          <DocumentActions path={`/api/documents/price-lists/${selectedList.id}${productFilterParams ? `?${productFilterParams.slice(1)}` : ''}`}
            filename={`lista-${selectedList.code}.pdf`} printLabel="Imprimir lista" downloadLabel="Descargar PDF"
            onDone={(message) => { setError(''); setFeedback(message) }} />
        </div> : undefined}>
        <div className="toolbar items-end price-product-filters">
          <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Buscar producto</span><input className="input search-input [@media(max-width:640px)]:max-w-none" aria-label="Buscar producto en la lista" placeholder="Nombre del producto" value={priceSearch} onChange={(event) => setPriceFilter('q', event.target.value)} /></label>
          <SearchableSelect label="Categoría" options={Array.isArray(categoriesQuery.data) ? categoriesQuery.data : []} value={priceCategoryId} onChange={(value) => setPriceFilter('categoryId', value)} allLabel="Todas las categorías" unavailableLabel="Categoría no disponible" loadingLabel="Cargando categorías…" loading={categoriesQuery.isLoading} disabled={categoriesQuery.isError} />
          <SearchableSelect label="Marca" options={Array.isArray(brandsQuery.data) ? brandsQuery.data : []} value={priceBrandId} onChange={(value) => setPriceFilter('brandId', value)} allLabel="Todas las marcas" unavailableLabel="Marca no disponible" loadingLabel="Cargando marcas…" loading={brandsQuery.isLoading} disabled={brandsQuery.isError} />
          {hasProductFilters && <Button variant="link" type="button" onClick={clearPriceFilters}>Limpiar filtros</Button>}
        </div>
        {(categoriesQuery.isError || brandsQuery.isError) && <div className="page-actions mb-[18px]"><p className="error-text" role="alert">No se pudieron cargar las categorías o marcas para filtrar.</p><Button variant="secondary" onClick={() => { if (categoriesQuery.isError) void categoriesQuery.refetch(); if (brandsQuery.isError) void brandsQuery.refetch() }}>Reintentar filtros</Button></div>}
        {showFollowState && <p className="helper-text price-follow-help">«Sin revisar» y «Sin cambios» siguen automáticamente los cambios de {defaultListName}. Al editar un precio, pasa a ser propio y deja de seguirla.</p>}
        {pricesQuery.isLoading || pricesQuery.isError ? <State error={pricesQuery.error} /> : rows.length === 0 ? <EmptyState title={activeFollowFilter || hasProductFilters ? 'No hay precios para estos filtros' : 'Esta lista todavía no tiene precios'} description={activeFollowFilter || hasProductFilters ? 'Probá otra búsqueda, categoría, marca o estado.' : 'Los precios aparecen cuando se asignan a productos.'} /> : <DataTable className={priceTableClass} columns={priceColumns} rows={rows} />}
        {pricesQuery.data && pricesQuery.data.totalPages > 1 && <div className="pagination"><span>Página {pricePage + 1} de {pricesQuery.data.totalPages} · {pricesQuery.data.totalElements} productos</span><div><Button variant="secondary" onClick={() => setPricePage(pricePage - 1)} disabled={pricePage === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPricePage(pricePage + 1)} disabled={pricePage + 1 >= pricesQuery.data.totalPages}>Siguiente</Button></div></div>}
      </Panel>
    </>}
    {historySelection && <FormModal className="price-history-dialog" labelledBy={`${historyDialogId}-title`} describedBy={`${historyDialogId}-context`} busy={cancelHistoryPrice.isPending} onClose={closeHistory}>
      <Panel>
        <header className="price-history-header" ref={historyHeader}>
          <div className="min-w-0">
            <h2 id={`${historyDialogId}-title`}>Historial de precios</h2>
            <p id={`${historyDialogId}-context`}>{historySelection.productName} · {historySelection.listName}</p>
          </div>
          <Button type="button" variant="secondary" aria-label="Cerrar historial" onClick={closeHistory} disabled={cancelHistoryPrice.isPending}>Cerrar</Button>
        </header>
        <div className="price-history-body">
          {cancelHistoryPrice.data && <p className="success-text mt-0" role="status">Precio programado para {cancelHistoryPrice.data} cancelado.</p>}
          {historyQuery.isLoading ? <div role="status"><EmptyState title="Cargando historial" description="Consultando vigencias registradas." /></div>
            : historyQuery.isError ? <div role="alert"><EmptyState title="No se pudo cargar el historial" description={historyQuery.error.message}
              action={<Button type="button" variant="secondary" onClick={() => void historyQuery.refetch()}>Reintentar</Button>} /></div>
            : historyQuery.data?.content.length ? <DataTable className="price-history-table" columns={[
              { key: 'effectiveOn', label: 'Vigente desde', emphasis: true },
              { key: 'price', label: 'Precio', align: 'right' },
              { key: 'recordedAt', label: 'Registrado el' },
              { key: 'updatedAt', label: 'Actualizado el' },
              { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'Programado' ? 'strong' : 'muted'}>{value}</Badge> },
              ...(isAdmin ? [{ key: 'actions', label: 'Acciones', align: 'right', render: (date) => date &&
                <Button type="button" variant="link" aria-haspopup="dialog" aria-label={`Cancelar precio del ${date}`} onClick={() => {
                  cancelHistoryPrice.reset()
                  setHistoryDateToCancel(date)
                }}>Cancelar precio</Button>,
              } satisfies TableColumn] : []),
            ]} rows={historyQuery.data.content.map((entry) => ({
              id: entry.effectiveOn,
              effectiveOn: entry.effectiveOn,
              price: money(entry.price),
              recordedAt: priceHistoryTimestamp.format(new Date(entry.recordedAt)),
              updatedAt: priceHistoryTimestamp.format(new Date(entry.updatedAt)),
              status: entry.scheduled ? 'Programado' : 'Vigente o histórico',
              actions: entry.scheduled ? entry.effectiveOn : '',
            }))} /> : <EmptyState title="Sin historial" description="Este producto no registra vigencias de precio." />}
          {historyQuery.data && historyQuery.data.totalPages > 1 && <div className="pagination">
            <span>Página {historyPage + 1} de {historyQuery.data.totalPages} · {historyQuery.data.totalElements} vigencias</span>
            <div>
              <Button variant="secondary" aria-label="Historial anterior" onClick={() => setHistoryPage(historyPage - 1)} disabled={historyPage === 0}>Anterior</Button>
              <Button variant="secondary" aria-label="Siguiente historial" onClick={() => setHistoryPage(historyPage + 1)} disabled={historyPage + 1 >= historyQuery.data.totalPages}>Siguiente</Button>
            </div>
          </div>}
        </div>
      </Panel>
    </FormModal>}
    {historySelection && historyDateToCancel && <ConfirmationDialog
      title={`¿Cancelar el precio programado para ${historyDateToCancel}?`}
      description={`Se quitará esta vigencia programada de ${historySelection.productName} en ${historySelection.listName}. Las demás fechas de vigencia se conservan.`}
      confirmLabel="Confirmar cancelación" pendingLabel="Cancelando…" pending={cancelHistoryPrice.isPending}
      error={cancelHistoryPrice.error ? readableError(cancelHistoryPrice.error, 'No se pudo cancelar el precio programado.') : undefined}
      onCancel={() => setHistoryDateToCancel(undefined)}
      onConfirm={() => cancelHistoryPrice.mutate(historyDateToCancel)}
    />}
    {editingProduct && <FormModal size="compact" label="Programar precio" busy={savingPrice} onClose={closePriceEditor}>
      <Panel title="Programar precio" description={`${editingProduct.name} · ${selectedList?.name ?? defaultListName}`}>
        <form className="grid grid-cols-1 gap-[14px]" onSubmit={savePrice}>
          <label className="field">
            <span>Precio de {editingProduct.name}</span>
            <input className="input currency-input w-full min-w-0" type="text" inputMode="decimal" value={price} onChange={(event) => setPrice(event.target.value)} required readOnly={savingPrice} />
          </label>
          <label className="field">
            <span>Vigente desde</span>
            <input className="input w-full min-w-0" aria-label="Fecha de vigencia" aria-describedby="scheduled-price-date-help" type="date" value={effectiveOn} onChange={(event) => setEffectiveOn(event.target.value)} readOnly={savingPrice} />
            <small id="scheduled-price-date-help" className="text-muted text-[11px] font-normal leading-[1.5]">Dejalo vacío para aplicar el precio desde hoy.</small>
          </label>
          {error && <p className="error-text m-0" role="alert"><CurrencyText>{error}</CurrencyText></p>}
          {showFollowState && editingProduct.followStatus && <p className="helper-text m-0">Este precio sigue a {defaultListName}. Al guardarlo pasa a ser propio y deja de seguir sus cambios.</p>}
          {selectedList?.isDefault && <p className="helper-text m-0">Si otras listas siguen este precio, se te va a avisar antes de guardar.</p>}
          <div className="page-actions justify-end [border-top:1px_solid_var(--line)] pt-[14px]">
            <Button variant="secondary" type="button" onClick={closePriceEditor} disabled={savingPrice}>Cancelar</Button>
            <Button type="submit" disabled={savingPrice}>{savingPrice ? 'Guardando...' : 'Guardar precio'}</Button>
          </div>
        </form>
      </Panel>
    </FormModal>}
    {priceDiscardDialog}
    {followerWarning && <ConfirmationDialog
      title={`Este cambio también se aplica en ${followerNames(followerWarning.followers)}`}
      description={`${followerWarning.followers.length === 1 ? 'Esa lista sigue' : 'Esas listas siguen'} a ${selectedList?.name ?? defaultListName} para ${followerWarning.product.name}, así que su precio va a cambiar igual. Si confirmás, no se volverá a avisar para este producto en ${followerWarning.followers.length === 1 ? 'esa lista' : 'esas listas'}.`}
      confirmLabel="Guardar y no volver a avisar" confirmVariant="primary" cancelLabel="Volver" pendingLabel="Guardando..."
      pending={savingPrice}
      onCancel={() => setFollowerWarning(undefined)} onConfirm={() => void persistPrice(followerWarning.product, followerWarning.amount, followerWarning.effectiveOn, true)}
    />}
    {statusList && <div role="dialog" aria-modal="true" aria-labelledby="status-dialog-title" aria-describedby="status-dialog-description" className="price-status-dialog-backdrop">
      <section className={`price-status-dialog${nextStatusIsActive ? ' is-activating' : ' is-deactivating'}`}>
        <header className="price-status-dialog-header">
          <span className="price-status-dialog-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none"><path d="M7 12.5 10.2 16 17.5 8" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
          </span>
          <div>
            <p className="price-status-dialog-kicker">Lista de precios</p>
            <h2 id="status-dialog-title">{nextStatusIsActive ? 'Activar lista' : 'Desactivar lista'}</h2>
          </div>
          <button className="price-status-dialog-close" type="button" aria-label="Cerrar diálogo" onClick={() => setStatusList(undefined)}>×</button>
        </header>
        <div className="price-status-dialog-body">
          <p id="status-dialog-description">{nextStatusIsActive && !statusList.isDefault
            ? `Se activará la lista. Los productos que no tengan precio en ella van a tomar el precio de ${defaultListName}, quedarán marcados «Sin revisar» y seguirán sus cambios hasta que les pongas un precio propio.`
            : 'Se actualizará el estado de la lista seleccionada.'}</p>
          {error && <p className="price-status-dialog-error" role="alert"><CurrencyText>{error}</CurrencyText></p>}
          <div className="price-status-dialog-summary">
            <span className="price-status-dialog-label">Lista seleccionada</span>
            <strong>{statusList.name}</strong>
            <div className="price-status-transition" aria-label={`Estado ${statusList.status === 'ACTIVE' ? 'Activa' : 'Inactiva'}; nuevo estado ${nextStatusIsActive ? 'Activa' : 'Inactiva'}`}>
              <span className={`price-status-pill${statusList.status === 'ACTIVE' ? ' active' : ''}`}>{statusList.status === 'ACTIVE' ? 'Activa' : 'Inactiva'}</span>
              <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M4 10h11m0 0-4-4m4 4-4 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" /></svg>
              <span className={`price-status-pill${nextStatusIsActive ? ' active' : ''}`}>{nextStatusIsActive ? 'Activa' : 'Inactiva'}</span>
            </div>
          </div>
        </div>
        <footer className="price-status-dialog-footer">
          <Button variant="secondary" onClick={() => setStatusList(undefined)}>Cancelar</Button>
          <Button variant={nextStatusIsActive ? 'primary' : 'danger'} onClick={changeStatus}>{nextStatusIsActive ? 'Activar lista' : 'Desactivar lista'}</Button>
        </footer>
      </section>
    </div>}
  </div>
}
