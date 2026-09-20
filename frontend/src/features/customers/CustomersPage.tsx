import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPatch, apiPost, apiPut, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge, type BadgeTone } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type Customer = {
  id: string
  name: string
  taxId: string
  seller?: string
  sellerId?: string
  priceListId?: string | null
  balance?: number
  status: 'ACTIVE' | 'INACTIVE' | string
}

type Seller = { id: string; displayName: string; email: string }
type PriceList = { id: string; code: string; name: string; status: string }

const CUSTOMER_QUERY_KEY = ['/api/customers?page=0&size=20']

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 0 }).format(Number(value ?? 0))
}

function errorMessage(cause: unknown, fallback: string) {
  const message = cause instanceof Error ? cause.message : ''
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
  onDone,
  onSaved,
}: {
  initial?: Customer
  sellers: Seller[]
  onDone: () => void
  onSaved: () => Promise<void>
}) {
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const [businessName, setBusinessName] = useState(initial?.name ?? '')
  const [taxId, setTaxId] = useState(initial?.taxId ?? '')
  const [sellerId, setSellerId] = useState(initial?.sellerId ?? '')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setSaving(true)
    setError('')
    try {
      const body = { businessName, taxId, sellerId: sellerId || undefined }
      if (initial) await apiPut(`/api/customers/${initial.id}`, body)
      else await apiPost('/api/customers', body)
      await queryClient.invalidateQueries({ queryKey: CUSTOMER_QUERY_KEY })
      await onSaved()
    } catch (cause) {
      setError(errorMessage(cause, 'No se pudo guardar el cliente.'))
    } finally {
      setSaving(false)
    }
  }

  return <Panel title={initial ? 'Editar cliente' : 'Nuevo cliente'}>
    <form className="form-grid" onSubmit={submit}>
      <label className="field"><span>Razón social</span><input className="input" value={businessName} onChange={(event) => setBusinessName(event.target.value)} required /></label>
      <label className="field"><span>Identificación fiscal</span><input className="input" value={taxId} onChange={(event) => setTaxId(event.target.value)} required /></label>
      {isAdmin && <label className="field"><span>Vendedor asignado</span><select className="select" aria-label="Vendedor asignado" value={sellerId} onChange={(event) => setSellerId(event.target.value)}><option value="">Sin asignar</option>{sellers.map((seller) => <option value={seller.id} key={seller.id}>{seller.displayName} ({seller.email})</option>)}</select></label>}
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={onDone}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : initial ? 'Guardar cambios' : 'Guardar cliente'}</Button></div>
    </form>
  </Panel>
}

