import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { useQuery } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet, ApiError, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { StatCard } from '../../shared/components/StatCard'
import { useUrlListState } from '../../shared/useUrlListState'
import type { Customer } from './customerTypes'

type CustomerDetail = Customer & { priceList?: string | null; priceListCode?: string | null; createdAt?: string }
type CustomerOrder = { id: string; number: string; seller: string; total: number; balance?: number; status: string; date: string }
type AssociatedCustomer = { id: string; name: string; number?: string; balance?: number; status: string }
const money = (value: number = 0) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)
const date = (value?: string) => value ? new Intl.DateTimeFormat('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'America/Argentina/Buenos_Aires' }).format(new Date(value)) : '—'
const orderStatus: Record<string, string> = { CONFIRMED: 'Confirmado', DELIVERED: 'Entregado', CANCELLED: 'Cancelado' }

export default function CustomerDetailPage() {
  const { customerId = '' } = useParams()
  const [params] = useSearchParams()
  const returnTo = params.get('returnTo') ?? ''
  const back = returnTo === '/customers' || returnTo.startsWith('/customers?') ? returnTo : '/customers'
  const { page, pageSize, setPage } = useUrlListState([], 20, 'ordersPage')
  const clientsList = useUrlListState([], 20, 'clientsPage')
  const isAdmin = hasAuthority('ADMIN_ALL')
  const path = `/api/customers/${customerId}`
  const customerQuery = useQuery({ queryKey: [path], queryFn: () => apiGet<CustomerDetail>(path), enabled: Boolean(customerId) })
  const ordersPath = `${path}/orders?page=${page}&size=${pageSize}`
  const ordersQuery = useQuery({ queryKey: [ordersPath], queryFn: () => apiGet<ApiPage<CustomerOrder>>(ordersPath), enabled: Boolean(customerQuery.data) && !customerQuery.isError })
  const reseller = customerQuery.data?.kind === 'RESELLER'
  const clientsPath = `/api/customers?page=${clientsList.page}&size=${clientsList.pageSize}&status=&resellerId=${encodeURIComponent(customerId)}`
  const clientsQuery = useQuery({ queryKey: [clientsPath], queryFn: () => apiGet<ApiPage<AssociatedCustomer>>(clientsPath), enabled: reseller && isAdmin })
  const backAction = <Button variant="secondary" href={back}>Volver a clientes</Button>

  if (customerQuery.isPending) return <>
    <PageHeader eyebrow="Operación" title="Cliente" actions={backAction} />
    <Panel><div role="status"><EmptyState title="Cargando cliente" description="Consultando los datos del cliente." /></div></Panel>
  </>
  if (customerQuery.isError) {
    const unavailable = customerQuery.error instanceof ApiError && customerQuery.error.status === 404
    return <>
      <PageHeader eyebrow="Operación" title="Cliente" actions={backAction} />
      <Panel><div role="alert"><EmptyState title={unavailable ? 'Cliente no disponible' : 'No se pudo cargar el cliente'}
        description={unavailable ? 'El cliente no existe o no tenés acceso a sus datos.' : 'No se pudieron consultar los datos. Podés reintentar.'}
        action={!unavailable && <Button variant="secondary" onClick={() => customerQuery.refetch()}>Reintentar datos</Button>} /></div></Panel>
    </>
  }

  const customer = customerQuery.data
  const details = [
    ['N.º de cliente', customer.number],
    ['Razón social', customer.name], ['CUIT', customer.cuitId], ['Mail', customer.email],
    ['Celular', customer.phone], ['Dirección', customer.address], ['Zona', customer.zone],
    ['Vendedor asignado', customer.seller || 'Sin asignar'],
    ...(customer.kind === 'RESELLER' ? [['Tipo de cliente', `Revendedor · ${customer.associatedCustomersCount ?? 0} clientes asociados`]] : []),
    ...(customer.resellerId ? [['Revendedor', `${customer.reseller ?? ''} (sus pedidos nuevos se cargan a esa cuenta)`]] : []),
    ['Lista de precios predeterminada', customer.priceList ? `${customer.priceListCode} - ${customer.priceList}` : 'Sin lista asignada'],
    ['Fecha de alta', date(customer.createdAt)],
  ]
  const columns: TableColumn[] = [
    { key: 'number', label: 'Pedido', emphasis: true },
    { key: 'date', label: 'Fecha' },
    { key: 'seller', label: 'Vendedor' },
    { key: 'total', label: 'Total', align: 'right' },
    { key: 'balance', label: 'Por cobrar', align: 'right', render: (value, row) => <span className={Number(row.pendingBalance) > 0 ? 'font-bold text-accent-dark' : 'text-muted'}>{value}</span> },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'CANCELLED' ? 'muted' : value === 'DELIVERED' ? 'strong' : 'soft'}>{orderStatus[value] ?? value}</Badge> },
    { key: 'action', label: '', render: (_value, row) => <div className="table-row-actions"><Button variant="link" href={`/orders/${row.id}`} aria-label={`Ver pedido ${row.number}`}>Ver pedido</Button></div> },
  ]
  const rows = (ordersQuery.data?.content ?? []).map((order) => ({ id: order.id, number: order.number, date: date(order.date), seller: order.seller, total: money(order.total), balance: order.balance === undefined ? '—' : order.balance > 0 ? money(order.balance) : 'Sin deuda', pendingBalance: String(order.balance ?? ''), status: order.status }))
  const balance = Number(customer.balance ?? 0)
  const clientColumns: TableColumn[] = [
    { key: 'name', label: 'Cliente', emphasis: true, render: (value, row) => <div className="min-w-0"><span>{value}</span>{row.number && <span className="block text-[11px] font-normal text-muted">Cliente {row.number}</span>}</div> },
    { key: 'balance', label: 'Saldo propio', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'ACTIVE' ? 'strong' : 'muted'}>{value === 'ACTIVE' ? 'Activo' : 'Inactivo'}</Badge> },
    { key: 'action', label: '', render: (_value, row) => <div className="table-row-actions"><Button variant="link" href={`/customers/${row.id}`} aria-label={`Ver cliente ${row.name}`}>Ver cliente</Button></div> },
  ]
  const clientRows = (clientsQuery.data?.content ?? []).map((client) => ({ id: client.id, name: client.name, number: client.number ?? '', balance: money(Number(client.balance ?? 0)), status: client.status }))
  const purchasePath = `/customers/${encodeURIComponent(customerId)}/purchases${params.size ? `?${new URLSearchParams({ customerFilters: params.toString() })}` : ''}`
  return <>
    <PageHeader eyebrow="Operación" title={customer.name} description="Datos del cliente e historial de pedidos." actions={<>
      {hasPermission('REPORTS_VIEW') && <Button variant="secondary" href={purchasePath}>Ver compras del cliente</Button>}
      {reseller && isAdmin && <Button variant="secondary" href={`/sales?accountCustomerId=${encodeURIComponent(customerId)}&pendingBalance=true`}>Ver deuda de la cuenta</Button>}
      {backAction}
    </>} />
    <div className="content-grid">
      <div className="content-grid two-thirds">
        <Panel title="Datos del cliente" action={<span className="page-actions">{customer.kind === 'RESELLER' && <Badge>Revendedor</Badge>}<Badge tone={customer.status === 'ACTIVE' ? 'strong' : 'muted'}>{customer.status === 'ACTIVE' ? 'Activo' : 'Inactivo'}</Badge></span>}>
          <dl className="customer-detail-fields">{details.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '—'}</dd></div>)}</dl>
        </Panel>
        <div className="self-start"><StatCard label={hasAuthority('ADMIN_ALL') ? 'Saldo de cuenta corriente' : 'Saldo de tus ventas'} value={money(balance)} detail={customer.resellerId ? `Deuda propia del cliente. Sus pedidos nuevos se cargan a la cuenta de ${customer.reseller ?? 'su revendedor'}.` : customer.kind === 'RESELLER' && hasAuthority('ADMIN_ALL') ? 'Incluye los pedidos de sus clientes asociados.' : hasAuthority('ADMIN_ALL') ? balance > 0 ? 'Saldo por cobrar al cliente.' : balance < 0 ? 'Saldo a favor del cliente.' : 'El cliente no tiene saldo pendiente.' : balance > 0 ? 'Deuda visible para tu usuario.' : 'No hay deuda asignada pendiente.'} />
          {hasPermission('CUSTOMER_STATEMENT_EXPORT') && <div className="customer-statement-actions" aria-label="Estado de deuda">
            <p className="helper-text m-0">Estado de deuda (ventas con saldo pendiente)</p>
            <DocumentActions path={`/api/documents/customers/${customer.id}/statement?format=a4`} filename={`estado-deuda-${customer.name}.pdf`} printLabel="Imprimir estado A4" downloadLabel="Descargar A4" />
            <DocumentActions path={`/api/documents/customers/${customer.id}/statement?format=ticket`} filename={`estado-deuda-${customer.name}-ticket.pdf`} printLabel="Imprimir ticket 80 mm" downloadLabel="Descargar ticket" />
          </div>}</div>
      </div>
      {reseller && isAdmin && <Panel title="Clientes asociados" description="Sus pedidos se cargan a la cuenta corriente de este revendedor.">
        {clientsQuery.isPending ? <div role="status"><EmptyState title="Cargando clientes asociados" description="Consultando los clientes del revendedor." /></div>
          : clientsQuery.isError ? <div role="alert"><EmptyState title="No se pudieron cargar los clientes asociados" description="Los datos del revendedor siguen disponibles." action={<Button variant="secondary" onClick={() => clientsQuery.refetch()}>Reintentar clientes</Button>} /></div>
          : clientRows.length === 0 ? <EmptyState title="Sin clientes asociados" description="Asigná clientes desde el listado con «Asignar revendedor»." />
          : <DataTable columns={clientColumns} rows={clientRows} />}
        {clientsQuery.isSuccess && (clientsQuery.data.totalElements > 0 || clientsList.page > 0) && <div className="pagination" aria-label="Paginación de clientes asociados"><span>Página {clientsList.page + 1} · {clientsQuery.data.totalElements} clientes</span><div>
          <Button variant="secondary" onClick={() => clientsList.setPage(clientsList.page - 1)} disabled={clientsList.page === 0}>Anterior</Button>
          <Button variant="secondary" onClick={() => clientsList.setPage(clientsList.page + 1)} disabled={clientsList.page + 1 >= clientsQuery.data.totalPages}>Siguiente</Button>
        </div></div>}
      </Panel>}
      <Panel title="Historial de pedidos" description="Pedidos del cliente, del más reciente al más antiguo. El saldo pendiente muestra la deuda actual de cada pedido en cuenta corriente.">
        {ordersQuery.isPending ? <div role="status"><EmptyState title="Cargando pedidos" description="Consultando el historial del cliente." /></div>
          : ordersQuery.isError ? <div role="alert"><EmptyState title="No se pudo cargar el historial" description="Los datos del cliente siguen disponibles. Podés reintentar la consulta." action={<Button variant="secondary" onClick={() => ordersQuery.refetch()}>Reintentar historial</Button>} /></div>
          : rows.length === 0 ? <EmptyState title="No hay pedidos para mostrar" description="Este cliente no tiene pedidos visibles para tu usuario en esta página." />
          : <DataTable columns={columns} rows={rows} />}
        {ordersQuery.isSuccess && (ordersQuery.data.totalElements > 0 || page > 0) && <div className="pagination" aria-label="Paginación del historial"><span>Página {page + 1} · {ordersQuery.data.totalElements} pedidos</span><div>
          <Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button>
          <Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= ordersQuery.data.totalPages}>Siguiente</Button>
        </div></div>}
      </Panel>
    </div>
  </>
}
