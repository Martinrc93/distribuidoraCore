import { useQuery } from '@tanstack/react-query'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'

type Order = { id: string; number: string; customer: string; seller: string; total: number; status: string; date: string }
type Row = Record<string, string>

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
}

function formatDate(value: string) {
  return value ? new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium' }).format(new Date(value)) : '—'
}

export default function OrdersPage() {
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['search', 'status'])
  const search = getFilter('search')
  const status = getFilter('status')
  const path = `/api/orders?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}&status=${encodeURIComponent(status)}`
  const query = useQuery({ queryKey: [path], queryFn: () => apiGet<ApiPage<Order>>(path) })
  const rows: Row[] = (query.data?.content ?? []).map((order) => ({
    id: order.id,
    number: order.number,
    customer: order.customer,
    seller: order.seller,
    total: money(order.total),
    status: order.status,
    date: formatDate(order.date),
    action: order.number,
  }))
  const columns: TableColumn[] = [
    { key: 'number', label: 'Pedido', emphasis: true },
    { key: 'customer', label: 'Cliente' },
    { key: 'seller', label: 'Vendedor' },
    { key: 'date', label: 'Fecha' },
    { key: 'total', label: 'Total', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'CANCELLED' ? 'muted' : value === 'DELIVERED' ? 'strong' : 'soft'}>{value}</Badge> },
    { key: 'action', label: '', render: (_value, row) => <Button variant="link" href={`/orders/${row.id}`} aria-label={`Abrir pedido ${row.number}`}>Ver detalle</Button> },
  ]

  return <>
    <PageHeader eyebrow="Operación" title="Pedidos" description="Consultá pedidos, cobros, entregas y snapshots comerciales." actions={<Button href="/orders/new">+ Nuevo pedido</Button>} />
    <Panel>
      <form className="toolbar" onSubmit={(event) => event.preventDefault()}>
        <label className="field"><span>Buscar pedidos</span><input className="input search-input" placeholder="Cliente o número" aria-label="Buscar pedidos" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
        <label className="field"><span>Filtrar por estado</span><select className="select" aria-label="Filtrar por estado" value={status} onChange={(event) => setFilter('status', event.target.value)}><option value="">Todos los estados</option><option value="CONFIRMED">Confirmado</option><option value="DELIVERED">Entregado</option><option value="CANCELLED">Cancelado</option></select></label>
      </form>
      {query.isLoading ? <EmptyState title="Cargando pedidos" description="Consultando pedidos y ventas." /> : query.isError ? <EmptyState title="No se pudieron cargar los pedidos" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="No hay pedidos para mostrar" description="Probá otra búsqueda o creá un pedido." action={<Button href="/orders/new">+ Nuevo pedido</Button>} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} pedidos</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
  </>
}
