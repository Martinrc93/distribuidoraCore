import { Pagination } from '../../shared/components/Pagination'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { apiGet, apiPatch, apiPost, apiPut, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { FormModal } from '../../shared/components/FormModal'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { orderStatusLabel } from '../orders/orderStatus'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { SellerSelect } from '../../shared/components/EntitySelect'
import { apiGetAllPages } from '../../shared/api/pagination'
import { useUrlListState } from '../../shared/useUrlListState'

type Seller = { id: string; userId: string; displayName: string; email: string; status: string; assignedCustomersCount?: number | null }
type CommercialProfile = (Seller & { kind: 'SELLER' })
  | { id: string; displayName: string; status: string; assignedCustomersCount: number; kind: 'RESELLER'; email: null }
type User = { id: string; email: string; status: string }
type Order = { id: string; number: string; customer: string; status: string }
type Row = Record<string, string>
const USERS_PATH = '/api/users?page=0&size=20'
const USERS_KEY = queryKeys.users.options
const ORDERS_PATH = '/api/orders?page=0&size=20&search=&status='
const ORDERS_KEY = queryKeys.orders.reassignmentList
const PROFILES_PATH = '/api/sellers/profiles'

function readable(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 403) return 'No tenés permiso para administrar vendedores.'
  return cause instanceof Error ? cause.message : 'No se pudo completar la operación.'
}

