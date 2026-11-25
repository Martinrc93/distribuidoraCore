import { Pagination } from '../../shared/components/Pagination'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { useLocation } from 'react-router-dom'
import { apiGet, apiPatch, apiPost, apiPut, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge, type BadgeTone } from '../../shared/components/Badge'
import { SecondaryRowActions } from '../../shared/components/SecondaryRowActions'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { CustomerSelect, SellerSelect } from '../../shared/components/EntitySelect'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { formatMoney } from '../../shared/format'
import { apiGetAllPages } from '../../shared/api/pagination'
import { useUrlListState } from '../../shared/useUrlListState'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import type { Customer } from './customerTypes'
import { AccountSettlementDialog } from './AccountSettlementDialog'
import { CustomerPriceListDialog } from './CustomerPriceListDialog'
import { CustomerResellerDialog } from './CustomerResellerDialog'
import { FormModal } from '../../shared/components/FormModal'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DocumentActions } from '../../shared/components/DocumentActions'

type Seller = { id: string; displayName: string; email: string }
type PriceList = { id: string; code: string; name: string; status: string }
type Zone = { id: string; name: string }

function money(value: unknown) {
  return formatMoney(value, 2)
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
  onRetrySellers,
  zones,
  zonesLoading,
  zonesError,
  onRetryZones,
  priceLists,
  priceListsLoading,
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
  onRetrySellers: () => void
  zones: Zone[]
  zonesLoading: boolean
  zonesError: boolean
  onRetryZones: () => void
  priceLists: PriceList[]
  priceListsLoading: boolean
  priceListsError: boolean
  onRetryPriceLists: () => void
  onDone: () => void
  onSuccess: (message: string) => void
  onChangeStatus: (customer: Customer) => void
}) {
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canChangeList = hasPermission('CUSTOMER_PRICE_LIST_CHANGE')
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
  const saveDisabled = saving || sellersLoading || sellersError || (!isAdmin && !sellers.length)
    || (canChangeList && (priceListsLoading || priceListsError))
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

  const { requestDiscard: requestClose, discardDialog } = useDiscardChanges({ hasChanges: changed, onDiscard: onDone, disabled: saving, protectUnload: true })

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saveDisabled) return
    setSaving(true)
    setError('')
    try {
      const body = { businessName, cuitId: cuitId.trim() || null, email: email.trim() || null, phone: phone.trim() || null, address: address.trim() || null, zone: zone.trim() || null, sellerId: (isAdmin ? sellerId : sellers[0]?.id) || undefined, ...(initial || priceListId ? { priceListId: priceListId || null } : {}) }
      if (initial) await apiPut(`/api/customers/${initial.id}`, body)
      else await apiPost('/api/customers', body)
      await invalidateDomains(queryClient, 'customers', 'orders', 'sales', 'reports')
      onSuccess(initial ? 'Cliente actualizado correctamente.' : 'Cliente creado correctamente.')
      onDone()
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 404) await invalidateDomains(queryClient, 'customers', 'orders', 'sales', 'reports')
      setError(errorMessage(cause, 'No se pudo guardar el cliente.'))
    } finally {
      setSaving(false)
    }
  }

  return <>
  <FormModal label={initial ? 'Editar cliente' : 'Nuevo cliente'} busy={saving} onClose={requestClose}><Panel title={initial ? 'Editar cliente' : 'Nuevo cliente'} action={<button className="customer-modal-close" type="button" aria-label="Cerrar modal" onClick={requestClose} disabled={saving}><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg></button>}>
    <form className="form-grid" onSubmit={submit}>
      <label className="field"><span>Razón social</span><input disabled={saving} className="input" value={businessName} onChange={(event) => setBusinessName(event.target.value)} required /></label>
      <label className="field"><span>CUIT (opcional)</span><input disabled={saving} className="input" value={cuitId} onChange={(event) => setCuitId(event.target.value)} /></label>
      <label className="field"><span>Mail</span><input disabled={saving} className="input" type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label>
      <label className="field"><span>Celular</span><input disabled={saving} className="input" type="tel" value={phone} onChange={(event) => setPhone(event.target.value)} /></label>
      <label className="field"><span>Dirección</span><input disabled={saving} className="input" value={address} onChange={(event) => setAddress(event.target.value)} /></label>
      <div className="field">
        <label htmlFor="customer-zone">Zona</label>
        <select id="customer-zone" className="select" value={zone} onChange={(event) => setZone(event.target.value)} disabled={saving || zonesLoading || zonesError}>
          <option value="">{zonesLoading ? 'Cargando zonas…' : 'Sin zona asignada'}</option>
          {initial?.zone && !zones.some((item) => item.name === initial.zone) && <option value={initial.zone}>{initial.zone}</option>}
          {zones.map((item) => <option value={item.name} key={item.id}>{item.name}</option>)}
        </select>
        {zonesError && <span className="error-text">No se pudieron cargar las zonas. <Button variant="link" type="button" onClick={onRetryZones}>Reintentar</Button></span>}
      </div>
      <SellerSelect mode="selection" label="Vendedor asignado" options={sellers.map((seller) => ({ id: seller.id, name: `${seller.displayName} (${seller.email})` }))} value={isAdmin ? sellerId : sellers[0]?.id ?? ''} onChange={setSellerId} emptyLabel="Sin asignar" loading={sellersLoading} disabled={saving || sellersError || !isAdmin} />
      {sellersError && <p className="error-text col-span-full" role="alert">No se pudo cargar la asignación de vendedor. <Button variant="link" type="button" onClick={onRetrySellers}>Reintentar asignación</Button></p>}
      <div className="field">
        <label htmlFor="customer-default-price-list">Lista de precios predeterminada</label>
        <select id="customer-default-price-list" className="select" value={priceListId} onChange={(event) => setPriceListId(event.target.value)} disabled={saving || priceListsLoading || priceListsError || !canChangeList}>
          <option value="">{priceListsLoading ? 'Cargando listas…' : 'Sin lista asignada'}</option>
          {initial?.priceListId && !priceLists.some(list => list.id === initial.priceListId) && <option value={initial.priceListId}>Lista asignada (sin cambios)</option>}
          {priceLists.filter((list) => list.status === 'ACTIVE' || list.id === initial?.priceListId).map((list) => <option value={list.id} key={list.id}>{list.code} - {list.name}{list.status !== 'ACTIVE' ? ' (inactiva)' : ''}</option>)}
        </select>
        {canChangeList && priceListsError && <span className="error-text">No se pudieron cargar las listas. <Button variant="link" type="button" onClick={onRetryPriceLists}>Reintentar</Button></span>}
      </div>
      {error && <p className="error-text col-span-full" role="alert"><CurrencyText>{error}</CurrencyText></p>}
      <div className="page-actions customer-form-actions">{initial && hasPermission('CUSTOMER_STATUS_CHANGE') && <Button variant="secondary" type="button" onClick={() => onChangeStatus(initial)} disabled={saving}>{initial.status === 'INACTIVE' ? 'Activar cliente' : 'Desactivar cliente'}</Button>}<div className="customer-form-actions-right"><Button variant="secondary" type="button" onClick={requestClose} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saveDisabled}>{saving ? 'Guardando...' : initial ? 'Guardar cambios' : 'Guardar cliente'}</Button></div></div>
    </form>
  </Panel></FormModal>
  {discardDialog}
  </>
}