export default function CustomersPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: CUSTOMER_QUERY_KEY, queryFn: () => apiGet<ApiPage<Customer>>('/api/customers?page=0&size=20') })
  const sellersQuery = useQuery({ queryKey: ['/api/sellers?page=0&size=100'], queryFn: () => apiGet<ApiPage<Seller>>('/api/sellers?page=0&size=100'), enabled: isAdmin })
  const listsQuery = useQuery({ queryKey: ['/api/pricing/lists?page=0&size=100'], queryFn: () => apiGet<ApiPage<PriceList>>('/api/pricing/lists?page=0&size=100'), enabled: isAdmin })
  const [formCustomer, setFormCustomer] = useState<Customer | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [selectedLists, setSelectedLists] = useState<Record<string, string>>({})
  const [actionError, setActionError] = useState('')
  const [actionSaving, setActionSaving] = useState(false)
  const [statusCustomer, setStatusCustomer] = useState<Customer | undefined>()

  async function invalidate() {
    await queryClient.invalidateQueries({ queryKey: CUSTOMER_QUERY_KEY })
  }

  async function assignPriceList(customer: Customer) {
    if (actionSaving) return
    setActionSaving(true)
    setActionError('')
    try {
      await apiPatch(`/api/customers/${customer.id}/price-list`, { priceListId: selectedLists[customer.id] || null })
      await invalidate()
    } catch (cause) {
      setActionError(errorMessage(cause, 'No se pudo asignar la lista de precios.'))
    } finally {
      setActionSaving(false)
    }
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
    } catch (cause) {
      setActionError(errorMessage(cause, 'No se pudo actualizar el estado del cliente.'))
    } finally {
      setActionSaving(false)
    }
  }

  const rows = (query.data?.content ?? []).map((customer) => ({
    id: customer.id,
    name: customer.name,
    taxId: customer.taxId,
    seller: customer.seller ?? 'Sin asignar',
    balance: money(customer.balance),
    status: customer.status,
    priceListId: customer.priceListId ?? '',
  }))
  const columns: TableColumn[] = [
    { key: 'name', label: 'Cliente', emphasis: true },
    { key: 'taxId', label: 'Identificación' },
    { key: 'seller', label: 'Vendedor' },
    { key: 'balance', label: 'Saldo', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <StatusBadge value={value} /> },
    ...(isAdmin ? [{ key: 'actions', label: '', render: (_value: string, row: Record<string, string>) => <div className="page-actions"><Button variant="link" onClick={() => { setFormCustomer(query.data?.content.find((customer) => customer.id === row.id)); setShowForm(false) }}>Editar</Button><Button variant="link" onClick={() => setStatusCustomer(query.data?.content.find((customer) => customer.id === row.id))}>{row.status === 'ACTIVE' ? 'Desactivar cliente' : 'Activar cliente'}</Button></div> }] : []),
  ]

  return <>
    <PageHeader eyebrow="Operación" title="Clientes" description="Gestioná clientes, vendedor, lista de precios y cuenta corriente." actions={isAdmin ? <Button onClick={() => { setFormCustomer(undefined); setShowForm((value) => !value) }}>+ Nuevo cliente</Button> : undefined} />
    {actionError && <p className="error-text" role="alert">{actionError}</p>}
    {isAdmin && (showForm || formCustomer) && <CustomerForm initial={formCustomer} sellers={sellersQuery.data?.content ?? []} onDone={() => { setShowForm(false); setFormCustomer(undefined) }} onSaved={async () => { if (!formCustomer) setShowForm(false) }} />}
    {isAdmin && formCustomer && <Panel title="Lista de precios" description="La asignación se aplica al cliente seleccionado."><div className="form-grid"><label className="field"><span>Lista de precios</span><select className="select" aria-label="Lista de precios" value={selectedLists[formCustomer.id] ?? formCustomer.priceListId ?? ''} onChange={(event) => setSelectedLists((current) => ({ ...current, [formCustomer.id]: event.target.value }))}><option value="">Sin lista asignada</option>{(listsQuery.data?.content ?? []).map((list) => <option value={list.id} key={list.id}>{list.code} - {list.name}</option>)}</select></label><Button type="button" onClick={() => assignPriceList(formCustomer)} disabled={actionSaving}>{actionSaving ? 'Guardando...' : 'Asignar lista'}</Button></div></Panel>}
    <Panel><div className="toolbar"><input className="input search-input" placeholder="Buscar por nombre o identificación..." aria-label="Buscar clientes" /><Button variant="secondary">Filtrar</Button></div>
      {query.isLoading ? <EmptyState title="Cargando clientes" description="Consultando clientes a través de la API." /> : query.isError ? <EmptyState title="No se pudieron cargar los clientes" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="Todavía no hay clientes" description="Creá el primer cliente para comenzar a gestionar la operación." action={isAdmin ? <Button onClick={() => setShowForm(true)}>+ Nuevo cliente</Button> : undefined} /> : <DataTable columns={columns} rows={rows} />}
    </Panel>
    {statusCustomer && <div role="dialog" aria-modal="true" aria-labelledby="status-dialog-title" className="modal-backdrop"><Panel title="Confirmar cambio de estado"><h2 id="status-dialog-title">¿Querés {statusCustomer.status === 'ACTIVE' ? 'desactivar' : 'activar'} a {statusCustomer.name}?</h2><div className="page-actions"><Button variant="secondary" onClick={() => setStatusCustomer(undefined)}>Cancelar</Button><Button onClick={changeStatus} disabled={actionSaving}>{actionSaving ? 'Guardando...' : 'Confirmar'}</Button></div></Panel></div>}
  </>
}