export default function SellersPage() {
  const queryClient = useQueryClient()
  const [, setSearchParams] = useSearchParams()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['search', 'status', 'profile'])
  const canManageResellers = hasAuthority('ADMIN_ALL') && hasAuthority('RESELLER_MANAGE')
  const profileFilter = canManageResellers ? getFilter('profile') : ''
  const kind = profileFilter === 'resellers' ? 'RESELLER' : profileFilter === 'sellers' ? 'SELLER' : ''
  const showResellers = kind === 'RESELLER'
  const profileLabel = showResellers ? 'revendedores' : kind === 'SELLER' || !canManageResellers ? 'vendedores' : 'perfiles'
  const search = getFilter('search')
  const status = getFilter('status')
  const listParams = `page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}&status=${encodeURIComponent(status)}`
  const profilesPath = `${PROFILES_PATH}?${listParams}&kind=${kind}`
  const profilesQuery = useQuery({ queryKey: queryKeys.sellers.list(profilesPath), queryFn: ({ signal }) => apiGet<ApiPage<CommercialProfile>>(profilesPath, signal) })
  const profiles = profilesQuery.data?.content ?? []
  const [showCreate, setShowCreate] = useState(false)
  const [creatingReseller, setCreatingReseller] = useState(false)
  const [userId, setUserId] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [editingSeller, setEditingSeller] = useState<Seller | undefined>()
  const isReseller = !editingSeller && canManageResellers && creatingReseller
  const requiresUser = !editingSeller && !isReseller
  const usersQuery = useQuery({
    queryKey: USERS_KEY,
    queryFn: ({ signal }) => apiGetAllPages<User>(USERS_PATH, signal),
    enabled: showCreate && requiresUser,
  })
  const activeUsers = (usersQuery.data?.content ?? []).filter((user) => user.status === 'ACTIVE')
  const [reassignCustomers, setReassignCustomers] = useState(false)
  const [sourceSellerId, setSourceSellerId] = useState('')
  const [targetSellerId, setTargetSellerId] = useState('')
  const [reassignPendingOrders, setReassignPendingOrders] = useState(false)
  const [confirmCustomerReassign, setConfirmCustomerReassign] = useState(false)
  const [reassignOrders, setReassignOrders] = useState(false)
  const ordersQuery = useQuery({ queryKey: ORDERS_KEY, queryFn: ({ signal }) => apiGet<ApiPage<Order>>(ORDERS_PATH, signal), enabled: reassignOrders })
  const orders = ordersQuery.data?.content ?? []
  const sellerOptionsQuery = useQuery({ queryKey: queryKeys.sellers.reassignmentOptions, queryFn: ({ signal }) => apiGetAllPages<Seller>('/api/sellers?page=0&size=100', signal), enabled: reassignCustomers || reassignOrders })
  const sellerOptions = (sellerOptionsQuery.data?.content ?? []).map((seller) => ({ id: seller.id, name: seller.displayName, status: seller.status }))
  const [selectedOrderIds, setSelectedOrderIds] = useState<string[]>([])
  const [onlyPendingOrders, setOnlyPendingOrders] = useState(true)
  const [confirmOrderReassign, setConfirmOrderReassign] = useState(false)
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)
  const [statusSeller, setStatusSeller] = useState<Seller>()
  const newProfileLabel = showResellers ? 'Nuevo revendedor' : 'Nuevo vendedor'
  const formTitle = editingSeller ? 'Editar vendedor' : newProfileLabel
  const { requestDiscard, discardDialog } = useDiscardChanges({
    hasChanges: showCreate && (isReseller !== showResellers || displayName !== (editingSeller?.displayName ?? '') || userId !== (editingSeller?.userId ?? '')),
    onDiscard: () => { setShowCreate(false); setError('') },
    disabled: saving,
  })

  function openCreate() {
    setEditingSeller(undefined)
    setCreatingReseller(showResellers)
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
    if (saving) return
    const name = displayName.trim()
    if (!name) { setError('Ingresá un nombre.'); return }
    if (requiresUser && (!userId || usersQuery.isLoading || usersQuery.isError)) {
      setError('Seleccioná un usuario para vincular.')
      return
    }
    setSaving(true)
    setError('')
    try {
      if (isReseller) {
        await apiPost('/api/customers', { businessName: name, kind: 'RESELLER' })
        await invalidateDomains(queryClient, 'sellers', 'customers')
        setFeedback('Revendedor creado correctamente.')
        setSearchParams({ search: name }, { replace: true })
      } else {
        if (editingSeller) await apiPut(`/api/sellers/${editingSeller.id}`, { displayName: name })
        else await apiPost('/api/sellers', { userId, displayName: name })
        await invalidateDomains(queryClient, 'sellers', 'customers', 'orders', 'sales', 'reports')
        setFeedback(editingSeller ? 'Vendedor actualizado correctamente.' : 'Vendedor creado correctamente.')
        if (showResellers) setSearchParams({ search: name })
      }
      setShowCreate(false)
    } catch (cause) {
      setError(isReseller && cause instanceof ApiError && cause.status === 403
        ? 'No tenés permiso para administrar revendedores.'
        : readable(cause))
    } finally {
      setSaving(false)
    }
  }

  async function changeStatus() {
    if (!statusSeller || saving) return
    const seller = statusSeller
    const status = seller.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    setSaving(true)
    setError('')
    try {
      await apiPatch(`/api/sellers/${seller.id}/status`, { status })
      await invalidateDomains(queryClient, 'sellers', 'customers', 'orders', 'sales', 'reports')
      setFeedback(status === 'ACTIVE' ? 'Vendedor activado.' : 'Vendedor desactivado.')
      setStatusSeller(undefined)
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  async function confirmReassignCustomers() {
    if (saving) return
    if (!sourceSellerId || !targetSellerId || sourceSellerId === targetSellerId) { setError('Elegí dos vendedores distintos.'); return }
    setSaving(true)
    setError('')
    try {
      const result = await apiPost<{ reassignedCustomersCount: number; reassignedOrdersCount: number }>('/api/sellers/reassign-customers', {
        sourceSellerId, targetSellerId, reassignPendingOrders,
      })
      await invalidateDomains(queryClient, 'sellers', 'customers', 'orders', 'sales', 'reports')
      setFeedback(`${result.reassignedCustomersCount} clientes y ${result.reassignedOrdersCount} pedidos reasignados.`)
      setConfirmCustomerReassign(false)
      setReassignCustomers(false)
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  async function confirmReassignOrders() {
    if (saving) return
    if (!targetSellerId || selectedOrderIds.length === 0) { setError('Elegí un vendedor de destino y al menos un pedido.'); return }
    setSaving(true)
    setError('')
    try {
      const result = await apiPost<{ reassignedOrdersCount: number }>('/api/sellers/reassign-orders', { targetSellerId, orderIds: selectedOrderIds, onlyPending: onlyPendingOrders })
      await invalidateDomains(queryClient, 'sellers', 'customers', 'orders', 'sales', 'reports')
      setFeedback(`${result.reassignedOrdersCount} pedidos reasignados.`)
      setConfirmOrderReassign(false)
      setReassignOrders(false)
      setSelectedOrderIds([])
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  const rows: Row[] = profiles.map((profile) => ({
    id: profile.id, kind: profile.kind, name: profile.displayName, email: profile.email ?? '—',
    customers: String(profile.assignedCustomersCount ?? 0), status: profile.status,
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Nombre', emphasis: true },
    { key: 'kind', label: 'Tipo', render: (value) => <Badge tone={value === 'RESELLER' ? 'soft' : 'muted'}>{value === 'RESELLER' ? 'Revendedor' : 'Vendedor'}</Badge> },
    { key: 'email', label: 'Usuario' },
    { key: 'customers', label: 'Clientes', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'ACTIVE' ? 'strong' : 'muted'}>{value === 'ACTIVE' ? 'Activo' : 'Inactivo'}</Badge> },
    { key: 'actions', label: 'Acciones', render: (_value, row) => {
      if (row.kind === 'RESELLER') return <div className="table-row-actions">
        <Button variant="link" href={`/customers/${encodeURIComponent(row.id)}`} aria-label={`Ver ficha de ${row.name}`}>Ver ficha</Button>
      </div>
      const seller = profiles.find((profile) => profile.kind === 'SELLER' && profile.id === row.id)
      if (seller?.kind !== 'SELLER') return null
      return <div className="table-row-actions"><Button variant="link" aria-label={`Editar vendedor ${seller.displayName}`} onClick={() => openEdit(seller)}>Editar vendedor</Button><Button variant="link" aria-label={`${seller.status === 'ACTIVE' ? 'Desactivar' : 'Activar'} vendedor ${seller.displayName}`} onClick={() => { setError(''); setStatusSeller(seller) }} disabled={saving}>{seller.status === 'ACTIVE' ? 'Desactivar vendedor' : 'Activar vendedor'}</Button></div>
    } },
  ]

  return <>
    <PageHeader eyebrow="Administración" title="Vendedores" description="Perfiles comerciales, clientes asignados y reasignaciones." actions={<div className="page-actions"><Button onClick={openCreate}>+ {newProfileLabel}</Button><Button variant="secondary" onClick={() => { setReassignCustomers(true); setError('') }}>Reasignar clientes</Button><Button variant="secondary" onClick={() => { setReassignOrders(true); setError('') }}>Reasignar pedidos</Button></div>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}

    {showCreate && <FormModal label={formTitle} size="compact" busy={saving} onClose={requestDiscard}>
      <Panel title={formTitle}><form className="grid gap-[14px]" onSubmit={saveSeller}>
        {!editingSeller && canManageResellers && <label className="field">
          <span>Tipo de perfil</span>
          <select className="select" value={isReseller ? 'RESELLER' : 'SELLER'} disabled={saving} onChange={(event) => {
            setCreatingReseller(event.target.value === 'RESELLER')
            setError('')
          }}>
            <option value="SELLER">Vendedor</option>
            <option value="RESELLER">Revendedor</option>
          </select>
        </label>}
        {requiresUser && <label className="field">
          <span>Usuario vinculado</span>
          <select className="select" value={userId} onChange={(event) => setUserId(event.target.value)} required disabled={saving || usersQuery.isLoading || usersQuery.isError}>
            <option value="">Seleccionar usuario...</option>
            {activeUsers.map((user) => <option value={user.id} key={user.id}>{user.email}</option>)}
          </select>
        </label>}
        <label className="field"><span>{isReseller ? 'Nombre del revendedor' : 'Nombre para mostrar'}</span><input className="input" value={displayName} onChange={(event) => setDisplayName(event.target.value)} required maxLength={160} disabled={saving} /></label>
        {requiresUser && usersQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los usuarios. <Button type="button" variant="link" onClick={() => void usersQuery.refetch()}>Reintentar usuarios</Button></p>}
        {requiresUser && usersQuery.isLoading && <p className="helper-text" role="status">Cargando usuarios...</p>}
        {error && <p className="error-text" role="alert">{error}</p>}
        <div className="page-actions justify-end">
          <Button type="button" variant="secondary" onClick={requestDiscard} disabled={saving}>Cancelar</Button>
          <Button type="submit" disabled={saving || requiresUser && (!userId || usersQuery.isLoading || usersQuery.isError)}>{saving ? 'Guardando...' : isReseller ? 'Guardar revendedor' : 'Guardar vendedor'}</Button>
        </div>
      </form></Panel>
    </FormModal>}
    {discardDialog}
    <Panel title="Perfiles comerciales">
      <div className="toolbar">
        {canManageResellers && <label className="field"><span>Mostrar</span>
          <select className="select" value={kind === '' ? '' : profileFilter} onChange={(event) => setFilter('profile', event.target.value)}>
            <option value="">Todos los perfiles</option>
            <option value="sellers">Vendedores</option>
            <option value="resellers">Revendedores</option>
          </select>
        </label>}
        <label className="field"><span>Buscar {profileLabel}</span>
          <input className="input search-input" placeholder={showResellers ? 'Nombre' : 'Nombre o email'} value={search} onChange={(event) => setFilter('search', event.target.value)} />
        </label>
        <label className="field"><span>Estado</span>
          <select className="select" aria-label={`Filtrar ${profileLabel} por estado`} value={status} onChange={(event) => setFilter('status', event.target.value)}>
            <option value="">Todos</option><option value="ACTIVE">Activos</option><option value="INACTIVE">Inactivos</option>
          </select>
        </label>
      </div>
      {profilesQuery.isLoading ? <EmptyState state="loading" title={`Cargando ${profileLabel}`} description="Consultando perfiles comerciales." />
        : profilesQuery.isError ? <EmptyState state="error" title={`No se pudieron cargar los ${profileLabel}`} description={profilesQuery.error.message}
          action={<Button variant="secondary" onClick={() => void profilesQuery.refetch()}>Reintentar</Button>} />
        : rows.length === 0 && page === 0 ? <EmptyState title={search || status ? `No se encontraron ${profileLabel}` : `Todavía no hay ${profileLabel}`}
          description={search || status ? 'Podés cambiar los filtros para ampliar la búsqueda.' : showResellers ? 'Creá un revendedor para asociarle clientes.' : 'Creá un perfil comercial asociado a un usuario.'}
          action={!search && !status && <Button onClick={openCreate}>+ {newProfileLabel}</Button>} />
        : <>
          <DataTable className="compact-list-table" columns={columns} rows={rows} />
          <Pagination summary={<>Página {page + 1} · {profilesQuery.data?.totalElements ?? 0} {profileLabel}</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= (profilesQuery.data?.totalPages ?? 0)} />
        </>}
    </Panel>
    {reassignCustomers && <FormModal label="Reasignar clientes" busy={saving} onClose={() => setReassignCustomers(false)}>
      <Panel title="Reasignar clientes">
        <form onSubmit={(event) => {
          event.preventDefault()
          setError('')
          if (sourceSellerId === targetSellerId) { setError('Elegí dos vendedores distintos.'); return }
          setConfirmCustomerReassign(true)
        }}>
          <div className="form-grid">
            <SellerSelect mode="selection" label="Vendedor de origen" options={sellerOptions} value={sourceSellerId} onChange={setSourceSellerId} required loading={sellerOptionsQuery.isLoading} disabled={saving || sellerOptionsQuery.isError} />
            <SellerSelect mode="selection" label="Vendedor de destino" options={sellerOptions.filter((seller) => seller.status === 'ACTIVE')} value={targetSellerId} onChange={setTargetSellerId} required loading={sellerOptionsQuery.isLoading} disabled={saving || sellerOptionsQuery.isError} />
          </div>
          {sellerOptionsQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los vendedores. <Button type="button" variant="link" onClick={() => void sellerOptionsQuery.refetch()}>Reintentar vendedores</Button></p>}
          <label className="radio-row"><input aria-label="Reasignar pedidos pendientes" type="checkbox" checked={reassignPendingOrders} disabled={saving} onChange={(event) => setReassignPendingOrders(event.target.checked)} /> Reasignar también pedidos pendientes de esos clientes</label>
          {!confirmCustomerReassign && error && <p className="error-text" role="alert">{error}</p>}
          <div className="page-actions mt-[14px] justify-end">
            <Button type="button" variant="secondary" disabled={saving} onClick={() => setReassignCustomers(false)}>Cancelar</Button>
            <Button type="submit" disabled={saving || sellerOptionsQuery.isError || sellerOptionsQuery.isLoading || !sourceSellerId || !targetSellerId}>Continuar reasignación</Button>
          </div>
        </form>
      </Panel>
    </FormModal>}
    {reassignOrders && <FormModal label="Reasignar pedidos" busy={saving} onClose={() => setReassignOrders(false)}>
      <Panel title="Reasignar pedidos">
        <form className="grid gap-[14px]" onSubmit={(event) => { event.preventDefault(); setError(''); setConfirmOrderReassign(true) }}>
          <SellerSelect mode="selection" label="Vendedor de destino" options={sellerOptions.filter((seller) => seller.status === 'ACTIVE')} value={targetSellerId} onChange={setTargetSellerId} required loading={sellerOptionsQuery.isLoading} disabled={saving || sellerOptionsQuery.isError} />
          {sellerOptionsQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los vendedores. <Button type="button" variant="link" onClick={() => void sellerOptionsQuery.refetch()}>Reintentar vendedores</Button></p>}
          <label className="radio-row"><input type="checkbox" checked={onlyPendingOrders} disabled={saving} onChange={(event) => setOnlyPendingOrders(event.target.checked)} /> Solo pedidos pendientes</label>
          {ordersQuery.isLoading ? <p role="status">Cargando pedidos...</p> : ordersQuery.isError
            ? <p className="error-text" role="alert">No se pudieron cargar los pedidos. <Button type="button" variant="link" onClick={() => void ordersQuery.refetch()}>Reintentar pedidos</Button></p>
            : <fieldset className="order-select-list" disabled={saving}><legend>Pedidos disponibles</legend>
              {orders.length === 0 && <p className="helper-text">No hay pedidos disponibles.</p>}
              {orders.map((order) => <label className="radio-row" key={order.id}>
                <input type="checkbox" checked={selectedOrderIds.includes(order.id)} onChange={(event) => setSelectedOrderIds((current) => event.target.checked ? [...current, order.id] : current.filter((id) => id !== order.id))} />
                <span>{order.number} · {order.customer} · {orderStatusLabel(order.status)}</span>
              </label>)}
            </fieldset>}
          <div className="page-actions justify-end">
            <Button type="button" variant="secondary" disabled={saving} onClick={() => setReassignOrders(false)}>Cancelar</Button>
            <Button type="submit" disabled={saving || ordersQuery.isError || sellerOptionsQuery.isError || !targetSellerId || !selectedOrderIds.length}>Continuar reasignación</Button>
          </div>
        </form>
      </Panel>
    </FormModal>}
    {confirmCustomerReassign && <ConfirmationDialog title="Confirmar reasignación de clientes"
      description={reassignPendingOrders ? 'Se reasignarán todos los clientes del vendedor de origen y sus pedidos pendientes.' : 'Se reasignarán todos los clientes del vendedor de origen.'}
      confirmLabel="Confirmar reasignación" cancelLabel="Cancelar" confirmVariant="primary" pendingLabel="Reasignando..."
      pending={saving} error={error} onCancel={() => { setConfirmCustomerReassign(false); setError('') }} onConfirm={() => void confirmReassignCustomers()} />}
    {confirmOrderReassign && <ConfirmationDialog title="Confirmar reasignación de pedidos"
      description={'Pedidos seleccionados: ' + selectedOrderIds.length + '. Se asignarán al vendedor de destino.'}
      confirmLabel="Confirmar reasignación" cancelLabel="Cancelar" confirmVariant="primary" pendingLabel="Reasignando..."
      pending={saving} error={error} onCancel={() => { setConfirmOrderReassign(false); setError('') }} onConfirm={() => void confirmReassignOrders()} />}
    {statusSeller && <ConfirmationDialog title={statusSeller.status === 'ACTIVE' ? 'Desactivar vendedor' : 'Activar vendedor'}
      description={statusSeller.status === 'ACTIVE' ? 'Se desactivará el perfil comercial de ' + statusSeller.displayName + '.' : 'Se habilitará el perfil comercial de ' + statusSeller.displayName + '.'}
      confirmLabel="Confirmar" cancelLabel="Cancelar" confirmVariant={statusSeller.status === 'ACTIVE' ? 'danger' : 'primary'}
      pending={saving} error={error} onCancel={() => { setStatusSeller(undefined); setError('') }} onConfirm={() => void changeStatus()} />}
  </>
}
