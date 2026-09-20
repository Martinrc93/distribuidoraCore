import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { apiGet, apiPatch, apiPost, apiPut, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge, type BadgeTone } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type Product = {
  id: string
  sku: string
  name: string
  category: string
  presentation: string
  cost: number
  price: number
  stock?: number
  status?: string
}

type ProductFormValues = Omit<Product, 'cost' | 'price' | 'stock' | 'status'> & { cost: string; price: string }
const PRODUCT_QUERY_KEY = ['/api/products?page=0&size=20']

function initialFormValues(initial?: Product): ProductFormValues {
  return { id: initial?.id ?? '', sku: initial?.sku ?? '', name: initial?.name ?? '', category: initial?.category ?? '', presentation: initial?.presentation ?? 'Unidad', cost: initial ? String(initial.cost) : '', price: initial ? String(initial.price) : '' }
}

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(Number(value ?? 0))
}

function errorMessage(cause: unknown, fallback: string) {
  const status = cause instanceof ApiError ? cause.status : undefined
  const message = cause instanceof ApiError ? cause.detail ?? '' : cause instanceof Error ? cause.message : ''
  if (status !== undefined) {
    if (status === 400) return message || 'Revisá los datos ingresados.'
    if (status === 403) return 'No tenés permisos para realizar esta operación.'
    if (status === 404) return 'El producto ya no existe o no está disponible.'
    if (status === 409) return message || 'El SKU ya existe.'
    return message || fallback
  }
  if (/403|forbidden|permiso/i.test(message)) return 'No tenés permisos para realizar esta operación.'
  if (/404|not found|no existe/i.test(message)) return 'El producto ya no existe o no está disponible.'
  if (/409|conflict|ya existe|duplic/i.test(message)) return message || 'El SKU ya existe.'
  if (/400|invalid|inválid/i.test(message)) return message || 'Revisá los datos ingresados.'
  return message || fallback
}

function StatusBadge({ value }: { value: string }) {
  const tone: BadgeTone = value === 'ACTIVE' || value === 'Activo' ? 'strong' : 'muted'
  return <Badge tone={tone}>{value === 'ACTIVE' ? 'Activo' : value === 'INACTIVE' ? 'Inactivo' : value}</Badge>
}

function ProductForm({ initial, onDone, onSuccess, onBusyChange }: { initial?: Product; onDone: () => void; onSuccess: (message: string) => void; onBusyChange: (busy: boolean) => void }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<ProductFormValues>(() => initialFormValues(initial))
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    setForm(initialFormValues(initial))
    setError('')
  }, [initial])
  function change(field: keyof ProductFormValues, value: string) { setForm((current) => ({ ...current, [field]: value })) }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    const cost = Number(form.cost)
    const price = Number(form.price)
    if (!form.cost.trim() || !form.price.trim() || !Number.isFinite(cost) || !Number.isFinite(price) || cost < 0 || price < 0) {
      setError('El costo y el precio deben ser números no negativos.')
      return
    }
    setSaving(true)
    onBusyChange(true)
    setError('')
    try {
      const body = { sku: form.sku, name: form.name, category: form.category, presentation: form.presentation, cost, price }
      if (initial) await apiPut(`/api/products/${initial.id}`, body)
      else await apiPost('/api/products', body)
      await queryClient.invalidateQueries({ queryKey: PRODUCT_QUERY_KEY })
      onSuccess(initial ? 'Producto actualizado correctamente.' : 'Producto creado correctamente.')
      onDone()
    } catch (cause) {
      setError(errorMessage(cause, 'No se pudo guardar el producto.'))
    } finally {
      setSaving(false)
      onBusyChange(false)
    }
  }

  return <Panel title={initial ? 'Editar producto' : 'Nuevo producto'}><form className="form-grid" onSubmit={submit}>
    {(['sku', 'name', 'category', 'presentation', 'cost', 'price'] as const).map((field) => { const numeric = field === 'cost' || field === 'price'; return <label className="field" key={field}><span>{field === 'sku' ? 'SKU' : field === 'name' ? 'Nombre' : field === 'category' ? 'Categoría' : field === 'presentation' ? 'Presentación' : field === 'cost' ? 'Costo' : 'Precio'}</span><input className="input" type="text" inputMode={numeric ? 'decimal' : undefined} value={form[field]} onChange={(event) => change(field, event.target.value)} required={!numeric} disabled={saving} /></label> })}
    {error && <p className="error-text" role="alert">{error}</p>}
    <div className="page-actions"><Button variant="secondary" type="button" onClick={onDone} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : initial ? 'Guardar cambios' : 'Guardar producto'}</Button></div>
  </form></Panel>
}

