import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPatch, apiPost, apiPut, ApiError, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'

type Seller = { id: string; userId: string; displayName: string; email: string; status: string; assignedCustomersCount: number }
type User = { id: string; email: string; status: string }
type Order = { id: string; number: string; customer: string; status: string }
type Row = Record<string, string>
const USERS_PATH = '/api/users?page=0&size=20'
const USERS_KEY = [USERS_PATH]
const ORDERS_PATH = '/api/orders?page=0&size=20&search=&status='
const ORDERS_KEY = [ORDERS_PATH]

function readable(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 403) return 'No tenés permiso para administrar vendedores.'
  return cause instanceof Error ? cause.message : 'No se pudo completar la operación.'
}

export default function SellersPage() {
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['search', 'status'])
  const search = getFilter('search')
  const status = getFilter('status')
  const sellersPath = `/api/sellers?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}&status=${encodeURIComponent(status)}`
  const sellersKey = [sellersPath]
  const sellersQuery = useQuery({ queryKey: sellersKey, queryFn: () => apiGet<ApiPage<Seller>>(sellersPath) })
  const usersQuery = useQuery({ queryKey: USERS_KEY, queryFn: () => apiGet<ApiPage<User>>(USERS_PATH) })
  const ordersQuery = useQuery({ queryKey: ORDERS_KEY, queryFn: () => apiGet<ApiPage<Order>>(ORDERS_PATH) })
  const sellers = sellersQuery.data?.content ?? []
  const users = usersQuery.data?.content ?? []
  const orders = ordersQuery.data?.content ?? []
  const [showCreate, setShowCreate] = useState(false)
  const [userId, setUserId] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [editingSeller, setEditingSeller] = useState<Seller | undefined>()
  const [reassignCustomers, setReassignCustomers] = useState(false)
  const [sourceSellerId, setSourceSellerId] = useState('')
  const [targetSellerId, setTargetSellerId] = useState('')
  const [reassignPendingOrders, setReassignPendingOrders] = useState(false)
  const [confirmCustomerReassign, setConfirmCustomerReassign] = useState(false)
  const [reassignOrders, setReassignOrders] = useState(false)
  const [selectedOrderIds, setSelectedOrderIds] = useState<string[]>([])
  const [onlyPendingOrders, setOnlyPendingOrders] = useState(true)
  const [confirmOrderReassign, setConfirmOrderReassign] = useState(false)
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)

  function openCreate() {
    setEditingSeller(undefined)
    setUserId('')
    setDisplayName('')
    setShowCreate(true)
    setError('')
  }

  function openEdit(seller: Seller) {
    setEditingSeller(seller)
    setDisplayName(seller.displayName)
    setUserId(seller.userId)
    setShowCreate(true)
    setError('')
  }

  async function saveSeller(event: FormEvent) {
    event.preventDefault()
    if (!displayName.trim()) { setError('Ingresá un nombre para mostrar.'); return }
    setSaving(true)
    setError('')
    try {
      if (editingSeller) await apiPut(`/api/sellers/${editingSeller.id}`, { displayName: displayName.trim() })
      else {
        if (!userId) { setError('Seleccioná un usuario para vincular.'); setSaving(false); return }
        await apiPost('/api/sellers', { userId, displayName: displayName.trim() })
      }
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/sellers?') })
      setShowCreate(false)
      setFeedback(editingSeller ? 'Vendedor actualizado correctamente.' : 'Vendedor creado correctamente.')
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  async function setStatus(seller: Seller) {
    const status = seller.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    setSaving(true)
    setError('')
    try {
      await apiPatch(`/api/sellers/${seller.id}/status`, { status })
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/sellers?') })
      setFeedback(status === 'ACTIVE' ? 'Vendedor activado.' : 'Vendedor desactivado.')
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  async function confirmReassignCustomers() {
    if (!sourceSellerId || !targetSellerId || sourceSellerId === targetSellerId) { setError('Elegí dos vendedores distintos.'); return }
    setSaving(true)
    setError('')
    try {
      const result = await apiPost<{ reassignedCustomersCount: number; reassignedOrdersCount: number }>('/api/sellers/reassign-customers', {
        sourceSellerId, targetSellerId, reassignPendingOrders,
      })
      await Promise.all([
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/sellers?') }),
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/customers?') }),
        queryClient.invalidateQueries({ queryKey: ['/api/orders'] }),
      ])
      setFeedback(`${result.reassignedCustomersCount} clientes y ${result.reassignedOrdersCount} pedidos reasignados.`)
      setReassignCustomers(false)
      setConfirmCustomerReassign(false)
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  async function confirmReassignOrders() {
    if (!targetSellerId || selectedOrderIds.length === 0) { setError('Elegí un vendedor de destino y al menos un pedido.'); return }
    setSaving(true)
    setError('')
    try {
      const result = await apiPost<{ reassignedOrdersCount: number }>('/api/sellers/reassign-orders', { targetSellerId, orderIds: selectedOrderIds, onlyPending: onlyPendingOrders })
      await queryClient.invalidateQueries({ queryKey: ORDERS_KEY })
      setFeedback(`${result.reassignedOrdersCount} pedidos reasignados.`)
      setReassignOrders(false)
      setConfirmOrderReassign(false)
      setSelectedOrderIds([])
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  const rows: Row[] = sellers.map((seller) => ({ id: seller.id, name: seller.displayName, email: seller.email, customers: String(seller.assignedCustomersCount), status: seller.status }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Vendedor', emphasis: true },
    { key: 'email', label: 'Usuario' },
    { key: 'customers', label: 'Clientes asignados', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'ACTIVE' ? 'strong' : 'muted'}>{value === 'ACTIVE' ? 'Activo' : 'Inactivo'}</Badge> },
    { key: 'actions', label: '', render: (_value, row) => {
      const seller = sellers.find((item) => item.id === row.id)
      return seller && <div className="page-actions"><Button variant="link" onClick={() => openEdit(seller)}>Editar vendedor</Button><Button variant="link" onClick={() => void setStatus(seller)} disabled={saving}>{seller.status === 'ACTIVE' ? 'Desactivar vendedor' : 'Activar vendedor'}</Button></div>
    } },
  ]

  return <>
    <PageHeader eyebrow="Administración" title="Vendedores" description="Perfiles comerciales, clientes asignados y reasignaciones." actions={<div className="page-actions"><Button onClick={openCreate}>+ Nuevo vendedor</Button><Button variant="secondary" onClick={() => { setReassignCustomers(true); setError('') }}>Reasignar clientes</Button><Button variant="secondary" onClick={() => { setReassignOrders(true); setError('') }}>Reasignar pedidos</Button></div>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
    {showCreate && <Panel title={editingSeller ? 'Editar vendedor' : 'Nuevo vendedor'}><form className="form-grid" onSubmit={saveSeller}>
      {!editingSeller && <label className="field"><span>Usuario vinculado</span><select className="select" value={userId} onChange={(event) => setUserId(event.target.value)} required disabled={saving}><option value="">Seleccionar usuario...</option>{users.filter((user) => user.status === 'ACTIVE').map((user) => <option value={user.id} key={user.id}>{user.email}</option>)}</select></label>}
      <label className="field"><span>Nombre para mostrar</span><input className="input" value={displayName} onChange={(event) => setDisplayName(event.target.value)} required maxLength={160} disabled={saving} /></label>
      <div className="page-actions"><Button type="button" variant="secondary" onClick={() => setShowCreate(false)} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Guardar vendedor'}</Button></div>
    </form></Panel>}
    <Panel title="Perfiles comerciales"><div className="toolbar"><label className="field"><span>Buscar vendedores</span><input className="input search-input" aria-label="Buscar vendedores" placeholder="Nombre o email" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label><label className="field"><span>Estado</span><select className="select" aria-label="Filtrar vendedores por estado" value={status} onChange={(event) => setFilter('status', event.target.value)}><option value="">Todos</option><option value="ACTIVE">Activos</option><option value="INACTIVE">Inactivos</option></select></label></div>{sellersQuery.isLoading ? <EmptyState title="Cargando vendedores" description="Consultando perfiles comerciales." /> : sellersQuery.isError ? <EmptyState title="No se pudieron cargar los vendedores" description={sellersQuery.error.message} /> : rows.length === 0 ? <EmptyState title="Todavía no hay vendedores" description="Creá un perfil comercial asociado a un usuario." action={<Button onClick={openCreate}>+ Nuevo vendedor</Button>} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {sellersQuery.data?.totalElements ?? 0} vendedores</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (sellersQuery.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}</Panel>
    {reassignCustomers && <Panel title="Reasignar clientes"><form className="form-grid" onSubmit={(event) => { event.preventDefault(); setConfirmCustomerReassign(true) }}>
      <label className="field"><span>Vendedor de origen</span><select className="select" value={sourceSellerId} onChange={(event) => setSourceSellerId(event.target.value)}><option value="">Seleccionar...</option>{sellers.map((seller) => <option value={seller.id} key={seller.id}>{seller.displayName}</option>)}</select></label>
      <label className="field"><span>Vendedor de destino</span><select className="select" value={targetSellerId} onChange={(event) => setTargetSellerId(event.target.value)}><option value="">Seleccionar...</option>{sellers.filter((seller) => seller.status === 'ACTIVE').map((seller) => <option value={seller.id} key={seller.id}>{seller.displayName}</option>)}</select></label>
      <label className="radio-row"><input aria-label="Reasignar pedidos pendientes" type="checkbox" checked={reassignPendingOrders} onChange={(event) => setReassignPendingOrders(event.target.checked)} /> Reasignar también pedidos pendientes de esos clientes</label>
      <div className="page-actions"><Button type="button" variant="secondary" onClick={() => setReassignCustomers(false)}>Cancelar</Button><Button type="submit">Continuar reasignación</Button></div>
    </form></Panel>}
    {reassignOrders && <Panel title="Reasignar pedidos"><form className="form-grid" onSubmit={(event) => { event.preventDefault(); setConfirmOrderReassign(true) }}>
      <label className="field"><span>Vendedor de destino</span><select className="select" value={targetSellerId} onChange={(event) => setTargetSellerId(event.target.value)}><option value="">Seleccionar...</option>{sellers.filter((seller) => seller.status === 'ACTIVE').map((seller) => <option value={seller.id} key={seller.id}>{seller.displayName}</option>)}</select></label>
      <label className="radio-row"><input type="checkbox" checked={onlyPendingOrders} onChange={(event) => setOnlyPendingOrders(event.target.checked)} /> Solo pedidos pendientes</label>
      {ordersQuery.isLoading ? <p>Cargando pedidos...</p> : ordersQuery.isError ? <p className="error-text">No se pudieron cargar los pedidos.</p> : <fieldset className="order-select-list"><legend>Pedidos disponibles</legend>{orders.map((order) => <label className="radio-row" key={order.id}><input type="checkbox" checked={selectedOrderIds.includes(order.id)} onChange={(event) => setSelectedOrderIds((current) => event.target.checked ? [...current, order.id] : current.filter((id) => id !== order.id))} /> {order.number} · {order.customer} · {order.status}</label>)}</fieldset>}
      <div className="page-actions"><Button type="button" variant="secondary" onClick={() => setReassignOrders(false)}>Cancelar</Button><Button type="submit">Continuar reasignación</Button></div>
    </form></Panel>}
    {confirmCustomerReassign && <div role="dialog" aria-modal="true" aria-labelledby="reassign-customer-title" className="modal-backdrop"><Panel title="Confirmar reasignación"><h2 id="reassign-customer-title">¿Reasignar los clientes del vendedor seleccionado?</h2><p>Se procesarán todos los clientes del vendedor de origen.</p><div className="page-actions"><Button variant="secondary" onClick={() => setConfirmCustomerReassign(false)} disabled={saving}>Cancelar</Button><Button onClick={confirmReassignCustomers} disabled={saving}>{saving ? 'Reasignando...' : 'Confirmar reasignación'}</Button></div></Panel></div>}
    {confirmOrderReassign && <div role="dialog" aria-modal="true" aria-labelledby="reassign-order-title" className="modal-backdrop"><Panel title="Confirmar reasignación"><h2 id="reassign-order-title">¿Reasignar {selectedOrderIds.length} pedidos?</h2><div className="page-actions"><Button variant="secondary" onClick={() => setConfirmOrderReassign(false)} disabled={saving}>Cancelar</Button><Button onClick={confirmReassignOrders} disabled={saving}>{saving ? 'Reasignando...' : 'Confirmar reasignación'}</Button></div></Panel></div>}
  </>
}
