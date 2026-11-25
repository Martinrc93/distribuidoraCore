import { Pagination } from '../../shared/components/Pagination'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { useLocation } from 'react-router-dom'
import { apiGet, apiPatch, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge, type BadgeTone } from '../../shared/components/Badge'
import { SecondaryRowActions } from '../../shared/components/SecondaryRowActions'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { StockAdjustmentDialog, buildStockAdjustment, type StockAdjustmentMode } from '../../shared/components/StockAdjustmentDialog'
import { SearchableSelect } from '../../shared/components/SearchableSelect'
import { apiGetAllPages } from '../../shared/api/pagination'
import { useUrlListState } from '../../shared/useUrlListState'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { ShrinkageDialog } from '../inventory/ShrinkageDialog'
import { restockPurchasePath } from '../purchasing/supplierOrders'
import { formatMoney, formatNumber } from '../../shared/format'
import { ProductFormModal, productErrorMessage, type CatalogOption, type PriceList, type Product } from './ProductFormModal'
import { ProductMovementsModal, type Movement } from './ProductMovementsModal'

const PRICE_LIST_OPTIONS_PATH = '/api/pricing/lists?page=0&size=20'
const PRICE_LIST_QUERY_KEY = queryKeys.pricing.listOptions('product')

function StatusBadge({ value }: { value: string }) {
  const tone: BadgeTone = value === 'ACTIVE' || value === 'Activo' ? 'strong' : 'muted'
  return <Badge tone={tone}>{value === 'ACTIVE' ? 'Activo' : value === 'INACTIVE' ? 'Inactivo' : value}</Badge>
}

