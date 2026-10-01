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
import { SellerSelect } from '../../shared/components/EntitySelect'
import { apiGetAllPages } from '../../shared/api/pagination'
import { useUrlListState } from '../../shared/useUrlListState'
import { useDiscardChanges } from '../../shared/useDiscardChanges'

type Customer = {
  id: string
  name: string
  cuitId?: string | null
  email?: string | null
  phone?: string | null
  address?: string | null
  zone?: string | null
  seller?: string
  sellerId?: string
  priceListId?: string | null
  balance?: number
  status: 'ACTIVE' | 'INACTIVE' | string
}

type Seller = { id: string; displayName: string; email: string }
type PriceList = { id: string; code: string; name: string; status: string }
type Zone = { id: string; name: string }

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(Number(value ?? 0))
}

function errorMessage(cause: unknown, fallback: string) {
  const status = cause instanceof ApiError ? cause.status : undefined
  const message = cause instanceof ApiError ? cause.detail ?? '' : cause instanceof Error ? cause.message : ''
  if (status !== undefined) {
    if (status === 403) return 'No tenés permisos para realizar esta operación.'
    if (status === 404) return 'El cliente ya no existe o no está disponible.'
    if (status === 409) return message || 'El cliente entra en conflicto con un registro existente.'
    if (status === 400) return message || 'Revisá los datos ingresados.'
    return message || fallback
  }
  if (/403|forbidden|permiso/i.test(message)) return 'No tenés permisos para realizar esta operación.'
  if (/404|not found|no existe/i.test(message)) return 'El cliente ya no existe o no está disponible.'
  if (/409|conflict|ya existe|duplic/i.test(message)) return message || 'El cliente entra en conflicto con un registro existente.'
  if (/400|invalid|inválid/i.test(message)) return message || 'Revisá los datos ingresados.'
  return message || fallback
}

function StatusBadge({ value }: { value: string }) {
  const tone: BadgeTone = value === 'ACTIVE' || value === 'Activo' ? 'strong' : 'muted'
  return <Badge tone={tone}>{value === 'ACTIVE' ? 'Activo' : value === 'INACTIVE' ? 'Inactivo' : value}</Badge>
}

