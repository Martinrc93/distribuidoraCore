import { CurrencyText } from '../../shared/components/CurrencyText'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
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

type PriceList = { id: string; code: string; name: string; status: string; isDefault: boolean; pendingReviewCount?: number }
/** `followStatus`: PENDING (copied from the default list, not reviewed), CONFIRMED (reviewed, still follows it) or null (own price). */
type ProductPrice = { productId: string; name: string; price: number; effectiveOn?: string; followStatus?: 'PENDING' | 'CONFIRMED' | null }
type FollowFilter = '' | 'PENDING' | 'FOLLOWING' | 'OWN'
type CreationPreview = { productsToCopy: number; defaultListName?: string | null }
type Follower = { priceListId: string; code: string; name: string }
type CatalogOption = { id: string; name: string; status: string }
type FollowerWarning = { followers: Follower[]; amount: number; effectiveOn: string }

const MAX_PRICE_LISTS = 10

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

export default function PriceListsPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const preferencesKey = priceListPreferenceKey()
  const [preferences, setPreferences] = useState(() => readPriceListPreferences(preferencesKey))
  const { visibility: listVisibility, expanded: listExpanded } = preferences
  const { page: listPage, pageSize, setPage: setListPage } = useUrlListState([], 20, 'page')
  const { page: pricePage, setPage: setPricePage } = useUrlListState([], 20, 'pricePage')
  const { page: historyPage, setPage: setHistoryPage } = useUrlListState([], 20, 'historyPage')
  const listsPath = `/api/pricing/lists?page=${listPage}&size=${pageSize}`
  const listsKey = [listsPath]
  const listsQuery = useQuery({ queryKey: listsKey, queryFn: () => apiGet<ApiPage<PriceList>>(listsPath) })
  const lists = listsQuery.data?.content ?? []
  const visibleLists = lists.filter((list) => listVisibility === 'ALL' || list.status === listVisibility)
  const [selectedId, setSelectedId] = useState('')
  const selectedList = lists.find((list) => list.id === selectedId)
  const [editingHistoryProduct, setEditingHistoryProduct] = useState<ProductPrice | undefined>()
  const [historyEntryToCancel, setHistoryEntryToCancel] = useState<PriceHistory | undefined>()
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
  const historyPath = editingHistoryProduct && selectedId
    ? `/api/pricing/lists/${selectedId}/products/${editingHistoryProduct.productId}/history?page=${historyPage}&size=${pageSize}`
    : ''
  const historyKey = [historyPath]
  const historyQuery = useQuery({ queryKey: historyKey, queryFn: () => apiGet<ApiPage<PriceHistory>>(historyPath), enabled: Boolean(historyPath) })
  const [showCreate, setShowCreate] = useState(false)
  const [code, setCode] = useState('')
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
  const listLimitReached = (listsQuery.data?.totalElements ?? 0) >= MAX_PRICE_LISTS
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
    mutationFn: () => apiPost('/api/pricing/lists', { code: code.trim(), name: name.trim() }),
    onSuccess: async () => {
      await refreshPricing()
      const copied = createPreview?.productsToCopy ?? 0
      setCreatePreview(undefined)
      setShowCreate(false)
      setCode('')
      setName('')
      setError('')
      setFeedback(copied > 0
        ? `Lista creada con ${copied} ${copied === 1 ? 'producto marcado' : 'productos marcados'} «Sin revisar».`
        : 'Lista creada correctamente.')
    },
    onError: (cause) => { setCreatePreview(undefined); setError(readableError(cause, 'No se pudo crear la lista.')) },
  })

  async function submitCreate(event: FormEvent) {
    event.preventDefault()
    setError('')
    if (!code.trim() || !name.trim()) {
      setError('Completá el código y el nombre de la lista.')
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
    if (!selectedList || !editingProduct) return
    const amount = parsePriceInput(price)
    if (!price.trim() || !Number.isFinite(amount) || amount < 0) {
      setError('Ingresá un precio válido, igual o mayor a cero.')
      return
    }
    setError('')
    if (selectedList.isDefault) {
      // Lists following the default price change with it: warn once per product and list.
      setSavingPrice(true)
      try {
        const followers = await apiGet<Follower[]>(`/api/pricing/lists/products/${editingProduct.productId}/followers`)
        if (followers.length > 0) {
          setFollowerWarning({ followers, amount, effectiveOn })
          return
        }
      } catch (cause) {
        setError(readableError(cause, 'No se pudo consultar las listas que siguen a esta.'))
        return
      } finally {
        setSavingPrice(false)
      }
    }
    await persistPrice(amount, effectiveOn, false)
  }

  async function persistPrice(amount: number, date: string, acknowledgeFollowers: boolean) {
    if (!selectedList || !editingProduct) return
    setSavingPrice(true)
    setError('')
    try {
      await apiPut(`/api/pricing/lists/${selectedList.id}/products/${editingProduct.productId}`, {
        price: amount,
        ...(date ? { effectiveOn: date } : {}),
        ...(acknowledgeFollowers ? { acknowledgeFollowers: true } : {}),
      })
      await refreshPricing()
      if (editingHistoryProduct?.productId === editingProduct.productId) await queryClient.invalidateQueries({ queryKey: historyKey })
      const followers = followerWarning?.followers ?? []
      setFollowerWarning(undefined)
      setEditingProduct(undefined)
      setEffectiveOn('')
      setFeedback(followers.length > 0
        ? `Precio de ${editingProduct.name} actualizado, también en ${followerNames(followers)}.`
        : `Precio de ${editingProduct.name} actualizado.`)
    } catch (cause) {
      setFollowerWarning(undefined)
      setError(readableError(cause, 'No se pudo actualizar el precio.'))
    } finally {
      setSavingPrice(false)
    }
  }

  async function cancelScheduledPrice() {
    if (!selectedList || !editingHistoryProduct || !historyEntryToCancel?.scheduled) return
    const path = `/api/pricing/lists/${selectedList.id}/products/${editingHistoryProduct.productId}/history/${historyEntryToCancel.effectiveOn}`
    try {
      await apiDelete(path)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: historyKey }),
        queryClient.invalidateQueries({ queryKey: pricesKey }),
      ])
      setHistoryEntryToCancel(undefined)
      setFeedback(`Precio programado para ${historyEntryToCancel.effectiveOn} cancelado.`)
    } catch (cause) {
      setError(readableError(cause, 'No se pudo cancelar el precio programado.'))
    }
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
    { key: 'price', label: 'Precio de lista', align: 'right' },
    { key: 'effectiveOn', label: 'Vigente desde' },
    ...(showFollowState ? [{ key: 'followStatus', label: 'Estado', render: (value: string) => value === 'PENDING'
      ? <Badge tone="strong">Sin revisar</Badge>
      : value === 'CONFIRMED' ? <Badge tone="soft">Sin cambios</Badge> : <Badge tone="muted">Precio propio</Badge> } satisfies TableColumn] : []),
    { key: 'actions', label: 'Acciones', align: 'right', render: (_value: string, row: Row) => {
      const product = pricesQuery.data?.content.find((item) => item.productId === row.id)
      if (!product) return null
      return <div className="table-row-actions">
        {isAdmin && showFollowState && product.followStatus === 'PENDING' && <Button variant="secondary" disabled={confirmingReview} onClick={() => void confirmReview([product.productId])} aria-label={`Confirmar precio de ${product.name} sin cambios`}>Confirmar</Button>}
        {isAdmin && <Button variant="secondary" onClick={() => { setEditingProduct(product); setPrice(String(product.price).replace('.', ',')); setEffectiveOn(''); setError('') }} aria-label={`Editar precio de ${product.name}`}>Editar precio</Button>}
        <Button variant="secondary" onClick={() => { setEditingHistoryProduct(product); setHistoryPage(0); setError('') }} aria-label={`Ver historial de precios de ${product.name}`}>Ver historial</Button>
      </div>
    } },
  ]
  const pendingReviewCount = selectedList?.pendingReviewCount ?? 0
  const priceTableClass = ['price-products-table', isAdmin ? 'price-products-table-admin' : 'price-products-table-standard', showFollowState ? 'price-products-table-follow' : ''].filter(Boolean).join(' ')
  const nextStatusIsActive = statusList?.status !== 'ACTIVE'

  return <div className="price-lists-page">
    <PageHeader eyebrow="Catálogo" title="Listas de precios" description="Cada producto tiene un precio por lista; los pedidos resuelven la lista del cliente o la elegida para la operación." actions={isAdmin ? <Button onClick={() => { setShowCreate((value) => !value); setError('') }} disabled={listLimitReached} title={listLimitReached ? `Se alcanzó el máximo de ${MAX_PRICE_LISTS} listas. Activá y renombrá una lista inactiva.` : undefined}>+ Nueva lista</Button> : undefined} />
    {isAdmin && listLimitReached && <p className="helper-text">Se alcanzó el máximo de {MAX_PRICE_LISTS} listas. Para usar una lista nueva, activá y renombrá una de las inactivas: tomará los precios de {defaultListName}.</p>}
    {feedback && <p className="success-text" role="status"><CurrencyText>{feedback}</CurrencyText></p>}
    {error && !showCreate && !renameList && !editingProduct && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
    {showCreate && isAdmin && <FormModal size="compact" label="Nueva lista de precios" busy={createMutation.isPending} onClose={() => { setShowCreate(false); setError('') }}><Panel title="Nueva lista de precios"><form className="form-grid" onSubmit={submitCreate}>
      <label className="field"><span>Código</span><input className="input" value={code} onChange={(event) => setCode(event.target.value)} required maxLength={40} disabled={createMutation.isPending} /></label>
      <label className="field"><span>Nombre de lista</span><input className="input" value={name} onChange={(event) => setName(event.target.value)} required maxLength={120} disabled={createMutation.isPending} /></label>
      {error && <p className="error-text col-span-full" role="alert"><CurrencyText>{error}</CurrencyText></p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => { setShowCreate(false); setError('') }} disabled={createMutation.isPending || checkingCreate}>Cancelar</Button><Button type="submit" disabled={createMutation.isPending || checkingCreate || Boolean(createPreview)}>{createMutation.isPending || checkingCreate ? 'Guardando...' : 'Guardar lista'}</Button></div>
    </form></Panel>
    {createPreview && <ConfirmationDialog
      title={`¿Crear la lista con los precios de ${createPreview.defaultListName ?? defaultListName}?`}
      description={`Ya hay ${createPreview.productsToCopy} ${createPreview.productsToCopy === 1 ? 'producto cargado' : 'productos cargados'}. La nueva lista va a tomar los precios de ${createPreview.defaultListName ?? defaultListName} y los vas a ver marcados «Sin revisar» hasta que los confirmes o cambies. Mientras no les pongas un precio propio, van a seguir los cambios de ${createPreview.defaultListName ?? defaultListName}.`}
      confirmLabel="Crear lista" confirmVariant="primary" cancelLabel="Volver" pendingLabel="Creando..."
      pending={createMutation.isPending}
      onCancel={() => setCreatePreview(undefined)} onConfirm={() => createMutation.mutate()}
    />}</FormModal>}
    {renameList && <FormModal size="compact" label={`Renombrar ${renameList.code}`} onClose={() => { setRenameList(undefined); setError('') }}><Panel title={`Renombrar ${renameList.code}`}><form className="form-grid" onSubmit={submitRename}>
      <label className="field"><span>Nuevo nombre</span><input className="input" value={rename} onChange={(event) => setRename(event.target.value)} required maxLength={120} /></label>
      {error && <p className="error-text col-span-full" role="alert"><CurrencyText>{error}</CurrencyText></p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => { setRenameList(undefined); setError('') }}>Cancelar</Button><Button type="submit">Guardar nombre</Button></div>
    </form></Panel></FormModal>}
    {listsQuery.isLoading || listsQuery.isError ? <Panel><State error={listsQuery.error} /></Panel> : lists.length === 0 ? <Panel><EmptyState title="Todavía no hay listas" description="Creá una lista de precios para asignar valores de venta a los productos." action={isAdmin ? <Button onClick={() => setShowCreate(true)}>+ Nueva lista</Button> : undefined} /></Panel> : <>
      <Panel title="Listas de precios" description={`${visibleLists.length} ${visibleLists.length === 1 ? 'lista visible' : 'listas visibles'} · Los pedidos usan la lista asignada al cliente.`} action={<div className="price-list-controls">
        <label className="price-list-filter"><span>Mostrar</span><select className="select" aria-label="Mostrar listas" value={listVisibility} onChange={(event) => setPreferences((current) => ({ ...current, visibility: event.target.value as ListVisibility }))}>
          <option value="ALL">Todas</option><option value="ACTIVE">Activas</option><option value="INACTIVE">Inactivas</option>
        </select></label>
        <Button variant="secondary" aria-label={listExpanded ? 'Plegar listas' : 'Desplegar listas'} title={listExpanded ? 'Plegar listas' : 'Desplegar listas'} aria-expanded={listExpanded} aria-controls="price-list-selector" onClick={() => setPreferences((current) => ({ ...current, expanded: !current.expanded }))}>
          <svg className={`price-list-toggle-icon${listExpanded ? ' expanded' : ''}`} viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m5 7.5 5 5 5-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
        </Button>
      </div>}>
        {listExpanded && (visibleLists.length > 0 ? <div id="price-list-selector" className="price-list-selector" role="tablist" aria-label="Listas disponibles">{visibleLists.map((list) => <button className={`price-list-card${selectedId === list.id ? ' selected' : ''}`} type="button" role="tab" aria-selected={selectedId === list.id} key={list.id} onClick={() => { const next = new URLSearchParams(searchParams); next.delete('pricePage'); next.delete('historyPage'); setSearchParams(next); setSelectedId(list.id); setFollowFilter(''); setEditingHistoryProduct(undefined); setFeedback(''); setError('') }}>
          <span className="price-list-card-heading"><strong>{list.name}</strong>{list.isDefault && <span className="price-list-default">Predeterminada</span>}</span>
          <span className="price-list-code">{list.code}</span>
          <span className={`price-list-status${list.status === 'ACTIVE' ? ' active' : ''}`}><i aria-hidden="true" />{list.status === 'ACTIVE' ? 'Activa' : 'Inactiva'}</span>
          {(list.pendingReviewCount ?? 0) > 0 && <span className="price-list-pending">{list.pendingReviewCount} sin revisar</span>}
        </button>)}</div> : <EmptyState title={`No hay listas ${listVisibility === 'ACTIVE' ? 'activas' : 'inactivas'}`} description="Elegí otro filtro para ver las listas disponibles." />)}
        {listExpanded && isAdmin && selectedList && <div className="page-actions price-list-actions"><Button variant="secondary" onClick={() => { setRenameList(selectedList); setRename(selectedList.name); setError('') }}>Renombrar {selectedList.name}</Button><Button variant="secondary" onClick={() => { setError(''); setStatusList(selectedList) }}>{selectedList.status === 'ACTIVE' ? `Desactivar ${selectedList.name}` : `Activar ${selectedList.name}`}</Button></div>}
        {listsQuery.data && listsQuery.data.totalPages > 1 && <div className="pagination"><span>Página {listPage + 1} de {listsQuery.data.totalPages} · {listsQuery.data.totalElements} listas</span><div><Button variant="secondary" onClick={() => setListPage(listPage - 1)} disabled={listPage === 0}>Anterior</Button><Button variant="secondary" onClick={() => setListPage(listPage + 1)} disabled={listPage + 1 >= listsQuery.data.totalPages}>Siguiente</Button></div></div>}
      </Panel>
      <Panel title={selectedList ? `Precios de ${selectedList.name}` : 'Precios'} description={pricesQuery.data ? `${pricesQuery.data.totalElements} productos ${activeFollowFilter || hasProductFilters ? 'con estos filtros' : 'en esta lista'}` : selectedList ? `${selectedList.code}${selectedList.isDefault ? ' · Lista predeterminada' : ''}` : undefined}
        action={showFollowState ? <div className="price-list-controls">
          <label className="price-list-filter"><span>Estado</span><select className="select" aria-label="Filtrar por estado del precio" value={followFilter} onChange={(event) => { setFollowFilter(event.target.value as FollowFilter); setPricePage(0) }}>
            <option value="">Todos</option><option value="PENDING">Sin revisar</option><option value="FOLLOWING">Siguen a {defaultListName}</option><option value="OWN">Precio propio</option>
          </select></label>
          {isAdmin && pendingReviewCount > 0 && <Button variant="secondary" disabled={confirmingReview} onClick={() => void confirmReview()}>{confirmingReview ? 'Confirmando...' : `Confirmar ${pendingReviewCount} sin revisar`}</Button>}
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
    {editingHistoryProduct && <Panel title={`Historial de precios · ${editingHistoryProduct.name}`} action={<Button variant="link" onClick={() => { setEditingHistoryProduct(undefined); setHistoryPage(0) }}>Cerrar historial</Button>}>
      {historyQuery.isLoading ? <EmptyState title="Cargando historial" description="Consultando vigencias registradas." /> : historyQuery.isError ? <EmptyState title="No se pudo cargar el historial" description={historyQuery.error.message} /> : historyQuery.data?.content.length ? <DataTable columns={[
        { key: 'effectiveOn', label: 'Vigente desde', emphasis: true },
        { key: 'price', label: 'Precio', align: 'right' },
        { key: 'recordedAt', label: 'Registrado' },
        { key: 'updatedAt', label: 'Actualizado' },
        { key: 'status', label: 'Estado' },
        { key: 'actions', label: '', render: (_value: string, row: Row) => {
          const entry = historyQuery.data?.content.find((item) => item.effectiveOn === row.id)
          return entry?.scheduled ? <Button variant="link" onClick={() => setHistoryEntryToCancel(entry)}>Cancelar precio del {entry.effectiveOn}</Button> : null
        } },
      ]} rows={historyQuery.data.content.map((entry) => ({
        id: entry.effectiveOn,
        effectiveOn: entry.effectiveOn,
        price: money(entry.price),
        recordedAt: new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(entry.recordedAt)),
        updatedAt: new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(entry.updatedAt)),
        status: entry.scheduled ? 'Programado' : 'Vigente/histórico',
      }))} /> : <EmptyState title="Sin historial" description="Este producto no registra vigencias de precio." />}
      {historyQuery.data && historyQuery.data.totalPages > 1 && <div className="pagination"><span>Página {historyPage + 1} de {historyQuery.data.totalPages} · {historyQuery.data.totalElements} vigencias</span><div><Button variant="secondary" aria-label="Historial anterior" onClick={() => setHistoryPage(historyPage - 1)} disabled={historyPage === 0}>Anterior</Button><Button variant="secondary" aria-label="Siguiente historial" onClick={() => setHistoryPage(historyPage + 1)} disabled={historyPage + 1 >= historyQuery.data.totalPages}>Siguiente</Button></div></div>}
    </Panel>}
    {editingProduct && <div role="dialog" aria-modal="true" aria-labelledby="price-dialog-title" className="modal-backdrop"><Panel title="Editar precio"><form className="form-grid" onSubmit={savePrice}>
      <h2 id="price-dialog-title">{editingProduct.name} · {selectedList?.name}</h2>
      <label className="field"><span>Precio de {editingProduct.name}</span><input className="input currency-input" type="text" inputMode="decimal" value={price} onChange={(event) => setPrice(event.target.value)} required autoFocus /></label>
      <label className="field"><span>Vigente desde</span><input className="input" aria-label="Fecha de vigencia" type="date" value={effectiveOn} onChange={(event) => setEffectiveOn(event.target.value)} /><small>Dejalo vacío para aplicar el precio desde hoy.</small></label>
      {error && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
      {showFollowState && editingProduct.followStatus && <p className="helper-text">Este precio sigue a {defaultListName}. Al guardarlo pasa a ser propio y deja de seguir sus cambios.</p>}
      {selectedList?.isDefault && <p className="helper-text">Si otras listas siguen este precio, se te va a avisar antes de guardar.</p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setEditingProduct(undefined)} disabled={savingPrice}>Cancelar</Button><Button type="submit" disabled={savingPrice}>{savingPrice ? "Guardando..." : "Guardar precio"}</Button></div>
    </form></Panel></div>}
    {followerWarning && editingProduct && <ConfirmationDialog
      title={`Este cambio también se aplica en ${followerNames(followerWarning.followers)}`}
      description={`${followerWarning.followers.length === 1 ? 'Esa lista sigue' : 'Esas listas siguen'} a ${selectedList?.name ?? defaultListName} para ${editingProduct.name}, así que su precio va a cambiar igual. Si confirmás, no se volverá a avisar para este producto en ${followerWarning.followers.length === 1 ? 'esa lista' : 'esas listas'}.`}
      confirmLabel="Guardar y no volver a avisar" confirmVariant="primary" cancelLabel="Volver" pendingLabel="Guardando..."
      pending={savingPrice}
      onCancel={() => setFollowerWarning(undefined)} onConfirm={() => void persistPrice(followerWarning.amount, followerWarning.effectiveOn, true)}
    />}
    {historyEntryToCancel && <div role="dialog" aria-modal="true" aria-labelledby="cancel-price-title" className="modal-backdrop"><Panel title="Cancelar precio programado"><h2 id="cancel-price-title">¿Cancelar la vigencia del {historyEntryToCancel.effectiveOn}?</h2><p>El cambio no modificará precios que ya entraron en vigor.</p><div className="page-actions"><Button variant="secondary" onClick={() => setHistoryEntryToCancel(undefined)}>Volver</Button><Button onClick={cancelScheduledPrice}>Confirmar cancelación</Button></div></Panel></div>}
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