export default function ProductsPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canRecordShrinkage = isAdmin && hasAuthority('STOCK_SHRINKAGE')
  const { search: stockSearch } = useLocation()
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
  const [formProduct, setFormProduct] = useState<Product | undefined>()
  const [showForm, setShowForm] = useState(false)
  const formOpen = isAdmin && (showForm || Boolean(formProduct))
  const query = useQuery({ queryKey: queryKeys.products.list(productPath), queryFn: ({ signal }) => apiGet<ApiPage<Product>>(productPath, signal) })
  const listsQuery = useQuery({ queryKey: PRICE_LIST_QUERY_KEY, queryFn: ({ signal }) => apiGetAllPages<PriceList>(PRICE_LIST_OPTIONS_PATH, signal), enabled: formOpen })
  const categoriesQuery = useQuery({ queryKey: queryKeys.catalog.categories, queryFn: ({ signal }) => apiGet<CatalogOption[]>('/api/categories', signal) })
  const brandsQuery = useQuery({ queryKey: queryKeys.catalog.brands, queryFn: ({ signal }) => apiGet<CatalogOption[]>('/api/brands', signal) })
  const [statusProduct, setStatusProduct] = useState<Product | undefined>()
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [mutating, setMutating] = useState(false)
  const [formSaving, setFormSaving] = useState(false)
  const [selectedProduct, setSelectedProduct] = useState<Product | undefined>()
  const [adjustmentProduct, setAdjustmentProduct] = useState<Product | undefined>()
  const [shrinkageProduct, setShrinkageProduct] = useState<Product>()
  const [shrinkageFocus, setShrinkageFocus] = useState('')
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
  const movementsQuery = useQuery({
    queryKey: queryKeys.products.movements(selectedProduct?.id ?? '', movementsPath),
    queryFn: ({ signal }) => apiGet<ApiPage<Movement>>(movementsPath, signal),
    enabled: Boolean(selectedProduct),
  })

  useEffect(() => {
    if (shrinkageProduct || !shrinkageFocus) return
    const target = document.getElementById(shrinkageFocus) ?? document.getElementById('products-search')
    target?.focus()
    setShrinkageFocus('')
  }, [shrinkageProduct, shrinkageFocus])

  async function shrinkageSaved(productId: string) {
    await invalidateDomains(queryClient, 'stock', 'inventory', 'reports')
    setShrinkageFocus(`product-actions-${productId}`)
    setShrinkageProduct(undefined)
    setFeedback('Merma registrada. El stock fue actualizado.')
  }

  async function submitAdjustment(event: FormEvent) {
    event.preventDefault()
    if (!adjustmentProduct || adjustmentSaving) return
    const request = buildStockAdjustment(adjustmentMode, quantity, reason, Number(adjustmentProduct.stock ?? 0))
    if ('error' in request) { setAdjustmentError(request.error); return }
    setAdjustmentSaving(true)
    setAdjustmentError('')
    try {
      await apiPost(`/api/inventory/${adjustmentProduct.id}/adjustments`, request.body)
      await invalidateDomains(queryClient, 'stock', 'inventory', 'reports')
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
      await invalidateDomains(queryClient, 'stock', 'pricing', 'reports')
      setStatusProduct(undefined)
      setFormProduct(undefined)
      setFeedback(status === 'ACTIVE' ? 'Producto activado correctamente.' : 'Producto desactivado correctamente.')
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) await invalidateDomains(queryClient, 'stock', 'pricing')
      setActionError(productErrorMessage(cause, 'No se pudo actualizar el estado del producto.'))
    } finally {
      setMutating(false)
    }
  }

  const rows = products.map((product) => ({
    id: product.id,
    name: product.name,
    category: product.category,
    brand: product.brandId ? brandNames.get(product.brandId) ?? '—' : '—',
    cost: formatMoney(product.cost, 2),
    stock: String(product.stock ?? 0),
    status: product.status ?? 'ACTIVE',
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Producto', emphasis: true },
    { key: 'brand', label: 'Marca' },
    { key: 'category', label: 'Categoría' },
    ...(isAdmin ? [{ key: 'cost', label: 'Costo', align: 'right' as const, render: (value: string) => <span className="font-bold">{value}</span> }] : []),
    { key: 'stock', label: 'Stock', align: 'right', render: (value) => <span className={`font-bold${Number(value) < 0 ? ' negative-number' : ''}`}>{formatNumber(value, 2)}</span> },
    { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> },
    { key: 'actions', label: 'Acciones', align: 'right', render: (_value, row) => {
      const product = products.find((candidate) => candidate.id === row.id)
      if (!product) return null
      const canRestock = isAdmin && product.status === 'ACTIVE' && product.stock !== undefined && product.stock <= 0
      const canShrink = canRecordShrinkage && product.status !== 'INACTIVE'
      const movements = <Button variant="secondary" onClick={() => setSelectedProduct(product)} aria-label={`Ver movimientos de ${product.name}`}>Ver movimientos</Button>
      return <div className="table-row-actions">
        {hasAuthority('STOCK_ADJUST') && <Button variant="secondary" onClick={() => { setAdjustmentProduct(product); setAdjustmentMode('target'); setQuantity(''); setReason(''); setAdjustmentError('') }} aria-label={`Ajustar stock de ${product.name}`}>Ajustar stock</Button>}
        {isAdmin && <Button variant="secondary" onClick={() => { setFormProduct(product); setShowForm(false); setFeedback('') }} disabled={formSaving || mutating} aria-label={`Editar ${product.name}`}>Editar</Button>}
        {canShrink || canRestock ? <SecondaryRowActions triggerId={`product-actions-${product.id}`} label={'Más acciones de ' + product.name}>
          {movements}
          {canShrink && <Button variant="secondary"
            onClick={() => { setShrinkageProduct(product); setFeedback('') }} aria-label={`Registrar merma de ${product.name}`}>Registrar merma</Button>}
          {canRestock && <Button variant="secondary" href={restockPurchasePath(product.id, 'stock', stockSearch)}
            aria-label={`Reponer ${product.name}`}>Reponer</Button>}
        </SecondaryRowActions> : movements}
      </div>
    } },
  ]

  return <>
    <PageHeader eyebrow="Catálogo" title="Stock" description="Productos, stock, marcas, categorías y costos. Los precios se administran por lista." actions={isAdmin ? <>
      <Button variant="secondary" href={stockSearch ? `/products/shrinkages?stockSearch=${encodeURIComponent(stockSearch)}` : '/products/shrinkages'}>Ver mermas</Button>
      <Button onClick={() => { setFormProduct(undefined); setShowForm((value) => !value); setFeedback('') }} disabled={formSaving || mutating}>+ Nuevo producto</Button>
    </> : <Button variant="secondary" href="/price-lists">Ver listas de precios</Button>} />
    {feedback && <p className="success-text" role="status"><CurrencyText>{feedback}</CurrencyText></p>}
    {actionError && <p className="error-text" role="alert"><CurrencyText>{actionError}</CurrencyText></p>}
    {formOpen && <ProductFormModal initial={formProduct} activeLists={activeLists} categories={categories} brands={brands} optionsLoading={optionsLoading} optionsError={optionsError} onDone={() => { setShowForm(false); setFormProduct(undefined) }} onSuccess={setFeedback} onBusyChange={setFormSaving} onChangeStatus={(product) => { setActionError(''); setStatusProduct(product) }} />}
    <Panel>
      <div className="toolbar items-end">
        <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Buscar productos</span><input id="products-search" className="input search-input [@media(max-width:640px)]:max-w-none" aria-label="Buscar productos" placeholder="Nombre del producto" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
        <SearchableSelect label="Buscar por marca" options={brands} value={brandId} onChange={(value) => setFilter('brandId', value)} allLabel="Todas las marcas" unavailableLabel="Marca no disponible" loadingLabel="Cargando marcas…" loading={brandsQuery.isLoading} disabled={brandsQuery.isError} />
        <SearchableSelect label="Buscar por categoría" options={categories} value={categoryId} onChange={(value) => setFilter('categoryId', value)} allLabel="Todas las categorías" unavailableLabel="Categoría no disponible" loadingLabel="Cargando categorías…" loading={categoriesQuery.isLoading} disabled={categoriesQuery.isError} />
        <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Stock</span><select className="select [@media(max-width:640px)]:w-full [@media(max-width:640px)]:min-w-0" value={stock} onChange={(event) => setFilter('stock', event.target.value)}>
          <option value="">Ambos</option><option value="POSITIVE">Positivo</option><option value="NEGATIVE">Negativo</option>
        </select></label>
      </div>
      {(categoriesQuery.isError || brandsQuery.isError) && <div className="page-actions mb-[18px]"><p className="error-text" role="alert">No se pudieron cargar las marcas o categorías para filtrar.</p><Button variant="secondary" onClick={() => { if (categoriesQuery.isError) void categoriesQuery.refetch(); if (brandsQuery.isError) void brandsQuery.refetch() }}>Reintentar filtros</Button></div>}
      {query.isLoading ? <EmptyState state="loading" title="Cargando productos" description="Consultando productos a través de la API." /> : query.isError ? <EmptyState state="error" title="No se pudieron cargar los productos" description={query.error.message} /> : products.length === 0 ? <EmptyState title={hasFilters ? 'No hay productos para mostrar' : 'Todavía no hay productos'} description={hasFilters ? 'Probá otra búsqueda o cambiá los filtros.' : 'Creá el primer producto para comenzar a gestionar el catálogo.'} action={isAdmin ? <Button onClick={() => setShowForm(true)} disabled={formSaving || mutating}>+ Nuevo producto</Button> : undefined} /> : <><DataTable className="compact-list-table stock-list-table" columns={columns} rows={rows} /><Pagination summary={<>Página {page + 1} · {query.data?.totalElements ?? 0} productos</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= (query.data?.totalPages ?? 0)} /></>}
    </Panel>
    {selectedProduct && <ProductMovementsModal productName={selectedProduct.name} movementsQuery={movementsQuery} onClose={() => setSelectedProduct(undefined)} />}
    {adjustmentProduct && <StockAdjustmentDialog
      productName={adjustmentProduct.name} stock={adjustmentProduct.stock ?? 0}
      mode={adjustmentMode} onModeChange={(mode) => { setAdjustmentMode(mode); setAdjustmentError('') }}
      quantity={quantity} reason={reason} pending={adjustmentSaving} error={adjustmentError}
      onQuantityChange={(value) => { setQuantity(value); setAdjustmentError('') }}
      onReasonChange={(value) => { setReason(value); setAdjustmentError('') }}
      onSubmit={submitAdjustment} onCancel={() => setAdjustmentProduct(undefined)}
    />}
    {canRecordShrinkage && shrinkageProduct && <ShrinkageDialog product={shrinkageProduct}
      onClose={() => setShrinkageProduct(undefined)} onSaved={() => shrinkageSaved(shrinkageProduct.id)} />}
    {statusProduct && <ConfirmationDialog
      title={statusProduct.status === 'ACTIVE' ? 'Desactivar producto' : 'Activar producto'}
      description={`¿Querés ${statusProduct.status === 'ACTIVE' ? 'desactivar' : 'activar'} a ${statusProduct.name}?`}
      cancelLabel="Cancelar" confirmLabel="Confirmar" confirmVariant={statusProduct.status === 'ACTIVE' ? 'danger' : 'primary'} pendingLabel="Guardando..."
      pending={mutating || formSaving} error={actionError}
      onCancel={() => setStatusProduct(undefined)} onConfirm={() => void changeStatus()}
    />}
  </>
}