function CustomerForm({
  initial,
  sellers,
  sellersLoading,
  sellersError,
  zones,
  zonesLoading,
  zonesError,
  onRetryZones,
  priceLists,
  priceListsError,
  onRetryPriceLists,
  onDone,
  onSuccess,
  onChangeStatus,
}: {
  initial?: Customer
  sellers: Seller[]
  sellersLoading: boolean
  sellersError: boolean
  zones: Zone[]
  zonesLoading: boolean
  zonesError: boolean
  onRetryZones: () => void
  priceLists: PriceList[]
  priceListsError: boolean
  onRetryPriceLists: () => void
  onDone: () => void
  onSuccess: (message: string) => void
  onChangeStatus: (customer: Customer) => void
}) {
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const [businessName, setBusinessName] = useState(initial?.name ?? '')
  const [cuitId, setCuitId] = useState(initial?.cuitId ?? '')
  const [sellerId, setSellerId] = useState(initial?.sellerId ?? '')
  const [email, setEmail] = useState(initial?.email ?? '')
  const [phone, setPhone] = useState(initial?.phone ?? '')
  const [address, setAddress] = useState(initial?.address ?? '')
  const [zone, setZone] = useState(initial?.zone ?? '')
  const [priceListId, setPriceListId] = useState(initial?.priceListId ?? '')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    setBusinessName(initial?.name ?? '')
    setCuitId(initial?.cuitId ?? '')
    setSellerId(initial?.sellerId ?? '')
    setEmail(initial?.email ?? '')
    setPhone(initial?.phone ?? '')
    setAddress(initial?.address ?? '')
    setZone(initial?.zone ?? '')
    setPriceListId(initial?.priceListId ?? '')
    setError('')
  }, [initial])

  const changed = businessName !== (initial?.name ?? '')
    || cuitId !== (initial?.cuitId ?? '')
    || email !== (initial?.email ?? '')
    || phone !== (initial?.phone ?? '')
    || address !== (initial?.address ?? '')
    || zone !== (initial?.zone ?? '')
    || sellerId !== (initial?.sellerId ?? '')
    || priceListId !== (initial?.priceListId ?? '')

  const { requestDiscard: requestClose, discardDialog } = useDiscardChanges({ hasChanges: changed, onDiscard: onDone, disabled: saving })

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setSaving(true)
    setError('')
    try {
      const body = { businessName, cuitId: cuitId.trim() || null, email: email.trim() || null, phone: phone.trim() || null, address: address.trim() || null, zone: zone.trim() || null, sellerId: sellerId || undefined, ...(initial || priceListId ? { priceListId: priceListId || null } : {}) }
      if (initial) await apiPut(`/api/customers/${initial.id}`, body)
      else await apiPost('/api/customers', body)
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/customers?') || queryKey[0] === '/api/customers/filter-options' })
      onSuccess(initial ? 'Cliente actualizado correctamente.' : 'Cliente creado correctamente.')
      onDone()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/customers?') })
      setError(errorMessage(cause, 'No se pudo guardar el cliente.'))
    } finally {
      setSaving(false)
    }
  }

  return <>
  <Panel title={initial ? 'Editar cliente' : 'Nuevo cliente'} action={<button className="customer-modal-close" type="button" aria-label="Cerrar modal" onClick={requestClose} disabled={saving}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>}>
    <form className="form-grid" onSubmit={submit}>
      <label className="field"><span>Razón social</span><input className="input" value={businessName} onChange={(event) => setBusinessName(event.target.value)} required /></label>
      <label className="field"><span>CUIT (opcional)</span><input className="input" value={cuitId} onChange={(event) => setCuitId(event.target.value)} /></label>
      <label className="field"><span>Mail</span><input className="input" type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label>
      <label className="field"><span>Celular</span><input className="input" type="tel" value={phone} onChange={(event) => setPhone(event.target.value)} /></label>
      <label className="field"><span>Dirección</span><input className="input" value={address} onChange={(event) => setAddress(event.target.value)} /></label>
      <label className="field"><span>Zona</span><select className="select" aria-label="Zona" value={zone} onChange={(event) => setZone(event.target.value)} disabled={zonesLoading || zonesError}><option value="">Sin zona asignada</option>{initial?.zone && !zones.some((item) => item.name === initial.zone) && <option value={initial.zone}>{initial.zone}</option>}{zones.map((item) => <option value={item.name} key={item.id}>{item.name}</option>)}</select>{zonesError && <span className="error-text">No se pudieron cargar las zonas. <Button variant="link" type="button" onClick={onRetryZones}>Reintentar</Button></span>}</label>
      {isAdmin && <SellerSelect mode="selection" label="Vendedor asignado" options={sellers.map((seller) => ({ id: seller.id, name: `${seller.displayName} (${seller.email})` }))} value={sellerId} onChange={setSellerId} emptyLabel="Sin asignar" loading={sellersLoading} disabled={saving || sellersError} />}
      <label className="field"><span>Lista de precios predeterminada</span><select className="select" aria-label="Lista de precios predeterminada" value={priceListId} onChange={(event) => setPriceListId(event.target.value)} disabled={priceListsError}><option value="">Sin lista asignada</option>{priceLists.filter((list) => list.status === 'ACTIVE').map((list) => <option value={list.id} key={list.id}>{list.code} - {list.name}</option>)}</select>{priceListsError && <span className="error-text">No se pudieron cargar las listas. <Button variant="link" type="button" onClick={onRetryPriceLists}>Reintentar</Button></span>}</label>
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions customer-form-actions">{initial && <Button variant="secondary" type="button" onClick={() => onChangeStatus(initial)} disabled={saving}>{initial.status === 'INACTIVE' ? 'Activar cliente' : 'Desactivar cliente'}</Button>}<div className="customer-form-actions-right"><Button variant="secondary" type="button" onClick={requestClose} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving || priceListsError}>{saving ? 'Guardando...' : initial ? 'Guardar cambios' : 'Guardar cliente'}</Button></div></div>
    </form>
  </Panel>
  {discardDialog}
  </>
}

