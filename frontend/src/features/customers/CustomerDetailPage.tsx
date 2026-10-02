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
type CustomerOrder = { id: string; number: string; seller: string; total: number; status: string; date: string }
const money = (value: number = 0) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)
const date = (value?: string) => value ? new Intl.DateTimeFormat('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'America/Argentina/Buenos_Aires' }).format(new Date(value)) : '—'
const orderStatus: Record<string, string> = { CONFIRMED: 'Confirmado', DELIVERED: 'Entregado', CANCELLED: 'Cancelado' }

export default function CustomerDetailPage() {
  const { customerId = '' } = useParams()
  const [params] = useSearchParams()
  const returnTo = params.get('returnTo') ?? ''
  const back = returnTo === '/customers' || returnTo.startsWith('/customers?') ? returnTo : '/customers'
  const { page, pageSize, setPage } = useUrlListState([], 20, 'ordersPage')
  const path = `/api/customers/${customerId}`
  const customerQuery = useQuery({ queryKey: [path], queryFn: () => apiGet<CustomerDetail>(path), enabled: Boolean(customerId) })
  const ordersPath = `${path}/orders?page=${page}&size=${pageSize}`
  const ordersQuery = useQuery({ queryKey: [ordersPath], queryFn: () => apiGet<ApiPage<CustomerOrder>>(ordersPath), enabled: Boolean(customerQuery.data) && !customerQuery.isError })
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
    ['Razón social', customer.name], ['CUIT', customer.cuitId], ['Mail', customer.email],
    ['Celular', customer.phone], ['Dirección', customer.address], ['Zona', customer.zone],
    ['Vendedor asignado', customer.seller || 'Sin asignar'],
    ['Lista de precios predeterminada', customer.priceList ? `${customer.priceListCode} - ${customer.priceList}` : 'Sin lista asignada'],
    ['Fecha de alta', date(customer.createdAt)],
  ]
  const columns: TableColumn[] = [
    { key: 'number', label: 'Pedido', emphasis: true },
    { key: 'date', label: 'Fecha' },
    { key: 'seller', label: 'Vendedor' },
    { key: 'total', label: 'Total', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'CANCELLED' ? 'muted' : value === 'DELIVERED' ? 'strong' : 'soft'}>{orderStatus[value] ?? value}</Badge> },
    { key: 'action', label: '', render: (_value, row) => <div className="table-row-actions"><Button variant="link" href={`/orders/${row.id}`} aria-label={`Ver pedido ${row.number}`}>Ver pedido</Button></div> },
  ]
  const rows = (ordersQuery.data?.content ?? []).map((order) => ({ id: order.id, number: order.number, date: date(order.date), seller: order.seller, total: money(order.total), status: order.status }))
  const balance = Number(customer.balance ?? 0)
  return <>
    <PageHeader eyebrow="Operación" title={customer.name} description="Datos del cliente e historial de pedidos." actions={backAction} />
    <div className="content-grid">
      <div className="content-grid two-thirds">
        <Panel title="Datos del cliente" action={<Badge tone={customer.status === 'ACTIVE' ? 'strong' : 'muted'}>{customer.status === 'ACTIVE' ? 'Activo' : 'Inactivo'}</Badge>}>
          <dl className="customer-detail-fields">{details.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || '—'}</dd></div>)}</dl>
        </Panel>
        <div className="self-start"><StatCard label="Saldo de cuenta corriente" value={money(balance)} detail={balance > 0 ? 'Saldo deudor actual del cliente.' : balance < 0 ? 'Saldo a favor del cliente.' : 'El cliente no tiene saldo pendiente.'} /></div>
      </div>
      <Panel title="Historial de pedidos" description="Pedidos del cliente, del más reciente al más antiguo.">
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
