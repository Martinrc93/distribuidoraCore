import { Pagination } from '../../shared/components/Pagination'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { apiDelete, apiGet, apiPatch, apiPost, apiPut, type ApiPage } from '../../shared/api/client'
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
import { parseOptionalDate } from '../../shared/dates'
import { formatDecimalInput, formatMoney } from '../../shared/format'
import {
  followerNames,
  formatEffectiveDate,
  parsePriceInput,
  priceListPreferenceKey,
  readableError,
  readPriceListPreferences,
  type CatalogOption,
  type CreationPreview,
  type FollowFilter,
  type Follower,
  type FollowerWarning,
  type HistorySelection,
  type PriceHistory,
  type PriceList,
  type ProductPrice,
  type Row,
} from './pricingTypes'
import { PriceListSelectorPanel } from './PriceListSelectorPanel'
import { PriceHistoryModal } from './PriceHistoryModal'
import { SchedulePriceModal } from './SchedulePriceModal'

function money(value: unknown) {
  return formatMoney(value, 4)
}

function State({ error }: { error?: Error | null }) {
  return error
    ? <EmptyState state="error" title="No se pudieron cargar las listas" description={error.message} />
    : <EmptyState state="loading" title="Cargando listas" description="Consultando los precios vigentes." />
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

function InlinePriceInput({ product, disabled, validate, onSave }: {
  product: ProductPrice
  disabled: boolean
  validate: (raw: string) => string
  onSave: (raw: string) => Promise<boolean>
}) {
  const saved = formatDecimalInput(Number(product.price))
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
        const index = inlinePriceIndex(input)
        if (await onSave(draft)) window.setTimeout(() => focusInlinePrice(index + 1), 0)
      }} />
    {error ? <span id={errorId} className="inline-price-error" role="alert">{error}</span>
      : changed && <span className="inline-price-hint">Enter para guardar · Esc para descartar</span>}
  </span>
}