export default function CustomersPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['search', 'sellerId', 'hasBalance', 'status'])
  const search = getFilter('search')
  const sellerId = getFilter('sellerId')
  const hasBalance = getFilter('hasBalance') === 'true'
  const status = getFilter('status') || 'ACTIVE'
  const customerPath = `/api/customers?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}&sellerId=${encodeURIComponent(sellerId)}&hasBalance=${hasBalance}&status=${encodeURIComponent(status === 'ALL' ? '' : status)}`
  const query = useQuery({ queryKey: [customerPath], queryFn: () => apiGet<ApiPage<Customer>>(customerPath) })
  const optionsQuery = useQuery({ queryKey: ['/api/customers/filter-options'], queryFn: () => apiGet<{ sellers: Array<{ id: string; name: string }> }>('/api/customers/filter-options'), enabled: isAdmin })
  const sellersQuery = useQuery({ queryKey: ['/api/sellers?page=0&size=100'], queryFn: () => apiGetAllPages<Seller>('/api/sellers?page=0&size=100'), enabled: isAdmin })
  const zonesQuery = useQuery({ queryKey: ['/api/zones'], queryFn: () => apiGet<Zone[]>('/api/zones'), enabled: isAdmin })
  const listsQuery = useQuery({ queryKey: ['/api/pricing/lists?page=0&size=100'], queryFn: () => apiGet<ApiPage<PriceList>>('/api/pricing/lists?page=0&size=100'), enabled: isAdmin })
  const [formCustomer, setFormCustomer] = useState<Customer | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [actionSaving, setActionSaving] = useState(false)
  const [statusCustomer, setStatusCustomer] = useState<Customer | undefined>()

  async function invalidate() {
    await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/customers?') || queryKey[0] === '/api/customers/filter-options' })
  }

  async function changeStatus() {
    if (!statusCustomer || actionSaving) return
    setActionSaving(true)
    setActionError('')
    try {
      const status = statusCustomer.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await apiPatch(`/api/customers/${statusCustomer.id}/status`, { status })
      await invalidate()
      setStatusCustomer(undefined)
      setFormCustomer(undefined)
      setFeedback(status === 'ACTIVE' ? 'Cliente activado correctamente.' : 'Cliente desactivado correctamente.')
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) await invalidate()
      setActionError(errorMessage(cause, 'No se pudo actualizar el estado del cliente.'))
    } finally {
      setActionSaving(false)
    }
  }

  const rows = (query.data?.content ?? []).map((customer) => ({
    id: customer.id,
    name: customer.name,
    cuitId: customer.cuitId ?? '-',
    seller: customer.seller ?? 'Sin asignar',
    balance: money(customer.balance),
    status: customer.status,
    priceListId: customer.priceListId ?? '',
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Cliente', emphasis: true },
    { key: 'cuitId', label: 'CUIT' },
    { key: 'seller', label: 'Vendedor' },
    { key: 'balance', label: 'Saldo', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> },
    ...(isAdmin ? [{ key: 'actions', label: '', render: (_value: string, row: Record<string, string>) => <div className="page-actions"><Button variant="link" onClick={() => { setFormCustomer(query.data?.content.find((customer) => customer.id === row.id)); setShowForm(false) }}>Editar</Button></div> }] : []),
  ]

  return <>
    <PageHeader eyebrow="Operación" title="Clientes" description="Gestioná clientes, vendedor, lista de precios y cuenta corriente." actions={isAdmin ? <Button onClick={() => { setFormCustomer(undefined); setShowForm((value) => !value) }}>+ Nuevo cliente</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {actionError && <p className="error-text" role="alert">{actionError}</p>}
    {isAdmin && (showForm || formCustomer) && <div role="dialog" aria-modal="true" aria-label={formCustomer ? 'Editar cliente' : 'Nuevo cliente'} className="modal-backdrop"><div className="customer-modal"><CustomerForm initial={formCustomer} sellers={sellersQuery.data?.content ?? []} sellersLoading={sellersQuery.isLoading} sellersError={sellersQuery.isError} zones={zonesQuery.data ?? []} zonesLoading={zonesQuery.isLoading} zonesError={zonesQuery.isError} onRetryZones={() => zonesQuery.refetch()} priceLists={listsQuery.data?.content ?? []} priceListsError={listsQuery.isError} onRetryPriceLists={() => listsQuery.refetch()} onSuccess={setFeedback} onDone={() => { setShowForm(false); setFormCustomer(undefined) }} onChangeStatus={(customer) => { setActionError(''); setStatusCustomer(customer) }} /></div></div>}
    <Panel><div className="toolbar">
      <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Buscar clientes</span><input className="input search-input [@media(max-width:640px)]:max-w-none" placeholder="Nombre o identificación" aria-label="Buscar clientes" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
      {isAdmin && <SellerSelect options={optionsQuery.data?.sellers ?? []} value={sellerId} onChange={(value) => setFilter('sellerId', value)} loading={optionsQuery.isLoading} disabled={optionsQuery.isError} />}
      <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Cuenta corriente</span><select aria-label="Cuenta corriente" className="select [@media(max-width:640px)]:w-full [@media(max-width:640px)]:min-w-0" value={hasBalance ? 'true' : ''} onChange={(event) => setFilter('hasBalance', event.target.value)}><option value="">Todos los saldos</option><option value="true">Solo con saldo</option></select></label>
      <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Estado</span><select aria-label="Estado" className="select [@media(max-width:640px)]:w-full [@media(max-width:640px)]:min-w-0" value={status} onChange={(event) => setFilter('status', event.target.value)}><option value="ACTIVE">Activo</option><option value="INACTIVE">Inactivo</option><option value="ALL">Todos</option></select></label>
    </div>
      {isAdmin && optionsQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los vendedores del filtro. <Button variant="link" type="button" onClick={() => optionsQuery.refetch()}>Reintentar filtro</Button></p>}
      {query.isLoading ? <EmptyState title="Cargando clientes" description="Consultando clientes a través de la API." /> : query.isError ? <EmptyState title="No se pudieron cargar los clientes" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="No hay clientes para mostrar" description="No se encontraron clientes con los filtros seleccionados." /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} clientes</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    {isAdmin && sellersQuery.isError && (showForm || formCustomer) && <p className="error-text" role="alert">No se pudieron cargar los vendedores. <Button variant="link" type="button" onClick={() => sellersQuery.refetch()}>Reintentar</Button></p>}
    {statusCustomer && <div role="dialog" aria-modal="true" aria-labelledby="status-dialog-title" className="modal-backdrop"><Panel title="Confirmar cambio de estado"><h2 id="status-dialog-title">¿Querés {statusCustomer.status === 'ACTIVE' ? 'desactivar' : 'activar'} a {statusCustomer.name}?</h2><div className="page-actions"><Button variant="secondary" onClick={() => setStatusCustomer(undefined)}>Cancelar</Button><Button onClick={changeStatus} disabled={actionSaving}>{actionSaving ? 'Guardando...' : 'Confirmar'}</Button></div></Panel></div>}
  </>
}