export default function ProductsPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: PRODUCT_QUERY_KEY, queryFn: () => apiGet<ApiPage<Product>>('/api/products?page=0&size=20') })
  const [formProduct, setFormProduct] = useState<Product | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [statusProduct, setStatusProduct] = useState<Product | undefined>()
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [mutating, setMutating] = useState(false)
  const [formSaving, setFormSaving] = useState(false)
  const products = query.data?.content ?? []

  async function changeStatus() {
    if (!statusProduct || mutating) return
    setMutating(true)
    setActionError('')
    try {
      const status = statusProduct.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await apiPatch(`/api/products/${statusProduct.id}/status`, { status })
      await queryClient.invalidateQueries({ queryKey: PRODUCT_QUERY_KEY })
      setStatusProduct(undefined)
      setFeedback(status === 'ACTIVE' ? 'Producto activado correctamente.' : 'Producto desactivado correctamente.')
    } catch (cause) {
      setActionError(errorMessage(cause, 'No se pudo actualizar el estado del producto.'))
    } finally {
      setMutating(false)
    }
  }

  const rows = products.map((product) => ({ id: product.id, name: product.name, sku: product.sku, category: product.category, presentation: product.presentation, cost: money(product.cost), price: money(product.price), stock: String(product.stock ?? 0), status: product.status ?? 'ACTIVE' }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Producto', emphasis: true }, { key: 'sku', label: 'SKU' }, { key: 'category', label: 'Categoría' }, { key: 'presentation', label: 'Presentación' }, { key: 'cost', label: 'Costo', align: 'right' }, { key: 'price', label: 'Lista general', align: 'right' }, { key: 'stock', label: 'Stock', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{value}</span> }, { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> },
     ...(isAdmin ? [{ key: 'actions', label: '', render: (_value: string, row: Record<string, string>) => <div className="page-actions"><Button variant="link" onClick={() => { setFormProduct(products.find((product) => product.id === row.id)); setShowForm(false); setFeedback('') }} disabled={formSaving || mutating}>Editar</Button><Button variant="link" onClick={() => { setStatusProduct(products.find((product) => product.id === row.id)); setFeedback('') }} disabled={formSaving || mutating}>{row.status === 'ACTIVE' ? 'Desactivar producto' : 'Activar producto'}</Button></div> }] : []),
  ]

  return <>
    <PageHeader eyebrow="Catálogo" title="Productos" description="Productos, marcas, presentaciones, costos y precios." actions={isAdmin ? <Button onClick={() => { setFormProduct(undefined); setShowForm((value) => !value); setFeedback('') }} disabled={formSaving || mutating}>+ Nuevo producto</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {actionError && <p className="error-text" role="alert">{actionError}</p>}
    {isAdmin && (showForm || formProduct) && <ProductForm initial={formProduct} onDone={() => { setShowForm(false); setFormProduct(undefined) }} onSuccess={setFeedback} onBusyChange={setFormSaving} />}
    <Panel><div className="toolbar"><input className="input search-input" placeholder="Buscar productos..." aria-label="Buscar productos" /><select className="select" aria-label="Filtrar categoría"><option>Todas las categorías</option></select><Button variant="secondary">Filtrar</Button></div>
       {query.isLoading ? <EmptyState title="Cargando productos" description="Consultando productos a través de la API." /> : query.isError ? <EmptyState title="No se pudieron cargar los productos" description={query.error.message} /> : products.length === 0 ? <EmptyState title="Todavía no hay productos" description="Creá el primer producto para comenzar a gestionar el catálogo." action={isAdmin ? <Button onClick={() => setShowForm(true)} disabled={formSaving || mutating}>+ Nuevo producto</Button> : undefined} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Mostrando hasta 20 de {query.data?.totalElements ?? 0} resultados</span><div><Button variant="secondary" disabled>Anterior</Button><Button variant="secondary" disabled={(query.data?.totalElements ?? 0) <= 20}>Siguiente</Button></div></div></>}
    </Panel>
    {statusProduct && <div role="dialog" aria-modal="true" aria-labelledby="status-dialog-title" className="modal-backdrop"><Panel title="Confirmar cambio de estado"><h2 id="status-dialog-title">¿Querés {statusProduct.status === 'ACTIVE' ? 'desactivar' : 'activar'} a {statusProduct.name}?</h2><div className="page-actions"><Button variant="secondary" onClick={() => setStatusProduct(undefined)} disabled={mutating || formSaving}>Cancelar</Button><Button onClick={changeStatus} disabled={mutating || formSaving}>{mutating ? 'Guardando...' : 'Confirmar'}</Button></div></Panel></div>}
  </>
}
