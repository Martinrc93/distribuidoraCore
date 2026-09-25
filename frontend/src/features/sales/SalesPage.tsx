import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type Sale = { id: string; number: string; customer: string; total: number; paid: number; balance: number; status: string; date: string }
type Row = Record<string, string>
const money = (value: unknown) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
const formatDate = (value: string) => new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium' }).format(new Date(value))
const stateName: Record<string, string> = { CONFIRMED: 'Confirmada', DELIVERED: 'Entregada', CANCELLED: 'Cancelada', RETURNED: 'Con devoluciones' }

export default function SalesPage() {
  const [search, setSearch] = useState('')
  const path = `/api/sales?page=0&size=20&search=${encodeURIComponent(search.trim())}`
  const query = useQuery({ queryKey: [path], queryFn: () => apiGet<ApiPage<Sale>>(path) })
  const rows: Row[] = (query.data?.content ?? []).map((sale) => ({
    id: sale.id,
    number: sale.number,
    customer: sale.customer,
    date: formatDate(sale.date),
    total: money(sale.total),
    paid: money(sale.paid),
    balance: money(sale.balance),
    status: sale.status,
  }))
  const columns: TableColumn[] = [
    { key: 'number', label: 'Venta', emphasis: true },
    { key: 'customer', label: 'Cliente' },
    { key: 'date', label: 'Fecha' },
    { key: 'total', label: 'Total', align: 'right' },
    { key: 'paid', label: 'Cobrado', align: 'right' },
    { key: 'balance', label: 'Saldo', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'DELIVERED' ? 'strong' : value === 'CANCELLED' ? 'muted' : 'soft'}>{stateName[value] ?? value}</Badge> },
  ]

  return <>
    <PageHeader eyebrow="Operación" title="Ventas" description="Consultá importes cobrados, saldos y estado de las ventas." />
    <Panel>
      <div className="toolbar"><input className="input search-input" placeholder="Buscar por cliente o número de venta..." aria-label="Buscar ventas" value={search} onChange={(event) => setSearch(event.target.value)} /></div>
      {query.isLoading ? <EmptyState title="Cargando ventas" description="Consultando ventas registradas." /> : query.isError ? <EmptyState title="No se pudieron cargar las ventas" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="No hay ventas para mostrar" description="Probá otra búsqueda." /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Mostrando hasta 20 de {query.data?.totalElements ?? 0} ventas</span><div><Button variant="secondary" disabled>Anterior</Button><Button variant="secondary" disabled={(query.data?.totalElements ?? 0) <= 20}>Siguiente</Button></div></div></>}
    </Panel>
    <p className="helper-text">Los productos, precios y pagos de una venta se consultan desde el detalle del pedido correspondiente.</p>
  </>
}
