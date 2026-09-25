import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiDelete, apiGet, apiPatch, apiPost, apiPut, ApiError } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type CatalogItem = { id: string; name: string; code?: string | null; status: string; productCount?: number }
type Kind = 'brands' | 'categories'
type Row = Record<string, string>

const BRANDS_KEY = ['/api/brands']
const CATEGORIES_KEY = ['/api/categories']

function errorMessage(cause: unknown, fallback: string) {
  if (cause instanceof ApiError) {
    if (cause.status === 403) return 'No tenés permisos para administrar el catálogo.'
    if (cause.status === 404) return 'El elemento del catálogo ya no existe.'
    return cause.detail || fallback
  }
  return cause instanceof Error ? cause.message : fallback
}

export default function CatalogAdminPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const brandsQuery = useQuery({ queryKey: BRANDS_KEY, queryFn: () => apiGet<CatalogItem[]>('/api/brands') })
  const categoriesQuery = useQuery({ queryKey: CATEGORIES_KEY, queryFn: () => apiGet<CatalogItem[]>('/api/categories') })
  const [kind, setKind] = useState<Kind>('brands')
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<CatalogItem | undefined>()
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [deactivateItem, setDeactivateItem] = useState<CatalogItem | undefined>()
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)
  const resource = kind === 'brands'
    ? { singular: 'marca', plural: 'marcas', endpoint: '/api/brands', queryKey: BRANDS_KEY, query: brandsQuery }
    : { singular: 'categoría', plural: 'categorías', endpoint: '/api/categories', queryKey: CATEGORIES_KEY, query: categoriesQuery }
  const items = resource.query.data ?? []

  function openCreate() {
    setEditing(undefined)
    setName('')
    setCode('')
    setError('')
    setShowForm(true)
  }

  function openEdit(item: CatalogItem) {
    setEditing(item)
    setName(item.name)
    setCode(item.code ?? '')
    setError('')
    setShowForm(true)
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    if (!name.trim()) { setError(`Ingresá el nombre de la ${resource.singular}.`); return }
    setSaving(true)
    setError('')
    try {
      const body = { name: name.trim(), code: code.trim() || null }
      if (editing) await apiPut(`${resource.endpoint}/${editing.id}`, body)
      else await apiPost(resource.endpoint, body)
      await queryClient.invalidateQueries({ queryKey: resource.queryKey })
      setFeedback(editing ? `${capitalize(resource.singular)} actualizada correctamente.` : `${capitalize(resource.singular)} creada correctamente.`)
      setShowForm(false)
      setEditing(undefined)
    } catch (cause) {
      setError(errorMessage(cause, `No se pudo guardar la ${resource.singular}.`))
    } finally {
      setSaving(false)
    }
  }

  async function reactivate(item: CatalogItem) {
    setError('')
    try {
      await apiPatch(`${resource.endpoint}/${item.id}/status`, { status: 'ACTIVE' })
      await queryClient.invalidateQueries({ queryKey: resource.queryKey })
      setFeedback(`${capitalize(resource.singular)} activada correctamente.`)
    } catch (cause) {
      setError(errorMessage(cause, `No se pudo activar la ${resource.singular}.`))
    }
  }

  async function deactivate() {
    if (!deactivateItem) return
    setError('')
    try {
      await apiDelete(`${resource.endpoint}/${deactivateItem.id}`)
      await queryClient.invalidateQueries({ queryKey: resource.queryKey })
      setFeedback(`${capitalize(resource.singular)} desactivada correctamente.`)
      setDeactivateItem(undefined)
    } catch (cause) {
      setError(errorMessage(cause, `No se pudo desactivar la ${resource.singular}.`))
    }
  }

  const rows: Row[] = items.map((item) => ({
    id: item.id,
    name: item.name,
    code: item.code ?? '—',
    products: String(item.productCount ?? 0),
    status: item.status,
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Nombre', emphasis: true },
    { key: 'code', label: 'Código' },
    { key: 'products', label: 'Productos asociados', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'ACTIVE' ? 'strong' : 'muted'}>{value === 'ACTIVE' ? 'Activa' : 'Inactiva'}</Badge> },
    ...(isAdmin ? [{ key: 'actions', label: '', render: (_value: string, row: Row) => {
      const item = items.find((candidate) => candidate.id === row.id)
      if (!item) return null
      return <div className="page-actions">
        <Button variant="link" onClick={() => openEdit(item)}>Editar {resource.singular}</Button>
        {item.status === 'ACTIVE'
          ? <Button variant="link" onClick={() => setDeactivateItem(item)}>Eliminar {resource.singular} {item.name}</Button>
          : <Button variant="link" onClick={() => reactivate(item)}>Activar {resource.singular}</Button>}
      </div>
    } }] : []),
  ]

  return <>
    <PageHeader eyebrow="Catálogo" title="Marcas y categorías" description="Organizá el catálogo y mantené asociaciones visibles en los productos." actions={isAdmin ? <Button onClick={openCreate}>+ Nueva {resource.singular}</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && !showForm && <p className="error-text" role="alert">{error}</p>}
    <div className="catalog-tabs" role="tablist" aria-label="Administrar marcas y categorías">
      <button type="button" role="tab" aria-selected={kind === 'brands'} className={`catalog-tab${kind === 'brands' ? ' selected' : ''}`} onClick={() => { setKind('brands'); setShowForm(false); setEditing(undefined); setError(''); setFeedback('') }}>Marcas</button>
      <button type="button" role="tab" aria-selected={kind === 'categories'} className={`catalog-tab${kind === 'categories' ? ' selected' : ''}`} onClick={() => { setKind('categories'); setShowForm(false); setEditing(undefined); setError(''); setFeedback('') }}>Categorías</button>
    </div>
    {showForm && isAdmin && <Panel title={editing ? `Editar ${resource.singular}` : `Nueva ${resource.singular}`}><form className="form-grid" onSubmit={save}>
      <label className="field"><span>Nombre de {resource.singular}</span><input className="input" value={name} onChange={(event) => setName(event.target.value)} required disabled={saving} /></label>
      <label className="field"><span>Código de {resource.singular}</span><input className="input" value={code} onChange={(event) => setCode(event.target.value)} disabled={saving} /></label>
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setShowForm(false)} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : editing ? `Guardar ${resource.singular}` : `Guardar ${resource.singular}`}</Button></div>
    </form></Panel>}
    <Panel title={capitalize(resource.plural)}>
      {resource.query.isLoading ? <EmptyState title={`Cargando ${resource.plural}`} description="Consultando el catálogo." /> : resource.query.isError ? <EmptyState title={`No se pudieron cargar ${resource.plural}`} description={resource.query.error.message} /> : items.length === 0 ? <EmptyState title={`Todavía no hay ${resource.plural}`} description={`Creá la primera ${resource.singular} para organizar los productos.`} action={isAdmin ? <Button onClick={openCreate}>+ Nueva {resource.singular}</Button> : undefined} /> : <DataTable columns={columns} rows={rows} />}
    </Panel>
    {deactivateItem && <div role="dialog" aria-modal="true" aria-labelledby="catalog-status-title" className="modal-backdrop"><Panel title={`Desactivar ${resource.singular}`}><h2 id="catalog-status-title">¿Desactivar {deactivateItem.name}?</h2><p>Los productos asociados conservarán el nombre guardado.</p><div className="page-actions"><Button variant="secondary" onClick={() => setDeactivateItem(undefined)}>Cancelar</Button><Button onClick={deactivate}>Confirmar</Button></div></Panel></div>}
  </>
}

function capitalize(value: string) {
  return value.charAt(0).toLocaleUpperCase('es-AR') + value.slice(1)
}
