import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { apiGet } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { StatCard } from '../../shared/components/StatCard'
import { OrderDateFilter } from '../orders/OrderDateFilter'
import { parseDashboardData } from './dashboardResponses'

type Metrics = {
  performedOrders: number
  deliveredOrders: number
  totalBilled: number
  totalPaid: number
  accountBalance: number
}

export type DashboardData = {
  dateMin: string
  dateMax: string
  totals: Metrics
  bySeller: Array<Metrics & { sellerId: string | null; seller: string }>
}

function parseDate(value: string) {
  const match = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value)
  if (!match) return null
  const [, day, month, year] = match
  const iso = `${year}-${month}-${day}`
  const date = new Date(`${iso}T00:00:00Z`)
  return Number(year) > 0 && !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === iso ? iso : null
}

function money(value: number) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)
}

const columns: TableColumn[] = [
  { key: 'seller', label: 'Vendedor', emphasis: true },
  { key: 'performedOrders', label: 'Pedidos realizados', align: 'right' },
  { key: 'deliveredOrders', label: 'Pedidos entregados', align: 'right' },
  { key: 'totalBilled', label: 'Total facturado', align: 'right' },
  { key: 'totalPaid', label: 'Total pagado', align: 'right' },
  { key: 'accountBalance', label: 'Saldo en cuenta corriente', align: 'right' },
]

export default function DashboardPage() {
  const [params, setParams] = useSearchParams()
  const [today] = useState(() => new Intl.DateTimeFormat('es-AR', {
    day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'America/Argentina/Buenos_Aires',
  }).format(new Date()))
  const dateMin = params.get('dateMin') ?? today
  const dateMax = params.get('dateMax') ?? today
  const min = parseDate(dateMin)
  const max = parseDate(dateMax)
  const reversed = Boolean(min && max && min > max)
  const dateError = !min || !max ? 'Completá ambas fechas con formato dd/mm/aaaa.'
    : reversed ? 'La fecha desde no puede ser posterior a la fecha hasta.' : ''
  const path = `/api/dashboard?dateMin=${min ?? ''}&dateMax=${max ?? ''}`
  const query = useQuery({ queryKey: ['dashboard', min, max], queryFn: async ({ signal }) => parseDashboardData(await apiGet<unknown>(path, signal)), enabled: !dateError })

  function setDate(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.set(key, value)
    setParams(next, { replace: true })
  }

  function showToday() {
    const next = new URLSearchParams(params)
    next.set('dateMin', today)
    next.set('dateMax', today)
    setParams(next, { replace: true })
  }

  const data = query.data
  const rows = (data?.bySeller ?? []).map((seller) => ({
    id: seller.sellerId ?? 'unassigned', seller: seller.seller,
    performedOrders: String(seller.performedOrders), deliveredOrders: String(seller.deliveredOrders),
    totalBilled: money(seller.totalBilled), totalPaid: money(seller.totalPaid), accountBalance: money(seller.accountBalance),
  }))

  return <>
    <PageHeader eyebrow="Operación" title="Resumen operativo" description="Totales del período y detalle por vendedor." />
    <Panel title="Período" description="Se toman los pedidos creados entre Desde y Hasta, incluyendo ambos días.">
      <form className="toolbar orders-toolbar items-end mb-0" onSubmit={(event) => event.preventDefault()}>
        <Button type="button" variant="secondary" onClick={showToday}>Hoy</Button>
        <div className="orders-date-filters">
          <OrderDateFilter id="dashboard-date-min" label="Desde" value={dateMin} isoValue={min ?? ''} onChange={(value) => setDate('dateMin', value)} invalid={!min || reversed} describedBy={dateError ? 'dashboard-date-error' : undefined} />
          <OrderDateFilter id="dashboard-date-max" label="Hasta" value={dateMax} isoValue={max ?? ''} onChange={(value) => setDate('dateMax', value)} invalid={!max || reversed} describedBy={dateError ? 'dashboard-date-error' : undefined} />
        </div>
      </form>
      {dateError && <p id="dashboard-date-error" className="error-text" role="alert">{dateError}</p>}
    </Panel>
    {!dateError && (query.isLoading ? <div role="status"><EmptyState title="Cargando resumen" description="Consultando pedidos y sus saldos." /></div>
      : query.isError ? <EmptyState title="No se pudo cargar el resumen" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
      : data && <>
        <section className="stats-grid compact mt-[20px]" aria-label="Totales del período">
          <StatCard label="Pedidos realizados" value={String(data.totals.performedOrders)} detail="Incluye pedidos cancelados" />
          <StatCard label="Pedidos entregados" value={String(data.totals.deliveredOrders)} detail="Del conjunto de pedidos del período" />
          <StatCard label="Total facturado" value={money(data.totals.totalBilled)} detail="Importe de ventas no canceladas" />
          <StatCard label="Total pagado" value={money(data.totals.totalPaid)} detail="Cobros acumulados de esas ventas" />
          <StatCard label="Saldo en cuenta corriente" value={money(data.totals.accountBalance)} detail="Saldo pendiente actual de esas ventas" />
        </section>
        <Panel title="Detalle por vendedor" description="Entregas, cobros y saldos reflejan el estado actual de los pedidos del período. No incluye deuda de otros períodos ni saldo anterior agregado al remito.">
          <DataTable columns={columns} rows={rows} emptyContent={<p className="table-empty-message">No hay pedidos en el período seleccionado.</p>} />
        </Panel>
      </>)}
  </>
}