export default function PriceListsPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const preferencesKey = priceListPreferenceKey()
  const [preferences, setPreferences] = useState(() => readPriceListPreferences(preferencesKey))
  const { page: listPage, pageSize, setPage: setListPage } = useUrlListState([], 20, 'page')
  const { page: pricePage, setPage: setPricePage } = useUrlListState([], 20, 'pricePage')
  const { page: historyPage, setPage: setHistoryPage } = useUrlListState([], 20, 'historyPage')
  const listsPath = `/api/pricing/lists?page=${listPage}&size=${pageSize}`
  const listsKey = queryKeys.pricing.lists(listsPath)
  const listsQuery = useQuery({ queryKey: listsKey, queryFn: ({ signal }) => apiGet<ApiPage<PriceList>>(listsPath, signal) })
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
  const priceSearch = searchParams.get('q') ?? ''
  const priceCategoryId = searchParams.get('categoryId') ?? ''
  const priceBrandId = searchParams.get('brandId') ?? ''
  const hasProductFilters = Boolean(priceSearch.trim() || priceCategoryId || priceBrandId)
  const categoriesQuery = useQuery({ queryKey: queryKeys.catalog.categories, queryFn: ({ signal }) => apiGet<CatalogOption[]>('/api/categories', signal) })
  const brandsQuery = useQuery({ queryKey: queryKeys.catalog.brands, queryFn: ({ signal }) => apiGet<CatalogOption[]>('/api/brands', signal) })
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
  const pricesKey = queryKeys.pricing.products(pricesPath)
  const pricesQuery = useQuery({ queryKey: pricesKey, queryFn: ({ signal }) => apiGet<ApiPage<ProductPrice>>(pricesPath, signal), enabled: Boolean(selectedId) })
  const historyBasePath = historySelection ? `/api/pricing/lists/${historySelection.listId}/products/${historySelection.productId}/history` : ''
  const historyPath = historyBasePath ? `${historyBasePath}?page=${historyPage}&size=${pageSize}` : ''
  const historyKey = queryKeys.pricing.history(historyPath)
  const historyQuery = useQuery({ queryKey: historyKey, queryFn: ({ signal }) => apiGet<ApiPage<PriceHistory>>(historyPath, signal), enabled: Boolean(historyPath), retry: false })
  const cancelHistoryPrice = useMutation({
    mutationFn: async (effectiveOnDate: string) => {
      await apiDelete(`${historyBasePath}/${effectiveOnDate}`)
      return effectiveOnDate
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
  const [showCreate, setShowCreate] = useState(false)
  const [name, setName] = useState('')
  const [renameList, setRenameList] = useState<PriceList | undefined>()
  const [rename, setRename] = useState('')
  const [renaming, setRenaming] = useState(false)
  const [statusList, setStatusList] = useState<PriceList | undefined>()
  const [changingStatus, setChangingStatus] = useState(false)
  const [editingProduct, setEditingProduct] = useState<ProductPrice | undefined>()
  const [price, setPrice] = useState('')
  const [effectiveOn, setEffectiveOn] = useState('')
  const isoEffectiveOn = parseOptionalDate(effectiveOn)
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
  const refreshPricing = () => invalidateDomains(queryClient, 'pricing')

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
    if (!renameList || renaming) return
    if (!rename.trim()) {
      setError('Ingresá un nombre para la lista.')
      return
    }
    setRenaming(true)
    setError('')
    try {
      await apiPut(`/api/pricing/lists/${renameList.id}`, { name: rename.trim() })
      await refreshPricing()
      setRenameList(undefined)
      setFeedback('Nombre de lista actualizado.')
    } catch (cause) {
      setError(readableError(cause, 'No se pudo cambiar el nombre de la lista.'))
    } finally {
      setRenaming(false)
    }
  }

  async function changeStatus() {
    if (!statusList || changingStatus) return
    setChangingStatus(true)
    setError('')
    try {
      const status = statusList.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await apiPatch(`/api/pricing/lists/${statusList.id}/status`, { status })
      await refreshPricing()
      setStatusList(undefined)
      setFeedback(status === 'ACTIVE' ? 'Lista activada correctamente.' : 'Lista desactivada correctamente.')
    } catch (cause) {
      setError(readableError(cause, 'No se pudo actualizar el estado de la lista.'))
    } finally {
      setChangingStatus(false)
    }
  }

  async function savePrice(event: FormEvent) {
    event.preventDefault()
    if (!editingProduct || savingPrice || followerWarning || isoEffectiveOn === null) return
    const amount = parsePriceInput(price)
    const invalid = priceError(editingProduct, price, amount)
    if (invalid) {
      setError(invalid)
      return
    }
    setError('')
    await requestPriceSave(editingProduct, amount, isoEffectiveOn)
  }

  function priceError(product: ProductPrice, raw: string, amount: number) {
    if (!raw.trim() || !Number.isFinite(amount) || amount < 0) return 'Ingresá un precio válido, igual o mayor a cero.'
    if (product.cost !== undefined && product.cost !== null && amount < Number(product.cost)) {
      return `El precio no puede ser menor al costo (${money(product.cost)}).`
    }
    return ''
  }

  async function requestPriceSave(product: ProductPrice, amount: number, date: string): Promise<boolean> {
    if (!selectedList) return false
    if (selectedList.isDefault) {
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
        : date ? `Precio de ${product.name} programado para ${formatEffectiveDate(date)}.` : `Precio de ${product.name} actualizado.`)
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
    effectiveOn: formatEffectiveDate(item.effectiveOn),
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

  function reportDocumentAction(message: string) {
    setError('')
    setFeedback(message)
  }

  return <div className="price-lists-page">
    <PageHeader title="Listas de precios" description="Cada producto tiene un precio por lista; los pedidos resuelven la lista del cliente o la elegida para la operación." actions={isAdmin ? <>
      <DocumentActions path="/api/documents/price-lists" filename="listas-de-precios.pdf" downloadLabel="Descargar todas las listas"
        disabled={!listsQuery.data?.totalElements} onDone={reportDocumentAction} />
      <Button onClick={() => { setShowCreate((value) => !value); setError('') }}>+ Nueva lista</Button>
    </> : undefined} />
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
    {renameList && <FormModal size="compact" label={`Renombrar ${renameList.code}`} busy={renaming} onClose={() => { if (!renaming) { setRenameList(undefined); setError('') } }}>
      <Panel title={`Renombrar ${renameList.code}`}>
        <form className="form-grid" onSubmit={submitRename}>
          <label className="field"><span>Nuevo nombre</span><input className="input" value={rename} onChange={(event) => setRename(event.target.value)} required maxLength={120} disabled={renaming} /></label>
          {error && <p className="error-text col-span-full" role="alert"><CurrencyText>{error}</CurrencyText></p>}
          <div className="page-actions">
            <Button variant="secondary" type="button" onClick={() => { setRenameList(undefined); setError('') }} disabled={renaming}>Cancelar</Button>
            <Button type="submit" disabled={renaming}>{renaming ? 'Guardando...' : 'Guardar nombre'}</Button>
          </div>
        </form>
      </Panel>
    </FormModal>}
    {listsQuery.isLoading || listsQuery.isError ? <Panel><State error={listsQuery.error} /></Panel> : lists.length === 0 ? <Panel><EmptyState title="Todavía no hay listas" description="Creá una lista de precios para asignar valores de venta a los productos." action={isAdmin ? <Button onClick={() => setShowCreate(true)}>+ Nueva lista</Button> : undefined} /></Panel> : <>
      <PriceListSelectorPanel
        isAdmin={isAdmin}
        visibleLists={visibleLists}
        selectedId={selectedId}
        selectedList={selectedList}
        hasMultipleLists={hasMultipleLists}
        listVisibility={listVisibility}
        listExpanded={listExpanded}
        listPage={listPage}
        totalPages={listsQuery.data?.totalPages ?? 0}
        totalElements={listsQuery.data?.totalElements ?? 0}
        onChangeVisibility={(visibility) => setPreferences((current) => ({ ...current, visibility }))}
        onToggleExpanded={() => setPreferences((current) => ({ ...current, expanded: !current.expanded }))}
        onSelectList={(listId) => {
          const next = new URLSearchParams(searchParams)
          next.delete('pricePage')
          next.delete('historyPage')
          setSearchParams(next, { replace: true })
          setSelectedId(listId)
          setFollowFilter('')
          setHistorySelection(undefined)
          setFeedback('')
          setError('')
        }}
        onOpenRename={(list) => { setRenameList(list); setRename(list.name); setError('') }}
        onOpenStatus={(list) => { setError(''); setStatusList(list) }}
        onPageChange={setListPage}
        onDocumentDone={reportDocumentAction}
      />
      <Panel title={selectedList ? `Precios de ${selectedList.name}` : 'Precios'} description={pricesQuery.data ? `${pricesQuery.data.totalElements} productos ${activeFollowFilter || hasProductFilters ? 'con estos filtros' : 'en esta lista'}` : selectedList ? `${selectedList.code}${selectedList.isDefault ? ' · Lista predeterminada' : ''}` : undefined}
        action={selectedList ? <div className="price-list-controls">
          {showFollowState && <label className="price-list-filter"><span>Estado</span><select className="select" aria-label="Filtrar por estado del precio" value={followFilter} onChange={(event) => { setFollowFilter(event.target.value as FollowFilter); setPricePage(0) }}>
            <option value="">Todos</option><option value="PENDING">Sin revisar</option><option value="FOLLOWING">Siguen a {defaultListName}</option><option value="OWN">Precio propio</option>
          </select></label>}
          {isAdmin && showFollowState && pendingReviewCount > 0 && <Button variant="secondary" disabled={confirmingReview} onClick={() => void confirmReview()}>{confirmingReview ? 'Confirmando...' : `Confirmar ${pendingReviewCount} sin revisar`}</Button>}
          <DocumentActions path={`/api/documents/price-lists/${selectedList.id}${productFilterParams ? `?${productFilterParams.slice(1)}` : ''}`}
            filename={`lista-${selectedList.code}.pdf`} printLabel="Imprimir lista" downloadLabel={hasProductFilters ? 'Descargar PDF filtrado' : undefined}
            onDone={reportDocumentAction} />
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
        {pricesQuery.data && pricesQuery.data.totalPages > 1 && <Pagination summary={<>Página {pricePage + 1} de {pricesQuery.data.totalPages} · {pricesQuery.data.totalElements} productos</>} onPrevious={() => setPricePage(pricePage - 1)} onNext={() => setPricePage(pricePage + 1)} previousDisabled={pricePage === 0} nextDisabled={pricePage + 1 >= pricesQuery.data.totalPages} />}
      </Panel>
    </>}
    {historySelection && <PriceHistoryModal
      historySelection={historySelection}
      isAdmin={isAdmin}
      historyPage={historyPage}
      onChangePage={setHistoryPage}
      historyQuery={historyQuery}
      cancelHistoryPrice={cancelHistoryPrice}
      historyDateToCancel={historyDateToCancel}
      onRequestCancelDate={setHistoryDateToCancel}
      onClose={closeHistory}
    />}
    {editingProduct && <SchedulePriceModal
      editingProduct={editingProduct}
      listName={selectedList?.name ?? defaultListName}
      defaultListName={defaultListName}
      isDefaultList={Boolean(selectedList?.isDefault)}
      showFollowState={showFollowState}
      price={price}
      effectiveOn={effectiveOn}
      isoEffectiveOn={isoEffectiveOn}
      savingPrice={savingPrice}
      error={error}
      onChangePrice={setPrice}
      onChangeEffectiveOn={setEffectiveOn}
      onSubmit={savePrice}
      onClose={closePriceEditor}
    />}
    {priceDiscardDialog}
    {followerWarning && <ConfirmationDialog
      title={`Este cambio también se aplica en ${followerNames(followerWarning.followers)}`}
      description={`${followerWarning.followers.length === 1 ? 'Esa lista sigue' : 'Esas listas siguen'} a ${selectedList?.name ?? defaultListName} para ${followerWarning.product.name}, así que su precio va a cambiar igual. Si confirmás, no se volverá a avisar para este producto en ${followerWarning.followers.length === 1 ? 'esa lista' : 'esas listas'}.`}
      confirmLabel="Guardar y no volver a avisar" confirmVariant="primary" cancelLabel="Volver" pendingLabel="Guardando..."
      pending={savingPrice}
      onCancel={() => setFollowerWarning(undefined)} onConfirm={() => void persistPrice(followerWarning.product, followerWarning.amount, followerWarning.effectiveOn, true)}
    />}
    {statusList && <ConfirmationDialog
      title={nextStatusIsActive ? 'Activar lista' : 'Desactivar lista'}
      description={nextStatusIsActive && !statusList.isDefault
        ? `Se activará «${statusList.name}». Los productos que no tengan precio en ella van a tomar el precio de ${defaultListName}, quedarán marcados «Sin revisar» y seguirán sus cambios hasta que les pongas un precio propio.`
        : `Se ${nextStatusIsActive ? 'activará' : 'desactivará'} la lista «${statusList.name}».`}
      confirmLabel={nextStatusIsActive ? 'Activar lista' : 'Desactivar lista'}
      cancelLabel="Cancelar"
      confirmVariant={nextStatusIsActive ? 'primary' : 'danger'}
      pendingLabel={nextStatusIsActive ? 'Activando...' : 'Desactivando...'}
      pending={changingStatus}
      error={error || undefined}
      onCancel={() => { if (!changingStatus) { setError(''); setStatusList(undefined) } }}
      onConfirm={() => void changeStatus()}
    />}
  </div>
}
