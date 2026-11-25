import { Pagination } from '../../shared/components/Pagination'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { useQuery } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet, ApiError, type ApiPage } from '../../shared/api/client'
import { queryKeys } from '../../shared/api/queryKeys'
import { formatBusinessDateTimeNumeric } from '../../shared/dates'
import { formatMoney } from '../../shared/format'
import { Button } from '../../shared/components/Button'
import { orderStatusLabel } from '../orders/orderStatus'
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
const money = (value: number = 0) => formatMoney(value, 2)
const date = (value?: string) => formatBusinessDateTimeNumeric(value)

export default function CustomerDetailPage() {
  const { customerId = '' } = useParams()
  const [params] = useSearchParams()
  const returnTo = params.get('returnTo') ?? ''
  const back = returnTo === '/customers' || returnTo.startsWith('/customers?') ? returnTo : '/customers'
  const { page, pageSize, setPage } = useUrlListState([], 20, 'ordersPage')
  const clientsList = useUrlListState([], 20, 'clientsPage')
  const isAdmin = hasAuthority('ADMIN_ALL')
  const path = `/api/customers/${customerId}`
  const customerQuery = useQuery({ queryKey: queryKeys.customers.detail(customerId), queryFn: ({ signal }) => apiGet<CustomerDetail>(path, signal), enabled: Boolean(customerId) })
  const ordersPath = `${path}/orders?page=${page}&size=${pageSize}`
  const ordersQuery = useQuery({ queryKey: queryKeys.customers.orders(customerId, ordersPath), queryFn: ({ signal }) => apiGet<ApiPage<CustomerOrder>>(ordersPath, signal), enabled: Boolean(customerQuery.data) && !customerQuery.isError })
  const reseller = customerQuery.data?.kind === 'RESELLER'
  const clientsPath = `/api/customers?page=${clientsList.page}&size=${clientsList.pageSize}&status=&resellerId=${encodeURIComponent(customerId)}`
  const clientsQuery = useQuery({ queryKey: queryKeys.customers.associated(customerId, clientsPath), queryFn: ({ signal }) => apiGet<ApiPage<AssociatedCustomer>>(clientsPath, signal), enabled: reseller && isAdmin })
  const backAction = <Button variant="secondary" href={back}>Volver a clientes</Button>

  if (customerQuery.isPending) return <>
    <PageHeader title="Cliente" actions={backAction} />
    <Panel><EmptyState state="loading" title="Cargando cliente" description="Consultando los datos del cliente." /></Panel>
  </>
  if (customerQuery.isError) {
    const unavailable = customerQuery.error instanceof ApiError && customerQuery.error.status === 404
    return <>
      <PageHeader title="Cliente" actions={backAction} />
      <Panel><EmptyState state="error" title={unavailable ? 'Cliente no disponible' : 'No se pudo cargar el cliente'}
        description={unavailable ? 'El cliente no existe o no tenés acceso a sus datos.' : 'No se pudieron consultar los datos. Podés reintentar.'}
        action={!unavailable && <Button variant="secondary" onClick={() => customerQuery.refetch()}>Reintentar datos</Button>} /></Panel>
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
    { key: 'balance', label: 'Por cobrar', align: 'right', render: (value, row) => <span className={Number(row.pendingBalance) > 0 ? 'font-bold text-danger' : 'text-muted'}>{value}</span> },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'CANCELLED' ? 'muted' : value === 'DELIVERED' ? 'strong' : 'soft'}>{orderStatusLabel(value)}</Badge> },
    { key: 'action', label: 'Acciones', render: (_value, row) => <div className="table-row-actions"><Button variant="link" href={`/orders/${row.id}`} aria-label={`Ver pedido ${row.number}`}>Ver pedido</Button></div> },
  ]
  const rows = (ordersQuery.data?.content ?? []).map((order) => ({ id: order.id, number: order.number, date: date(order.date), seller: order.seller, total: money(order.total), balance: order.balance === undefined ? '—' : order.balance > 0 ? money(order.balance) : 'Sin deuda', pendingBalance: String(order.balance ?? ''), status: order.status }))
  const balance = Number(customer.balance ?? 0)
  const clientColumns: TableColumn[] = [
    { key: 'name', label: 'Cliente', emphasis: true, render: (value, row) => <div className="min-w-0"><span>{value}</span>{row.number && <span className="block text-[11px] font-normal text-muted">Cliente {row.number}</span>}</div> },
    { key: 'balance', label: 'Saldo propio', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'ACTIVE' ? 'strong' : 'muted'}>{value === 'ACTIVE' ? 'Activo' : 'Inactivo'}</Badge> },
    { key: 'action', label: 'Acciones', render: (_value, row) => <div className="table-row-actions"><Button variant="link" href={`/customers/${row.id}`} aria-label={`Ver cliente ${row.name}`}>Ver cliente</Button></div> },
  ]
  const clientRows = (clientsQuery.data?.content ?? []).map((client) => ({ id: client.id, name: client.name, number: client.number ?? '', balance: money(Number(client.balance ?? 0)), status: client.status }))
  const purchasePath = `/customers/${encodeURIComponent(customerId)}/purchases${params.size ? `?${new URLSearchParams({ customerFilters: params.toString() })}` : ''}`
  return <>
    <PageHeader title={customer.name} description="Datos del cliente e historial de pedidos." actions={<>
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
        {clientsQuery.isPending ? <EmptyState state="loading" title="Cargando clientes asociados" description="Consultando los clientes del revendedor." />
          : clientsQuery.isError ? <EmptyState state="error" title="No se pudieron cargar los clientes asociados" description="Los datos del revendedor siguen disponibles." action={<Button variant="secondary" onClick={() => clientsQuery.refetch()}>Reintentar clientes</Button>} />
          : clientRows.length === 0 ? <EmptyState title="Sin clientes asociados" description="Asigná clientes desde el listado con «Asignar revendedor»." />
          : <DataTable columns={clientColumns} rows={clientRows} />}
        {clientsQuery.isSuccess && (clientsQuery.data.totalElements > 0 || clientsList.page > 0) && <Pagination label="Paginación de clientes asociados" summary={<>Página {clientsList.page + 1} · {clientsQuery.data.totalElements} clientes</>} onPrevious={() => clientsList.setPage(clientsList.page - 1)} onNext={() => clientsList.setPage(clientsList.page + 1)} previousDisabled={clientsList.page === 0} nextDisabled={clientsList.page + 1 >= clientsQuery.data.totalPages} />}
      </Panel>}
      <Panel title="Historial de pedidos" description="Pedidos del cliente, del más reciente al más antiguo. El saldo pendiente muestra la deuda actual de cada pedido en cuenta corriente.">
        {ordersQuery.isPending ? <EmptyState state="loading" title="Cargando pedidos" description="Consultando el historial del cliente." />
          : ordersQuery.isError ? <EmptyState state="error" title="No se pudo cargar el historial" description="Los datos del cliente siguen disponibles. Podés reintentar la consulta." action={<Button variant="secondary" onClick={() => ordersQuery.refetch()}>Reintentar historial</Button>} />
          : rows.length === 0 ? <EmptyState title="No hay pedidos para mostrar" description="Este cliente no tiene pedidos visibles para tu usuario en esta página." />
          : <DataTable columns={columns} rows={rows} />}
        {ordersQuery.isSuccess && (ordersQuery.data.totalElements > 0 || page > 0) && <Pagination label="Paginación del historial" summary={<>Página {page + 1} · {ordersQuery.data.totalElements} pedidos</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= ordersQuery.data.totalPages} />}
      </Panel>
    </div>
  </>
}
