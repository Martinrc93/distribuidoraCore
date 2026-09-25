import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { apiGet, apiPatch, apiPost, apiPut, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type PriceList = { id: string; code: string; name: string; status: string; isDefault: boolean }
type ProductPrice = { productId: string; sku: string; name: string; price: number }
type Row = Record<string, string>

const LISTS_KEY = ['/api/pricing/lists?page=0&size=20']

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
  const listsQuery = useQuery({ queryKey: LISTS_KEY, queryFn: () => apiGet<ApiPage<PriceList>>('/api/pricing/lists?page=0&size=20') })
  const lists = listsQuery.data?.content ?? []
  const [selectedId, setSelectedId] = useState('')
  const selectedList = lists.find((list) => list.id === selectedId)
  const pricesPath = `/api/pricing/lists/${selectedId}/prices?page=0&size=20`
  const pricesKey = [pricesPath]
  const pricesQuery = useQuery({ queryKey: pricesKey, queryFn: () => apiGet<ApiPage<ProductPrice>>(pricesPath), enabled: Boolean(selectedId) })
  const [showCreate, setShowCreate] = useState(false)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [renameList, setRenameList] = useState<PriceList | undefined>()
  const [rename, setRename] = useState('')
  const [statusList, setStatusList] = useState<PriceList | undefined>()
  const [editingProduct, setEditingProduct] = useState<ProductPrice | undefined>()
  const [price, setPrice] = useState('')
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')

  useEffect(() => {
    if (selectedId && lists.some((list) => list.id === selectedId)) return
    const initial = lists.find((list) => list.status === 'ACTIVE' && list.isDefault) ?? lists.find((list) => list.status === 'ACTIVE')
    setSelectedId(initial?.id ?? lists[0]?.id ?? '')
  }, [lists, selectedId])

  const createMutation = useMutation({
    mutationFn: () => apiPost('/api/pricing/lists', { code: code.trim(), name: name.trim() }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: LISTS_KEY })
      setShowCreate(false)
      setCode('')
      setName('')
      setError('')
      setFeedback('Lista creada correctamente.')
    },
    onError: (cause) => setError(readableError(cause, 'No se pudo crear la lista.')),
  })

  async function submitCreate(event: FormEvent) {
    event.preventDefault()
    setError('')
    if (!code.trim() || !name.trim()) {
      setError('Completá el código y el nombre de la lista.')
      return
    }
    createMutation.mutate()
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
      await queryClient.invalidateQueries({ queryKey: LISTS_KEY })
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
      await queryClient.invalidateQueries({ queryKey: LISTS_KEY })
      setStatusList(undefined)
      setFeedback(status === 'ACTIVE' ? 'Lista activada correctamente.' : 'Lista desactivada correctamente.')
    } catch (cause) {
      setError(readableError(cause, 'No se pudo actualizar el estado de la lista.'))
    }
  }

  async function savePrice(event: FormEvent) {
    event.preventDefault()
    if (!selectedList || !editingProduct) return
    const amount = Number(price)
    if (!price.trim() || !Number.isFinite(amount) || amount < 0) {
      setError('Ingresá un precio válido, igual o mayor a cero.')
      return
    }
    setError('')
    try {
      await apiPut(`/api/pricing/lists/${selectedList.id}/products/${editingProduct.productId}`, { price: amount })
      await queryClient.invalidateQueries({ queryKey: pricesKey })
      setEditingProduct(undefined)
      setFeedback(`Precio de ${editingProduct.name} actualizado.`)
    } catch (cause) {
      setError(readableError(cause, 'No se pudo actualizar el precio.'))
    }
  }

  const rows: Row[] = (pricesQuery.data?.content ?? []).map((item) => ({
    id: item.productId,
    sku: item.sku,
    name: item.name,
    price: money(item.price),
  }))
  const priceColumns: TableColumn[] = [
    { key: 'name', label: 'Producto', emphasis: true },
    { key: 'sku', label: 'SKU' },
    { key: 'price', label: 'Precio de lista', align: 'right' },
    ...(isAdmin ? [{ key: 'actions', label: '', render: (_value: string, row: Row) => <Button variant="link" onClick={() => {
      const product = pricesQuery.data?.content.find((item) => item.productId === row.id)
      if (product) { setEditingProduct(product); setPrice(String(product.price)); setError('') }
    }}>Editar precio de {row.name.toLowerCase()}</Button> }] : []),
  ]

  return <>
    <PageHeader eyebrow="Catálogo" title="Listas de precios" description="Cada producto tiene un precio por lista; los pedidos resuelven la lista del cliente o la elegida para la operación." actions={isAdmin ? <Button onClick={() => { setShowCreate((value) => !value); setError('') }}>+ Nueva lista</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
    {showCreate && isAdmin && <Panel title="Nueva lista de precios"><form className="form-grid" onSubmit={submitCreate}>
      <label className="field"><span>Código</span><input className="input" value={code} onChange={(event) => setCode(event.target.value)} required maxLength={40} disabled={createMutation.isPending} /></label>
      <label className="field"><span>Nombre de lista</span><input className="input" value={name} onChange={(event) => setName(event.target.value)} required maxLength={120} disabled={createMutation.isPending} /></label>
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setShowCreate(false)} disabled={createMutation.isPending}>Cancelar</Button><Button type="submit" disabled={createMutation.isPending}>{createMutation.isPending ? 'Guardando...' : 'Guardar lista'}</Button></div>
    </form></Panel>}
    {renameList && <Panel title={`Renombrar ${renameList.code}`}><form className="form-grid" onSubmit={submitRename}>
      <label className="field"><span>Nuevo nombre</span><input className="input" value={rename} onChange={(event) => setRename(event.target.value)} required maxLength={120} /></label>
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setRenameList(undefined)}>Cancelar</Button><Button type="submit">Guardar nombre</Button></div>
    </form></Panel>}
    {listsQuery.isLoading || listsQuery.isError ? <Panel><State error={listsQuery.error} /></Panel> : lists.length === 0 ? <Panel><EmptyState title="Todavía no hay listas" description="Creá una lista de precios para asignar valores de venta a los productos." action={isAdmin ? <Button onClick={() => setShowCreate(true)}>+ Nueva lista</Button> : undefined} /></Panel> : <>
      <Panel title="Listas disponibles" description="Elegí una lista para consultar y editar sus precios.">
        <div className="price-list-tabs" role="tablist" aria-label="Listas disponibles">{lists.map((list) => <button className={`price-list-tab${selectedId === list.id ? ' selected' : ''}`} type="button" role="tab" aria-selected={selectedId === list.id} key={list.id} onClick={() => { setSelectedId(list.id); setFeedback(''); setError('') }}>
          <strong>{list.name}</strong><span>{list.code}{list.isDefault ? ' · Predeterminada' : ''}</span><span>{list.status === 'ACTIVE' ? 'Activa' : 'Inactiva'}</span>
        </button>)}</div>
        {isAdmin && selectedList && <div className="page-actions price-list-actions"><Button variant="secondary" onClick={() => { setRenameList(selectedList); setRename(selectedList.name); setError('') }}>Renombrar {selectedList.name}</Button><Button variant="secondary" onClick={() => setStatusList(selectedList)}>{selectedList.status === 'ACTIVE' ? `Desactivar ${selectedList.name}` : `Activar ${selectedList.name}`}</Button></div>}
      </Panel>
      <Panel title={selectedList ? `Precios · ${selectedList.name}` : 'Precios'}>
        {pricesQuery.isLoading || pricesQuery.isError ? <State error={pricesQuery.error} /> : rows.length === 0 ? <EmptyState title="Esta lista todavía no tiene precios" description="Los precios aparecen cuando se asignan a productos." /> : <DataTable columns={priceColumns} rows={rows} />}
        {pricesQuery.data && pricesQuery.data.totalElements > 20 && <div className="pagination"><span>Mostrando hasta 20 de {pricesQuery.data.totalElements} productos</span><div><Button variant="secondary" disabled>Anterior</Button><Button variant="secondary" disabled>Siguiente</Button></div></div>}
      </Panel>
    </>}
    {editingProduct && <div role="dialog" aria-modal="true" aria-labelledby="price-dialog-title" className="modal-backdrop"><Panel title="Editar precio"><form className="form-grid" onSubmit={savePrice}>
      <h2 id="price-dialog-title">{editingProduct.name} · {selectedList?.name}</h2>
      <label className="field"><span>Precio de {editingProduct.name}</span><input className="input" type="text" inputMode="decimal" value={price} onChange={(event) => setPrice(event.target.value)} required autoFocus /></label>
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setEditingProduct(undefined)}>Cancelar</Button><Button type="submit">Guardar precio</Button></div>
    </form></Panel></div>}
    {statusList && <div role="dialog" aria-modal="true" aria-labelledby="status-dialog-title" className="modal-backdrop"><Panel title="Confirmar cambio de estado"><h2 id="status-dialog-title">¿Querés {statusList.status === 'ACTIVE' ? 'desactivar' : 'activar'} la lista {statusList.name}?</h2><div className="page-actions"><Button variant="secondary" onClick={() => setStatusList(undefined)}>Cancelar</Button><Button onClick={changeStatus}>Confirmar</Button></div></Panel></div>}
  </>
}
