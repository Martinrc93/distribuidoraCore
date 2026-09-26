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
import { useUrlListState } from '../../shared/useUrlListState'

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
  priceLists,
  priceListsError,
  onRetryPriceLists,
  onDone,
  onSuccess,
  onChangeStatus,
}: {
  initial?: Customer
  sellers: Seller[]
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
  const [confirmDiscard, setConfirmDiscard] = useState(false)
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
    setConfirmDiscard(false)
  }, [initial])

  const changed = businessName !== (initial?.name ?? '')
    || cuitId !== (initial?.cuitId ?? '')
    || email !== (initial?.email ?? '')
    || phone !== (initial?.phone ?? '')
    || address !== (initial?.address ?? '')
    || zone !== (initial?.zone ?? '')
    || sellerId !== (initial?.sellerId ?? '')
    || priceListId !== (initial?.priceListId ?? '')

  function requestClose() {
    if (saving) return
    if (changed) setConfirmDiscard(true)
    else onDone()
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setSaving(true)
    setError('')
    try {
      const body = { businessName, cuitId: cuitId.trim() || null, email: email.trim() || null, phone: phone.trim() || null, address: address.trim() || null, zone: zone.trim() || null, sellerId: sellerId || undefined, ...(initial ? { priceListId: priceListId || null } : {}) }
      if (initial) await apiPut(`/api/customers/${initial.id}`, body)
      else await apiPost('/api/customers', body)
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/customers?') })
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
  <Panel title={initial ? 'Editar cliente' : 'Nuevo cliente'} action={<button className="customer-modal-close" type="button" aria-label="Cerrar modal" onClick={requestClose} disabled={saving}><span aria-hidden="true">×</span></button>}>
    <form className="form-grid" onSubmit={submit}>
      <label className="field"><span>Razón social</span><input className="input" value={businessName} onChange={(event) => setBusinessName(event.target.value)} required /></label>
      <label className="field"><span>CUIT (opcional)</span><input className="input" value={cuitId} onChange={(event) => setCuitId(event.target.value)} /></label>
      <label className="field"><span>Mail</span><input className="input" type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label>
      <label className="field"><span>Celular</span><input className="input" type="tel" value={phone} onChange={(event) => setPhone(event.target.value)} /></label>
      <label className="field"><span>Dirección</span><input className="input" value={address} onChange={(event) => setAddress(event.target.value)} /></label>
      <label className="field"><span>Zona</span><input className="input" value={zone} onChange={(event) => setZone(event.target.value)} /></label>
      {isAdmin && <label className="field"><span>Vendedor asignado</span><select className="select" aria-label="Vendedor asignado" value={sellerId} onChange={(event) => setSellerId(event.target.value)}><option value="">Sin asignar</option>{sellers.map((seller) => <option value={seller.id} key={seller.id}>{seller.displayName} ({seller.email})</option>)}</select></label>}
      {initial && <label className="field"><span>Lista de precios</span><select className="select" aria-label="Lista de precios" value={priceListId} onChange={(event) => setPriceListId(event.target.value)} disabled={priceListsError}><option value="">Sin lista asignada</option>{priceLists.map((list) => <option value={list.id} key={list.id}>{list.code} - {list.name}</option>)}</select>{priceListsError && <span className="error-text">No se pudieron cargar las listas. <Button variant="link" type="button" onClick={onRetryPriceLists}>Reintentar</Button></span>}</label>}
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions customer-form-actions">{initial && <Button variant="secondary" type="button" onClick={() => onChangeStatus(initial)} disabled={saving}>{initial.status === 'INACTIVE' ? 'Activar cliente' : 'Desactivar cliente'}</Button>}<div className="customer-form-actions-right"><Button variant="secondary" type="button" onClick={requestClose} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving || (initial !== undefined && priceListsError)}>{saving ? 'Guardando...' : initial ? 'Guardar cambios' : 'Guardar cliente'}</Button></div></div>
    </form>
  </Panel>
  {confirmDiscard && <div role="alertdialog" aria-modal="true" aria-labelledby="discard-customer-title" className="customer-discard-backdrop"><Panel title="Descartar cambios"><p id="discard-customer-title">Tenés cambios sin guardar. ¿Querés salir sin guardar los cambios?</p><div className="page-actions"><Button variant="secondary" type="button" onClick={() => setConfirmDiscard(false)}>Seguir editando</Button><Button type="button" onClick={onDone}>Salir sin guardar</Button></div></Panel></div>}
  </>
}

export default function CustomersPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const search = getFilter('search')
  const customerPath = `/api/customers?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}`
  const query = useQuery({ queryKey: [customerPath], queryFn: () => apiGet<ApiPage<Customer>>(customerPath) })
  const sellersQuery = useQuery({ queryKey: ['/api/sellers?page=0&size=100'], queryFn: () => apiGet<ApiPage<Seller>>('/api/sellers?page=0&size=100'), enabled: isAdmin })
  const listsQuery = useQuery({ queryKey: ['/api/pricing/lists?page=0&size=100'], queryFn: () => apiGet<ApiPage<PriceList>>('/api/pricing/lists?page=0&size=100'), enabled: isAdmin })
  const [formCustomer, setFormCustomer] = useState<Customer | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [actionError, setActionError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [actionSaving, setActionSaving] = useState(false)
  const [statusCustomer, setStatusCustomer] = useState<Customer | undefined>()

  async function invalidate() {
    await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/customers?') })
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
    {isAdmin && (showForm || formCustomer) && <div role="dialog" aria-modal="true" aria-label={formCustomer ? 'Editar cliente' : 'Nuevo cliente'} className="modal-backdrop"><div className="customer-modal"><CustomerForm initial={formCustomer} sellers={sellersQuery.data?.content ?? []} priceLists={listsQuery.data?.content ?? []} priceListsError={listsQuery.isError} onRetryPriceLists={() => listsQuery.refetch()} onSuccess={setFeedback} onDone={() => { setShowForm(false); setFormCustomer(undefined) }} onChangeStatus={(customer) => { setActionError(''); setStatusCustomer(customer) }} /></div></div>}
    <Panel><div className="toolbar"><label className="field"><span>Buscar clientes</span><input className="input search-input" placeholder="Nombre o identificación" aria-label="Buscar clientes" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label></div>
      {query.isLoading ? <EmptyState title="Cargando clientes" description="Consultando clientes a través de la API." /> : query.isError ? <EmptyState title="No se pudieron cargar los clientes" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="Todavía no hay clientes" description="Creá el primer cliente para comenzar a gestionar la operación." action={isAdmin ? <Button onClick={() => setShowForm(true)}>+ Nuevo cliente</Button> : undefined} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} clientes</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    {isAdmin && sellersQuery.isError && (showForm || formCustomer) && <p className="error-text" role="alert">No se pudieron cargar los vendedores. <Button variant="link" type="button" onClick={() => sellersQuery.refetch()}>Reintentar</Button></p>}
    {statusCustomer && <div role="dialog" aria-modal="true" aria-labelledby="status-dialog-title" className="modal-backdrop"><Panel title="Confirmar cambio de estado"><h2 id="status-dialog-title">¿Querés {statusCustomer.status === 'ACTIVE' ? 'desactivar' : 'activar'} a {statusCustomer.name}?</h2><div className="page-actions"><Button variant="secondary" onClick={() => setStatusCustomer(undefined)}>Cancelar</Button><Button onClick={changeStatus} disabled={actionSaving}>{actionSaving ? 'Guardando...' : 'Confirmar'}</Button></div></Panel></div>}
  </>
}
