import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../shared/api/queryKeys'
import { apiGet } from '../../shared/api/client'
import { displayDate, parseDate, todayInArgentina } from '../../shared/dates'
import { formatMoney } from '../../shared/format'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { OrderDateFilter } from '../orders/OrderDateFilter'
import { parseDashboardData } from './dashboardResponses'
import { SellerOrdersDialog, type SellerOrderSelection } from './SellerOrdersDialog'
import { PendingOrderReviews } from './PendingOrderReviews'
import { AttentionNow } from './AttentionNow'

type Metrics = {
  performedOrders: number
  deliveredOrders: number
  totalBilled: number
  totalPaid: number
  cashPaid: number
  transferPaid: number
  accountBalance: number
}

export type DashboardData = {
  dateMin: string
  dateMax: string
  totals: Metrics
  bySeller: Array<Metrics & { sellerId: string | null; seller: string }>
}

/** A figure of the period strip. */
function Metric({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return <div className="metric"><dt>{label}</dt><dd className="metric-value">{value}</dd>{detail && <dd className="metric-detail">{detail}</dd>}</div>
}

function money(value: number) {
  return formatMoney(value, 2)
}

const columns: TableColumn[] = [
  { key: 'seller', label: 'Vendedor', emphasis: true },
  { key: 'performedOrders', label: 'Pedidos realizados', align: 'right' },
  { key: 'deliveredOrders', label: 'Pedidos entregados', align: 'right' },
  { key: 'totalBilled', label: 'Total facturado', align: 'right' },
  { key: 'totalPaid', label: 'Total pagado', align: 'right' },
  { key: 'cashPaid', label: 'Pagado en efectivo', align: 'right' },
  { key: 'transferPaid', label: 'Pagado en transferencia', align: 'right' },
  { key: 'accountBalance', label: 'Por cobrar', align: 'right' },
]

export default function DashboardPage() {
  const [params, setParams] = useSearchParams()
  const [selectedSeller, setSelectedSeller] = useState<SellerOrderSelection | null>(null)
  const [today] = useState(() => displayDate(todayInArgentina()))
  const dateMin = params.get('dateMin') ?? today
  const dateMax = params.get('dateMax') ?? today
  const min = parseDate(dateMin)
  const max = parseDate(dateMax)
  const reversed = Boolean(min && max && min > max)
  const dateError = !min || !max ? 'Completá ambas fechas con formato dd/mm/aaaa.'
    : reversed ? 'La fecha desde no puede ser posterior a la fecha hasta.' : ''
  const path = `/api/dashboard?dateMin=${min ?? ''}&dateMax=${max ?? ''}`
  const query = useQuery({ queryKey: queryKeys.dashboard.summary(min, max), queryFn: async ({ signal }) => parseDashboardData(await apiGet<unknown>(path, signal)), enabled: !dateError })

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
    totalBilled: money(seller.totalBilled), totalPaid: money(seller.totalPaid), cashPaid: money(seller.cashPaid),
    transferPaid: money(seller.transferPaid), accountBalance: money(seller.accountBalance),
  }))
  const sellerColumns: TableColumn[] = [...columns, {
    key: 'action', label: 'Detalle', render: (_value, row) => <div className="table-row-actions"><Button type="button" variant="secondary" aria-label={`Ver pedidos de ${row.seller}`} onClick={() => {
      const seller = data?.bySeller.find((item) => (item.sellerId ?? 'unassigned') === row.id)
      if (seller && min && max) setSelectedSeller({ sellerId: seller.sellerId, seller: seller.seller, dateMin: min, dateMax: max })
    }}>Ver</Button></div>,
  }]

  return <>
    <PageHeader title="Resumen operativo" description="Pedidos creados entre Desde y Hasta, incluidos ambos días." actions={
      <form className="dashboard-period" aria-label="Período" onSubmit={(event) => event.preventDefault()}>
        <div className="orders-date-filters">
          <OrderDateFilter id="dashboard-date-min" label="Desde" value={dateMin} isoValue={min ?? ''} onChange={(value) => setDate('dateMin', value)} invalid={!min || reversed} describedBy={dateError ? 'dashboard-date-error' : undefined} />
          <OrderDateFilter id="dashboard-date-max" label="Hasta" value={dateMax} isoValue={max ?? ''} onChange={(value) => setDate('dateMax', value)} invalid={!max || reversed} describedBy={dateError ? 'dashboard-date-error' : undefined} />
        </div>
        <Button type="button" variant="secondary" onClick={showToday}>Hoy</Button>
      </form>
    } />
    {dateError && <p id="dashboard-date-error" className="error-text mb-[16px]" role="alert">{dateError}</p>}
    <AttentionNow />
    <PendingOrderReviews hideWhenEmpty />
    {!dateError && (query.isLoading ? <EmptyState state="loading" title="Cargando resumen" description="Consultando pedidos y sus saldos." />
      : query.isError ? <EmptyState state="error" title="No se pudo cargar el resumen" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
      : data && <>
        <section className="metric-strip" aria-label="Totales del período">
          <div className="metric-group">
            <h2>Pedidos</h2>
            <dl>
              <Metric label="Pedidos realizados" value={String(data.totals.performedOrders)} detail="Incluye cancelados" />
              <Metric label="Pedidos entregados" value={String(data.totals.deliveredOrders)} detail="Del período" />
            </dl>
          </div>
          <div className="metric-group metric-group-wide">
            <h2>Facturación y cobros</h2>
            <dl>
              <Metric label="Total facturado" value={money(data.totals.totalBilled)} detail="Ventas no canceladas" />
              <Metric label="Total pagado" value={money(data.totals.totalPaid)} detail={`Efectivo ${money(data.totals.cashPaid)} · Transferencia ${money(data.totals.transferPaid)}`} />
              <Metric label="Por cobrar en cuenta corriente" value={money(data.totals.accountBalance)} detail="Saldo de esas ventas" />
            </dl>
          </div>
        </section>
        <Panel title="Detalle por vendedor" description="Entregas, cobros y saldos reflejan el estado actual de los pedidos del período. No incluye deuda de otros períodos ni saldo anterior agregado al remito.">
          <DataTable className="compact-list-table [&_th]:whitespace-normal" columns={sellerColumns} rows={rows} emptyContent={<p className="table-empty-message">No hay pedidos en el período seleccionado.</p>} />
        </Panel>
      </>)}
    {selectedSeller && !dateError && selectedSeller.dateMin === min && selectedSeller.dateMax === max && <SellerOrdersDialog selection={selectedSeller} onClose={() => setSelectedSeller(null)} />}
  </>
}