export default function CustomersPage() {
  const location = useLocation()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canView = hasPermission('CUSTOMER_VIEW')
  const canCreate = hasPermission('CUSTOMER_CREATE')
  const canEdit = hasPermission('CUSTOMER_EDIT')
  const canStatus = hasPermission('CUSTOMER_STATUS_CHANGE')
  const canList = hasPermission('CUSTOMER_PRICE_LIST_CHANGE')
  const canSettle = hasPermission('ACCOUNT_SETTLE')
  const canExport = hasPermission('CUSTOMER_STATEMENT_EXPORT')
  // Reserved to administrators: marking resellers and associating customers changes who owes new orders.
  const canManageResellers = isAdmin && hasAuthority('RESELLER_MANAGE')
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['search', 'sellerId', 'hasBalance', 'status', 'resellerId'])
  const search = getFilter('search')
  const sellerId = getFilter('sellerId')
  const hasBalance = getFilter('hasBalance') === 'true'
  const status = getFilter('status') || 'ACTIVE'
  const resellerFilter = isAdmin ? getFilter('resellerId') : ''
  const [formCustomer, setFormCustomer] = useState<Customer | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [listCustomer, setListCustomer] = useState<Customer>()
  const formOpen = (showForm && canCreate) || (Boolean(formCustomer) && canEdit)
  const customerPath = `/api/customers?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}&sellerId=${encodeURIComponent(sellerId)}&hasBalance=${hasBalance}&status=${encodeURIComponent(status === 'ALL' ? '' : status)}${resellerFilter ? `&resellerId=${encodeURIComponent(resellerFilter)}` : ''}`
  const query = useQuery({ queryKey: queryKeys.customers.list(customerPath), queryFn: ({ signal }) => apiGet<ApiPage<Customer>>(customerPath, signal) })
  const optionsQuery = useQuery({ queryKey: queryKeys.customers.filterOptions, queryFn: ({ signal }) => apiGet<{ sellers: Array<{ id: string; name: string }> }>('/api/customers/filter-options', signal), enabled: isAdmin })
  const resellersPath = '/api/customers?page=0&size=100&kind=RESELLER&status='
  const resellersQuery = useQuery({ queryKey: queryKeys.customers.resellers('all'), queryFn: ({ signal }) => apiGetAllPages<Customer>(resellersPath, signal), enabled: isAdmin })
  const sellersQuery = useQuery({ queryKey: queryKeys.sellers.options('customers'), queryFn: ({ signal }) => apiGetAllPages<Seller>('/api/sellers?page=0&size=100', signal), enabled: isAdmin && formOpen })
  const assignmentQuery = useQuery({ queryKey: queryKeys.customers.assignmentContext, queryFn: ({ signal }) => apiGet<Seller>('/api/customers/assignment-context', signal), enabled: !isAdmin && formOpen, retry: false })
  const zonesQuery = useQuery({ queryKey: queryKeys.zones.all, queryFn: ({ signal }) => apiGet<Zone[]>('/api/zones', signal), enabled: formOpen })
  const priceListOptionsPath = '/api/pricing/lists?page=0&size=100'
  const listsQuery = useQuery({ queryKey: queryKeys.pricing.listOptions('customers'), queryFn: ({ signal }) => apiGetAllPages<PriceList>(priceListOptionsPath, signal), enabled: canList && (formOpen || Boolean(listCustomer)) })
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [actionSaving, setActionSaving] = useState(false)
  const [statusCustomer, setStatusCustomer] = useState<Customer | undefined>()
  const [statusFocusId, setStatusFocusId] = useState('')
  useEffect(() => {
    if (!statusCustomer && statusFocusId) {
      (document.getElementById(statusFocusId) ?? document.getElementById('customers-search'))?.focus()
      setStatusFocusId('')
    }
  }, [statusCustomer, statusFocusId])
  const [resellerCustomer, setResellerCustomer] = useState<Customer>()
  const [settlementCustomer, setSettlementCustomer] = useState<Customer>()
  const [loadingSettlement, setLoadingSettlement] = useState('')

  async function openSettlement(customerId: string) {
    if (loadingSettlement) return
    setLoadingSettlement(customerId)
    setActionError('')
    setFeedback('')
    try {
      const customer = await apiGet<Customer>(`/api/customers/${customerId}/account-balance`)
      if (Number(customer.balance ?? 0) > 0) setSettlementCustomer(customer)
      else { await invalidate(); setFeedback('El cliente no tiene saldo pendiente.'); }
    } catch (cause) {
      setActionError(errorMessage(cause, 'No se pudo consultar el saldo del cliente.'))
    } finally { setLoadingSettlement('') }
  }

  async function invalidate() {
    await invalidateDomains(queryClient, 'customers', 'orders', 'sales', 'reports')
  }


  async function changeStatus() {
    if (!statusCustomer || actionSaving) return
    setActionSaving(true)
    setActionError('')
    try {
      const status = statusCustomer.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
      await apiPatch(`/api/customers/${statusCustomer.id}/status`, { status })
      await invalidate()
      setStatusFocusId(`customer-actions-${statusCustomer.id}`)
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
    hasDebt: Number(customer.balance ?? 0) > 0 ? 'true' : '',
    status: customer.status,
    priceListId: customer.priceListId ?? '',
    kind: customer.kind ?? 'REGULAR',
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Cliente', emphasis: true, render: (value) => <span>{value}</span> },
    { key: 'cuitId', label: 'CUIT' },
    { key: 'seller', label: 'Vendedor' },
    { key: 'balance', label: 'Saldo', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> },
  ]
  const findCustomer = (id: string) => query.data?.content.find(customer => customer.id === id)
  if (canView || canEdit || canStatus || canSettle || canList || canExport || canManageResellers) columns.push(
    { key: 'actions', label: 'Acciones', render: (_value, row) => <div className="table-row-actions">
      {canView && <Button variant="secondary" href={`/customers/${row.id}?returnTo=${encodeURIComponent(location.pathname + location.search)}`} aria-label={`Ver cliente ${row.name}`}>Ver cliente</Button>}
      {canSettle && row.hasDebt && (row.kind !== 'RESELLER' || isAdmin) && <Button id={`settle-account-${row.id}`} variant="secondary" aria-label={`Registrar pago de ${row.name}`} disabled={Boolean(loadingSettlement)} onClick={() => openSettlement(row.id)}>{loadingSettlement === row.id ? 'Consultando saldo...' : 'Registrar pago'}</Button>}
      {canEdit && <Button variant="link" aria-label={`Editar ${row.name}`} onClick={() => { setFormCustomer(query.data?.content.find((customer) => customer.id === row.id)); setShowForm(false) }}>Editar</Button>}
      {(canList || canManageResellers || canStatus || canExport) && <SecondaryRowActions triggerId={`customer-actions-${row.id}`} label={'Más acciones de ' + row.name}>
        {canList && <Button variant="link" onClick={() => setListCustomer(query.data?.content.find(customer => customer.id === row.id))}>Cambiar lista</Button>}
        {canManageResellers && <Button variant="link" aria-label={`Revendedor de ${row.name}`} onClick={() => { setFeedback(''); setResellerCustomer(findCustomer(row.id)) }}>Revendedor</Button>}
        {canStatus && <Button variant={row.status === 'INACTIVE' ? 'link' : 'danger'} onClick={() => { setActionError(''); setStatusCustomer(query.data?.content.find(customer => customer.id === row.id)) }}>{row.status === 'INACTIVE' ? 'Activar cliente' : 'Desactivar cliente'}</Button>}
        {canExport && <DocumentActions path={`/api/documents/customers/${row.id}/statement?format=a4`} filename={`estado-deuda-${row.name}.pdf`} printLabel="Imprimir estado de deuda" downloadLabel="Descargar estado de deuda" />}
      </SecondaryRowActions>}
    </div> })

  return <>
    <PageHeader title="Clientes" description={isAdmin ? 'Gestioná clientes, vendedor, lista de precios y cuenta corriente.' : 'Consultá únicamente los clientes asignados a tu vendedor.'} actions={canCreate ? <Button onClick={() => { setFormCustomer(undefined); setShowForm((value) => !value) }}>+ Nuevo cliente</Button> : undefined} />
    {feedback && <p className="success-text" role="status"><CurrencyText>{feedback}</CurrencyText></p>}
    {actionError && <p className="error-text" role="alert"><CurrencyText>{actionError}</CurrencyText></p>}
    {loadingSettlement && <p className="helper-text" role="status">Consultando el saldo actual del cliente…</p>}
    {canSettle && settlementCustomer && <AccountSettlementDialog customer={settlementCustomer} onClose={() => setSettlementCustomer(undefined)} onSaved={(remaining) => { setSettlementCustomer(undefined); setFeedback(remaining > 0 ? `Pago registrado. Saldo pendiente: ${formatMoney(remaining, 2)}.` : isAdmin ? 'Cuenta corriente saldada correctamente.' : 'Deuda asignada saldada correctamente.') }} />}
    {formOpen && <CustomerForm initial={formCustomer} sellers={isAdmin ? sellersQuery.data?.content ?? [] : assignmentQuery.data ? [assignmentQuery.data] : []} sellersLoading={isAdmin ? sellersQuery.isLoading : assignmentQuery.isPending} sellersError={isAdmin ? sellersQuery.isError : assignmentQuery.isError} onRetrySellers={() => { void (isAdmin ? sellersQuery.refetch() : assignmentQuery.refetch()) }} zones={zonesQuery.data ?? []} zonesLoading={zonesQuery.isLoading} zonesError={zonesQuery.isError} onRetryZones={() => zonesQuery.refetch()} priceLists={listsQuery.data?.content ?? []} priceListsLoading={listsQuery.isLoading} priceListsError={listsQuery.isError} onRetryPriceLists={() => listsQuery.refetch()} onSuccess={setFeedback} onDone={() => { setShowForm(false); setFormCustomer(undefined) }} onChangeStatus={(customer) => { setActionError(''); setStatusCustomer(customer) }} />}
    {resellerCustomer && canManageResellers && <CustomerResellerDialog customer={resellerCustomer} onClose={() => setResellerCustomer(undefined)} onSaved={async (message) => { await invalidate(); setResellerCustomer(undefined); setFeedback(message) }} />}
    {listCustomer && canList && <CustomerPriceListDialog customer={listCustomer} lists={listsQuery.data?.content ?? []} loading={listsQuery.isPending} failed={listsQuery.isError} retry={() => void listsQuery.refetch()} onClose={() => setListCustomer(undefined)} onSaved={async () => { await invalidate(); setListCustomer(undefined); setFeedback('Lista de precios actualizada.') }} />}
    <Panel><div className="toolbar">
      <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Buscar clientes</span><input id="customers-search" className="input search-input [@media(max-width:640px)]:max-w-none" placeholder="Nombre o identificación" aria-label="Buscar clientes" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
      {isAdmin && <SellerSelect options={optionsQuery.data?.sellers ?? []} value={sellerId} onChange={(value) => setFilter('sellerId', value)} loading={optionsQuery.isLoading} disabled={optionsQuery.isError} />}
      {isAdmin && <CustomerSelect label="Buscar por revendedor" emptyLabel="Todos los revendedores" options={(resellersQuery.data?.content ?? []).map(reseller => ({ id: reseller.id, name: reseller.name }))} value={resellerFilter} onChange={(value) => setFilter('resellerId', value)} loading={resellersQuery.isLoading} disabled={resellersQuery.isError} />}
      <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Cuenta corriente</span><select aria-label="Cuenta corriente" className="select [@media(max-width:640px)]:w-full [@media(max-width:640px)]:min-w-0" value={hasBalance ? 'true' : ''} onChange={(event) => setFilter('hasBalance', event.target.value)}><option value="">Todos los saldos</option><option value="true">Solo con saldo</option></select></label>
      <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Estado</span><select aria-label="Estado" className="select [@media(max-width:640px)]:w-full [@media(max-width:640px)]:min-w-0" value={status} onChange={(event) => setFilter('status', event.target.value)}><option value="ACTIVE">Activo</option><option value="INACTIVE">Inactivo</option><option value="ALL">Todos</option></select></label>
    </div>
      {isAdmin && optionsQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los vendedores del filtro. <Button variant="link" type="button" onClick={() => optionsQuery.refetch()}>Reintentar filtro</Button></p>}
      {isAdmin && resellersQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los revendedores del filtro. <Button variant="link" type="button" onClick={() => resellersQuery.refetch()}>Reintentar revendedores</Button></p>}
      {query.isLoading ? <EmptyState state="loading" title="Cargando clientes" description="Consultando clientes a través de la API." /> : query.isError ? <EmptyState state="error" title="No se pudieron cargar los clientes" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="No hay clientes para mostrar" description="No se encontraron clientes con los filtros seleccionados." /> : <><DataTable className="compact-list-table customers-list-table" columns={columns} rows={rows} /><Pagination summary={<>Página {page + 1} · {query.data?.totalElements ?? 0} clientes</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= (query.data?.totalPages ?? 0)} /></>}
    </Panel>
    {isAdmin && sellersQuery.isError && (showForm || formCustomer) && <p className="error-text" role="alert">No se pudieron cargar los vendedores. <Button variant="link" type="button" onClick={() => sellersQuery.refetch()}>Reintentar</Button></p>}
    {statusCustomer && canStatus && <ConfirmationDialog title={`¿Querés ${statusCustomer.status === 'ACTIVE' ? 'desactivar' : 'activar'} a ${statusCustomer.name}?`} description="Los pedidos y movimientos históricos se conservan." confirmLabel="Confirmar" confirmVariant={statusCustomer.status === 'ACTIVE' ? 'danger' : 'primary'} pending={actionSaving} error={actionError} onCancel={() => setStatusCustomer(undefined)} onConfirm={() => void changeStatus()} />}
  </>
}
