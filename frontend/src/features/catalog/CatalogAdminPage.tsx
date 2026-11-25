import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState, type FormEvent } from 'react'
import { apiDelete, apiGet, apiPatch, apiPost, apiPut, ApiError } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { moveBetweenTabs } from '../../shared/tabList'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { Badge } from '../../shared/components/Badge'
import { FormModal } from '../../shared/components/FormModal'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type CatalogItem = { id: string; name: string; status: string; productCount?: number }
type Kind = 'brands' | 'categories'
type Row = Record<string, string>

const BRANDS_KEY = queryKeys.catalog.brands
const CATEGORIES_KEY = queryKeys.catalog.categories

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
  const brandsQuery = useQuery({ queryKey: BRANDS_KEY, queryFn: ({ signal }) => apiGet<CatalogItem[]>('/api/brands', signal) })
  const categoriesQuery = useQuery({ queryKey: CATEGORIES_KEY, queryFn: ({ signal }) => apiGet<CatalogItem[]>('/api/categories', signal) })
  const [kind, setKind] = useState<Kind>('brands')
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<CatalogItem | undefined>()
  const [name, setName] = useState('')
  const [deactivateItem, setDeactivateItem] = useState<CatalogItem | undefined>()
  const [deleteItem, setDeleteItem] = useState<CatalogItem | undefined>()
  const catalogTabs = useRef<HTMLDivElement>(null)
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)
  const resource = kind === 'brands'
    ? { singular: 'marca', plural: 'marcas', endpoint: '/api/brands', queryKey: BRANDS_KEY, query: brandsQuery }
    : { singular: 'categoría', plural: 'categorías', endpoint: '/api/categories', queryKey: CATEGORIES_KEY, query: categoriesQuery }
  const items = resource.query.data ?? []
  const formTitle = editing ? 'Editar ' + resource.singular : 'Nueva ' + resource.singular
  const { requestDiscard, discardDialog } = useDiscardChanges({
    hasChanges: showForm && name !== (editing?.name ?? ''),
    onDiscard: () => { setShowForm(false); setEditing(undefined); setError('') },
    disabled: saving,
  })
  const deleteEntry = useMutation({
    mutationFn: (id: string) => apiDelete(`${resource.endpoint}/${id}/permanent`),
    onSuccess: async () => {
      await invalidateDomains(queryClient, 'catalog')
      setDeleteItem(undefined)
      setFeedback(`${capitalize(resource.singular)} eliminada correctamente.`)
      requestAnimationFrame(() => catalogTabs.current?.querySelector<HTMLButtonElement>('button[aria-selected="true"]')?.focus())
    },
    onError: (cause) => {
      if (cause instanceof ApiError && (cause.status === 409 || cause.status === 404)) {
        void invalidateDomains(queryClient, 'catalog')
      }
    },
  })

  function openCreate() {
    setEditing(undefined)
    setName('')
    setError('')
    setShowForm(true)
  }

  function openEdit(item: CatalogItem) {
    setEditing(item)
    setName(item.name)
    setError('')
    setShowForm(true)
  }

  function openDeactivate(item: CatalogItem) {
    setError('')
    setDeactivateItem(item)
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    if (!name.trim()) { setError(`Ingresá el nombre de la ${resource.singular}.`); return }
    setSaving(true)
    setError('')
    try {
      const body = { name: name.trim() }
      if (editing) await apiPut(`${resource.endpoint}/${editing.id}`, body)
      else await apiPost(resource.endpoint, body)
      await invalidateDomains(queryClient, ...(editing ? ['catalog', 'stock', 'pricing', 'reports'] as const : ['catalog'] as const))
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
    if (saving) return
    setSaving(true)
    setError('')
    try {
      await apiPatch(`${resource.endpoint}/${item.id}/status`, { status: 'ACTIVE' })
      await invalidateDomains(queryClient, 'catalog', 'stock', 'pricing')
      setFeedback(`${capitalize(resource.singular)} activada correctamente.`)
    } catch (cause) {
      setError(errorMessage(cause, `No se pudo activar la ${resource.singular}.`))
    } finally {
      setSaving(false)
    }
  }

  async function deactivate() {
    if (!deactivateItem || saving) return
    setSaving(true)
    setError('')
    try {
      await apiDelete(`${resource.endpoint}/${deactivateItem.id}`)
      await invalidateDomains(queryClient, 'catalog', 'stock', 'pricing')
      setFeedback(`${capitalize(resource.singular)} desactivada correctamente.`)
      setDeactivateItem(undefined)
    } catch (cause) {
      setError(errorMessage(cause, `No se pudo desactivar la ${resource.singular}.`))
    } finally { setSaving(false) }
  }

  const rows: Row[] = items.map((item) => ({
    id: item.id,
    name: item.name,
    products: String(item.productCount ?? 0),
    status: item.status,
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Nombre', emphasis: true },
    { key: 'products', label: 'Productos asociados', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'ACTIVE' ? 'strong' : 'muted'}>{value === 'ACTIVE' ? 'Activa' : 'Inactiva'}</Badge> },
    ...(isAdmin ? [{ key: 'actions', label: 'Acciones', render: (_value: string, row: Row) => {
      const item = items.find((candidate) => candidate.id === row.id)
      if (!item) return null
      return <div className="catalog-row-actions flex-wrap">
        <Button variant="secondary" onClick={() => openEdit(item)} aria-label={`Editar ${resource.singular} ${item.name}`}>Editar</Button>
        {item.status === 'ACTIVE'
          ? <Button variant="danger" onClick={() => openDeactivate(item)} aria-label={`Desactivar ${resource.singular} ${item.name}`}>Desactivar</Button>
          : <Button variant="secondary" onClick={() => reactivate(item)} aria-label={`Activar ${resource.singular} ${item.name}`}>Activar</Button>}
        {item.productCount === 0 && <Button variant="danger" aria-label={`Eliminar ${resource.singular} ${item.name}`} onClick={() => {
          deleteEntry.reset()
          setError('')
          setFeedback('')
          setDeleteItem(item)
        }}>Eliminar</Button>}
      </div>
    } }] : []),
  ]

  return <>
    <PageHeader eyebrow="Catálogo" title="Marcas y categorías" description="Organizá el catálogo y mantené asociaciones visibles en los productos." actions={isAdmin ? <Button onClick={openCreate}>+ Nueva {resource.singular}</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && !showForm && !deactivateItem && <p className="error-text" role="alert">{error}</p>}
    <div ref={catalogTabs} className="catalog-tabs" role="tablist" aria-label="Administrar marcas y categorías" onKeyDown={moveBetweenTabs}>
      <button type="button" role="tab" tabIndex={kind === 'brands' ? 0 : -1} aria-selected={kind === 'brands'} className={`catalog-tab${kind === 'brands' ? ' selected' : ''}`} onClick={() => { setKind('brands'); setShowForm(false); setEditing(undefined); setError(''); setFeedback('') }}>Marcas</button>
      <button type="button" role="tab" tabIndex={kind === 'categories' ? 0 : -1} aria-selected={kind === 'categories'} className={`catalog-tab${kind === 'categories' ? ' selected' : ''}`} onClick={() => { setKind('categories'); setShowForm(false); setEditing(undefined); setError(''); setFeedback('') }}>Categorías</button>
    </div>
    {showForm && isAdmin && <FormModal label={formTitle} size="compact" busy={saving} onClose={requestDiscard}>
      <Panel title={formTitle}>
        <form className="grid gap-[14px]" onSubmit={save}>
          <label className="field"><span>Nombre de {resource.singular}</span><input className="input" value={name} onChange={(event) => setName(event.target.value)} required disabled={saving} /></label>
          {error && <p className="error-text" role="alert">{error}</p>}
          <div className="page-actions justify-end">
            <Button variant="secondary" type="button" onClick={requestDiscard} disabled={saving}>Cancelar</Button>
            <Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Guardar ' + resource.singular}</Button>
          </div>
        </form>
      </Panel>
    </FormModal>}
    {discardDialog}
    <Panel title={capitalize(resource.plural)}>
      {resource.query.isLoading ? <EmptyState state="loading" title={`Cargando ${resource.plural}`} description="Consultando el catálogo." /> : resource.query.isError ? <EmptyState state="error" title={`No se pudieron cargar ${resource.plural}`} description={resource.query.error.message} /> : items.length === 0 ? <EmptyState title={`Todavía no hay ${resource.plural}`} description={`Creá la primera ${resource.singular} para organizar los productos.`} action={isAdmin ? <Button onClick={openCreate}>+ Nueva {resource.singular}</Button> : undefined} /> : <DataTable columns={columns} rows={rows} />}
    </Panel>
    {deleteItem && <ConfirmationDialog title={`¿Eliminar «${deleteItem.name}»?`}
      description={`La ${resource.singular} se eliminará definitivamente.`}
      confirmLabel={`Eliminar ${resource.singular}`} cancelLabel="Cancelar" pendingLabel="Eliminando..."
      pending={deleteEntry.isPending}
      error={deleteEntry.error ? errorMessage(deleteEntry.error, `No se pudo eliminar la ${resource.singular}.`) : undefined}
      onConfirm={() => deleteEntry.mutate(deleteItem.id)} onCancel={() => setDeleteItem(undefined)} />}
    {deactivateItem && <ConfirmationDialog title={'¿Desactivar «' + deactivateItem.name + '»?'}
      description="Los productos asociados conservarán el nombre guardado."
      confirmLabel={'Desactivar ' + resource.singular} cancelLabel="Cancelar" pendingLabel="Desactivando..."
      pending={saving} error={error} onConfirm={() => void deactivate()} onCancel={() => { setDeactivateItem(undefined); setError('') }} />}
  </>
}

function capitalize(value: string) {
  return value.charAt(0).toLocaleUpperCase('es-AR') + value.slice(1)
}
