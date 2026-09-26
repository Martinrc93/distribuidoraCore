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
import { useUrlListState } from '../../shared/useUrlListState'

type Product = {
  id: string
  sku?: string | null
  name: string
  category: string
  categoryId?: string | null
  brandId?: string | null
  presentation?: string | null
  cost?: number
  stock?: number
  status?: string
}

type PriceList = { id: string; code: string; name: string; status: string }
type CatalogOption = { id: string; name: string; status: string }
type ProductFormValues = {
  name: string
  categoryId: string
  brandId: string
  cost: string
  prices: Record<string, string>
}

const PRICE_LIST_QUERY_KEY = ['/api/pricing/lists?page=0&size=20']
const CATEGORIES_QUERY_KEY = ['/api/categories']
const BRANDS_QUERY_KEY = ['/api/brands']

function initialFormValues(initial?: Product): ProductFormValues {
  return {
    name: initial?.name ?? '',
    categoryId: initial?.categoryId ?? '',
    brandId: initial?.brandId ?? '',
    cost: initial?.cost === undefined ? '' : String(initial.cost),
    prices: {},
  }
}

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
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
}) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<ProductFormValues>(() => initialFormValues(initial))
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [affectedLists, setAffectedLists] = useState<AffectedPriceList[]>([])
  const categoryOptions = categories.filter((category) => category.status === 'ACTIVE' || category.id === initial?.categoryId)
  const brandOptions = brands.filter((brand) => brand.status === 'ACTIVE' || brand.id === initial?.brandId)

  useEffect(() => {
    setForm(initialFormValues(initial))
    setAffectedLists([])
    setError('')
  }, [initial])

  function change(field: keyof Omit<ProductFormValues, 'prices'>, value: string) {
    setForm((current) => ({
      ...current,
      [field]: value,
    }))
  }

  function changePrice(priceListId: string, value: string) {
    setForm((current) => ({ ...current, prices: { ...current.prices, [priceListId]: value } }))
  }

  const visiblePriceLists = initial
    ? affectedLists.map((list) => ({ id: list.priceListId, code: list.code }))
    : activeLists

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    const cost = Number(form.cost)
    if (!form.name.trim() || !form.categoryId) {
      setError('Completá el nombre y seleccioná una categoría.')
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
      const price = Number(rawPrice)
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

    setSaving(true)
    onBusyChange(true)
    setError('')
    try {
      const body = {
        name: form.name.trim(),
        cost,
        ...(prices.length > 0 ? { prices } : {}),
        categoryId: form.categoryId || null,
        brandId: form.brandId || null,
      }
      if (initial) await apiPut(`/api/products/${initial.id}`, body)
      else await apiPost('/api/products', body)
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/products?') })
      await queryClient.invalidateQueries({ queryKey: PRICE_LIST_QUERY_KEY })
      onSuccess(initial ? 'Producto actualizado correctamente.' : 'Producto creado correctamente.')
      onDone()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/products?') })
      if (cause instanceof ApiError && cause.affectedPriceLists?.length) {
        setAffectedLists(cause.affectedPriceLists)
        setError(errorMessage(cause, 'El costo supera precios vigentes. Completá los precios indicados.'))
      } else {
        setError(errorMessage(cause, 'No se pudo guardar el producto.'))
      }
    } finally {
      setSaving(false)
      onBusyChange(false)
    }
  }

  return <Panel title={initial ? 'Editar producto' : 'Nuevo producto'}>
    <form className="form-grid" onSubmit={submit}>
      <label className="field"><span>Nombre</span><input className="input" value={form.name} onChange={(event) => change('name', event.target.value)} required disabled={saving} /></label>
      <label className="field"><span>Categoría</span><select className="select" value={form.categoryId} onChange={(event) => setForm((current) => ({ ...current, categoryId: event.target.value }))} required disabled={saving || optionsLoading}>
        <option value="">Seleccionar categoría...</option>{categoryOptions.map((category) => <option value={category.id} key={category.id}>{category.name}{category.status !== 'ACTIVE' ? ' · Inactiva' : ''}</option>)}
      </select></label>
      <label className="field"><span>Marca</span><select className="select" value={form.brandId} onChange={(event) => change('brandId', event.target.value)} disabled={saving || optionsLoading}>
        <option value="">Sin marca</option>{brandOptions.map((brand) => <option value={brand.id} key={brand.id}>{brand.name}{brand.status !== 'ACTIVE' ? ' · Inactiva' : ''}</option>)}
      </select></label>
      <label className="field"><span>Costo</span><input className="input" type="text" inputMode="decimal" value={form.cost} onChange={(event) => change('cost', event.target.value)} required disabled={saving} /></label>
      {visiblePriceLists.map((list) => <label className="field" key={list.id}><span>Precio para {list.code}</span><input className="input" type="text" inputMode="decimal" value={form.prices[list.id] ?? ''} onChange={(event) => changePrice(list.id, event.target.value)} required disabled={saving} /></label>)}
      {optionsError && <p className="error-text" role="alert">No se pudieron cargar las listas, marcas o categorías activas.</p>}
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={onDone} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving || optionsLoading}>{saving ? 'Guardando...' : initial ? 'Guardar cambios' : 'Guardar producto'}</Button></div>
    </form>
  </Panel>
}

export default function ProductsPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const search = getFilter('search')
  const searchParam = search.trim() ? `&search=${encodeURIComponent(search.trim())}` : ''
  const productPath = `/api/products?page=${page}&size=${pageSize}${searchParam}`
  const productQueryKey = [productPath]
  const query = useQuery({ queryKey: productQueryKey, queryFn: () => apiGet<ApiPage<Product>>(productPath) })
  const listsQuery = useQuery({ queryKey: PRICE_LIST_QUERY_KEY, queryFn: () => apiGet<ApiPage<PriceList>>('/api/pricing/lists?page=0&size=20'), enabled: isAdmin })
  const categoriesQuery = useQuery({ queryKey: CATEGORIES_QUERY_KEY, queryFn: () => apiGet<CatalogOption[]>('/api/categories'), enabled: isAdmin })
  const brandsQuery = useQuery({ queryKey: BRANDS_QUERY_KEY, queryFn: () => apiGet<CatalogOption[]>('/api/brands'), enabled: isAdmin })
  const [formProduct, setFormProduct] = useState<Product | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [statusProduct, setStatusProduct] = useState<Product | undefined>()
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [mutating, setMutating] = useState(false)
  const [formSaving, setFormSaving] = useState(false)
  const products = query.data?.content ?? []
  const activeLists = (listsQuery.data?.content ?? []).filter((list) => list.status === 'ACTIVE')
  const categories = categoriesQuery.data ?? []
  const brands = brandsQuery.data ?? []
  const optionsLoading = listsQuery.isLoading || categoriesQuery.isLoading || brandsQuery.isLoading
  const optionsError = listsQuery.isError || categoriesQuery.isError || brandsQuery.isError
  const brandNames = new Map(brands.map((brand) => [brand.id, brand.name]))

  async function changeStatus() {
    if (!statusProduct || mutating) return
    setMutating(true)
    setActionError('')
    try {
      const status = statusProduct.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await apiPatch(`/api/products/${statusProduct.id}/status`, { status })
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/products?') })
      setStatusProduct(undefined)
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
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Producto', emphasis: true },
    { key: 'category', label: 'Categoría' },
    { key: 'brand', label: 'Marca' },
    ...(isAdmin ? [{ key: 'cost', label: 'Costo', align: 'right' as const }] : []),
    { key: 'stock', label: 'Stock', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{value}</span> },
    { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> },
    ...(isAdmin ? [{ key: 'actions', label: '', render: (_value: string, row: Record<string, string>) => <div className="page-actions"><Button variant="link" onClick={() => { setFormProduct(products.find((product) => product.id === row.id)); setShowForm(false); setFeedback('') }} disabled={formSaving || mutating}>Editar</Button><Button variant="link" onClick={() => { setStatusProduct(products.find((product) => product.id === row.id)); setFeedback('') }} disabled={formSaving || mutating}>{row.status === 'ACTIVE' ? 'Desactivar producto' : 'Activar producto'}</Button></div> }] : []),
  ]

  return <>
    <PageHeader eyebrow="Catálogo" title="Productos" description="Productos, marcas, categorías y costos. Los precios se administran por lista." actions={isAdmin ? <Button onClick={() => { setFormProduct(undefined); setShowForm((value) => !value); setFeedback('') }} disabled={formSaving || mutating}>+ Nuevo producto</Button> : <Button variant="secondary" href="/price-lists">Ver listas de precios</Button>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {actionError && <p className="error-text" role="alert">{actionError}</p>}
    {isAdmin && (showForm || formProduct) && <ProductForm initial={formProduct} activeLists={activeLists} categories={categories} brands={brands} optionsLoading={optionsLoading} optionsError={optionsError} onDone={() => { setShowForm(false); setFormProduct(undefined) }} onSuccess={setFeedback} onBusyChange={setFormSaving} />}
    <Panel>
      <div className="toolbar"><label className="field"><span>Buscar productos</span><input className="input search-input" aria-label="Buscar productos" placeholder="Nombre o categoría" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label></div>
      {query.isLoading ? <EmptyState title="Cargando productos" description="Consultando productos a través de la API." /> : query.isError ? <EmptyState title="No se pudieron cargar los productos" description={query.error.message} /> : products.length === 0 ? <EmptyState title={search ? 'No hay productos para mostrar' : 'Todavía no hay productos'} description={search ? 'Probá otra búsqueda.' : 'Creá el primer producto para comenzar a gestionar el catálogo.'} action={isAdmin ? <Button onClick={() => setShowForm(true)} disabled={formSaving || mutating}>+ Nuevo producto</Button> : undefined} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} productos</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    {statusProduct && <div role="dialog" aria-modal="true" aria-labelledby="status-dialog-title" className="modal-backdrop"><Panel title="Confirmar cambio de estado"><h2 id="status-dialog-title">¿Querés {statusProduct.status === 'ACTIVE' ? 'desactivar' : 'activar'} a {statusProduct.name}?</h2><div className="page-actions"><Button variant="secondary" onClick={() => setStatusProduct(undefined)} disabled={mutating || formSaving}>Cancelar</Button><Button onClick={changeStatus} disabled={mutating || formSaving}>{mutating ? 'Guardando...' : 'Confirmar'}</Button></div></Panel></div>}
  </>
}
