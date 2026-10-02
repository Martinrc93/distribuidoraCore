import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { apiGet } from '../../shared/api/client'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { StatCard } from '../../shared/components/StatCard'
import { OrderDateFilter } from '../orders/OrderDateFilter'
import { businessTimeZone, displayDate, parseDate, presetRange, rangeError, todayInArgentina } from './dashboardDates'
import { parseDashboardReport } from './dashboardResponses'

type Sales = { amount: number; orders: number }
type Collections = { amount: number; cash: number; transfer: number }
type Order = { id: string; number: string; customer: string; seller: string; total: number; status: string; date: string }
type TrendPoint = { date: string; amount: number; orders: number }
export type DashboardReport = {
  dateMin: string; dateMax: string; previousDateMin: string; previousDateMax: string; generatedAt: string
  sales: Sales; previousSales: Sales; collections: Collections; previousCollections: Collections
  current: { debt: number; debtorCount: number; pendingOrders: number; pendingAmount: number; oldestPendingAt: string | null; failedDeliveries: number; stockAlertCount: number }
  trend: TrendPoint[]
  topDebtors: Array<{ id: string; name: string; balance: number }>
  debtAging: { days0to30: number; days31to60: number; days61to90: number; daysOver90: number }
  stockAlerts: Array<{ id: string; name: string; stock: number }>
  stockCoverage: Array<{ id: string; name: string; stock: number; dailyUnits: number; days: number | null }>
  topProducts: Array<{ id: string; name: string; units: number; amount: number }>
  sellers: Array<{ id: string | null; name: string; orders: number; amount: number }>
  pendingOrders: Order[]; recentOrders: Order[]
}

type Row = Record<string, string>
const money = (value: number) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)
const number = (value: number) => new Intl.NumberFormat('es-AR', { maximumFractionDigits: 1 }).format(value)
const date = (value: string) => new Intl.DateTimeFormat('es-AR', { timeZone: businessTimeZone, day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(value))
const stateNames: Record<string, string> = { CONFIRMED: 'Confirmado', DELIVERED: 'Entregado', CANCELLED: 'Cancelado' }
export function comparison(current: number, previous: number) {
  if (previous === 0) return current === 0 ? 'Sin variación' : 'Sin base de comparación'
  const change = (current - previous) / previous * 100
  return `${change > 0 ? '+' : ''}${number(change)}% respecto del período anterior`
}
const orderColumns: TableColumn[] = [
  { key: 'number', label: 'Pedido', emphasis: true }, { key: 'customer', label: 'Cliente' }, { key: 'seller', label: 'Vendedor' },
  { key: 'date', label: 'Fecha' }, { key: 'total', label: 'Total', align: 'right' },
  { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'DELIVERED' ? 'strong' : value === 'CANCELLED' ? 'muted' : 'soft'}>{stateNames[value] ?? value}</Badge> },
  { key: 'action', label: 'Detalle', render: (_, row) => <Button variant="link" href={`/orders/${row.id}`} aria-label={`Abrir pedido ${row.number}`}>Ver detalle</Button> },
]
const orderRows = (orders: Order[]): Row[] => orders.map((order) => ({ ...order, total: money(order.total), date: date(order.date) }))
const empty = (message: string) => <p className="m-0 py-[18px] text-[13px] text-muted">{message}</p>
const compactTable = '[&>.data-table]:min-w-0 [&>.data-table]:table-fixed [&_th]:whitespace-normal [&_td]:whitespace-normal [&_td]:[overflow-wrap:anywhere] [@media(min-width:641px)]:[&_th]:px-[10px] [@media(min-width:641px)]:[&_td]:px-[10px]'

function SalesTrend({ points }: { points: TrendPoint[] }) {
  if (!points.some((point) => Number(point.amount) > 0)) return empty('No hay ventas registradas en este período.')
  const max = Math.max(...points.map((point) => Number(point.amount)), 1)
  const coordinates = points.map((point, index) => ({ x: points.length === 1 ? 400 : index / (points.length - 1) * 792 + 4, y: 172 - Number(point.amount) / max * 160 }))
  const path = coordinates.map((point, index) => `${index ? 'L' : 'M'}${point.x},${point.y}`).join(' ')
  const tickIndexes = [...new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])]
  return <figure className="m-0 min-w-0">
    <div className="mb-[8px] flex justify-between text-[11px] text-muted"><span>Importe diario</span><span>Máximo {money(max)}</span></div>
    <svg className="block h-[180px] w-full" viewBox="0 0 800 180" preserveAspectRatio="none" role="img" aria-label={`Ventas diarias del ${displayDate(points[0].date)} al ${displayDate(points.at(-1)!.date)}. Máximo diario ${money(max)}.`}>
      {[12, 92, 172].map((y) => <line key={y} x1="0" x2="800" y1={y} y2={y} className="stroke-line" vectorEffect="non-scaling-stroke" />)}
      <path d={path} fill="none" className="stroke-accent" strokeWidth="2.5" vectorEffect="non-scaling-stroke" />
      {coordinates.length === 1 && <circle cx={coordinates[0].x} cy={coordinates[0].y} r="3" className="fill-accent" />}
    </svg>
    <div className="mt-[8px] flex justify-between gap-[8px] text-[11px] text-muted">{tickIndexes.map((index) => <span key={index}>{displayDate(points[index].date).slice(0, 5)}</span>)}</div>
    <figcaption className="mt-[12px] text-[12px] text-muted">Los días sin ventas se muestran en cero.</figcaption>
    <details className="mt-[12px] text-[12px]"><summary className="cursor-pointer rounded-[4px] py-[8px] focus-visible:outline-[3px] focus-visible:outline-[#bb8754] focus-visible:outline-offset-2">Ver datos del gráfico</summary>
      <DataTable className={compactTable} columns={[{ key: 'date', label: 'Fecha' }, { key: 'orders', label: 'Ventas', align: 'right' }, { key: 'amount', label: 'Importe', align: 'right' }]} rows={points.map((point) => ({ date: displayDate(point.date), orders: number(point.orders), amount: money(point.amount) }))} />
    </details>
  </figure>
}

export default function AnalyticsDashboardPage() {
  const [params, setParams] = useSearchParams()
  const today = todayInArgentina()
  const defaultRange = presetRange('month', today)
  const dateMin = params.get('dateMin') ?? displayDate(defaultRange.start)
  const dateMax = params.get('dateMax') ?? displayDate(defaultRange.end)
  const start = parseDate(dateMin)
  const end = parseDate(dateMax)
  const error = rangeError(start, end, today)
  const path = `/api/dashboard/analytics?dateMin=${start ?? ''}&dateMax=${end ?? ''}`
  const query = useQuery({ queryKey: ['dashboard', 'analytics', start, end], queryFn: async ({ signal }) => parseDashboardReport(await apiGet<unknown>(path, signal)), enabled: !error })
  const data = query.data
  function changeDate(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.set(key, value)
    next.set('period', 'custom')
    setParams(next, { replace: true })
  }
  function changePreset(value: string) {
    const next = new URLSearchParams(params)
    if (value === 'custom') next.set('period', 'custom')
    else {
      const range = presetRange(value, today)
      next.set('dateMin', displayDate(range.start))
      next.set('dateMax', displayDate(range.end))
      next.delete('period')
    }
    setParams(next, { replace: true })
  }
  const preset = params.get('period') === 'custom' ? 'custom' : ['month', 'week', 'today'].find((value) => {
    const range = presetRange(value, today)
    return range.start === start && range.end === end
  }) ?? 'custom'
  return <div className="grid min-w-0 gap-[20px] [&>.page-header]:mb-0">
    <PageHeader eyebrow="Operación" title="Dashboard" description="Ventas, cobros y prioridades de la distribuidora." actions={<Button type="button" variant="secondary" disabled={Boolean(error) || query.isFetching} onClick={() => void query.refetch()}>{query.isFetching ? 'Actualizando…' : 'Actualizar datos'}</Button>} />
    <Panel title="Período de análisis" description="El período se aplica a ventas, cobros, rankings y pedidos recientes.">
      <div className="toolbar mb-0 items-end [&>.field]:min-w-0 [@media(max-width:640px)]:grid [@media(max-width:640px)]:grid-cols-1">
        <label className="field"><span id="analytics-period-label">Período</span><select className="select" aria-labelledby="analytics-period-label" value={preset} onChange={(event) => changePreset(event.target.value)}><option value="today">Hoy</option><option value="week">Esta semana</option><option value="month">Este mes</option><option value="custom">Personalizado</option></select></label>
        <OrderDateFilter id="analytics-date-min" label="Desde" value={dateMin} isoValue={start ?? ''} invalid={Boolean(error)} describedBy={error ? 'analytics-date-error' : undefined} onChange={(value) => changeDate('dateMin', value)} />
        <OrderDateFilter id="analytics-date-max" label="Hasta" value={dateMax} isoValue={end ?? ''} invalid={Boolean(error)} describedBy={error ? 'analytics-date-error' : undefined} onChange={(value) => changeDate('dateMax', value)} />
      </div>
      {error && <p id="analytics-date-error" className="error-text" role="alert">{error}</p>}
    </Panel>
    {!error && query.isLoading && <div role="status"><EmptyState title="Cargando dashboard" description="Consultando indicadores y actividad comercial." /></div>}
    {!error && query.isError && <div role="alert"><EmptyState title="No se pudo cargar el dashboard" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()} disabled={query.isFetching}>Reintentar</Button>} /></div>}
    {!error && !query.isError && data && <>
      <p className="m-0 text-[12px] text-muted">Comparación: {displayDate(data.previousDateMin)} al {displayDate(data.previousDateMax)}{end === today ? ', hasta la misma hora del último día' : ''}. Actualizado {new Intl.DateTimeFormat('es-AR', { timeZone: businessTimeZone, dateStyle: 'short', timeStyle: 'short' }).format(new Date(data.generatedAt))}.</p>
      <section className="stats-grid mb-0" aria-label="Indicadores principales">
        <StatCard label="Ventas del período" value={money(data.sales.amount)} detail={`${number(data.sales.orders)} ventas · ${comparison(data.sales.amount, data.previousSales.amount)}`} />
        <StatCard label="Cobros del período" value={money(data.collections.amount)} detail={comparison(data.collections.amount, data.previousCollections.amount)} />
        <StatCard label="Deuda pendiente actual" value={money(data.current.debt)} detail={`${number(data.current.debtorCount)} clientes con deuda`} />
        <StatCard label="Pedidos por entregar" value={number(data.current.pendingOrders)} detail={`${money(data.current.pendingAmount)} pendientes actualmente`} />
      </section>
      <div className="content-grid two-thirds">
        <Panel title="Evolución de ventas" description={`Del ${dateMin} al ${dateMax}. Ventas confirmadas y entregadas, después de descuentos.`} action={<Button variant="link" href={`/sales?dateMin=${encodeURIComponent(dateMin)}&dateMax=${encodeURIComponent(dateMax)}`}>Ver ventas</Button>}>
          <SalesTrend points={data.trend} />
          <p className="mb-0 mt-[16px] text-[12px] text-muted">Ticket promedio: {money(data.sales.orders ? data.sales.amount / data.sales.orders : 0)}. Se excluyen cancelaciones. Las devoluciones de mercadería todavía no generan ajustes financieros.</p>
        </Panel>
        <Panel title="Cobros y pendientes" description="Ingresos del período y prioridades actuales.">
          <dl className="m-0 grid gap-[14px] text-[13px]">{[['Efectivo', money(data.collections.cash)], ['Transferencias', money(data.collections.transfer)], ['Productos sin stock o con saldo negativo', number(data.current.stockAlertCount)], ['Pedido pendiente más antiguo', data.current.oldestPendingAt ? date(data.current.oldestPendingAt) : 'Sin pendientes']].map(([label, value]) => <div className="flex flex-wrap justify-between gap-[8px] border-b border-line pb-[12px]" key={label}><dt className="max-w-[230px] text-muted">{label}</dt><dd className="m-0 font-bold">{value}</dd></div>)}</dl>
          <div className="mt-[16px] flex flex-wrap gap-[8px]"><Button variant="secondary" href="/payments">Ver pagos y deuda</Button><Button variant="link" href="/orders?status=CONFIRMED&dateMin=&dateMax=">Ver pendientes</Button></div>
        </Panel>
      </div>
      <div className="grid min-w-0 grid-cols-2 gap-[20px] [@media(max-width:760px)]:grid-cols-1">
        <Panel title="Clientes con mayor deuda" description="Los cinco saldos actuales más altos." action={<Button variant="link" href="/customers?hasBalance=true&status=ALL">Ver clientes</Button>}>
          <DataTable className={compactTable} columns={[{ key: 'name', label: 'Cliente', emphasis: true }, { key: 'balance', label: 'Deuda', align: 'right' }, { key: 'action', label: 'Cuenta', render: (_, row) => <Button variant="link" href={`/sales?customerId=${row.id}&pendingBalance=true`} aria-label={`Ver deuda de ${row.name}`}>Ver deuda</Button> }]} rows={data.topDebtors.map((item) => ({ id: item.id, name: item.name, balance: money(item.balance) }))} emptyContent={empty('No hay clientes con deuda pendiente.')} />
        </Panel>
        <Panel title="Antigüedad de deuda" description="Saldo abierto por fecha de venta, a la fecha actual. No indica vencimiento.">
          <DataTable className={compactTable} columns={[{ key: 'age', label: 'Antigüedad' }, { key: 'amount', label: 'Saldo', align: 'right' }]} rows={[
            { age: 'Hasta 30 días', amount: money(data.debtAging.days0to30) }, { age: '31 a 60 días', amount: money(data.debtAging.days31to60) },
            { age: '61 a 90 días', amount: money(data.debtAging.days61to90) }, { age: 'Más de 90 días', amount: money(data.debtAging.daysOver90) },
          ]} />
        </Panel>
        <Panel title="Alertas de stock" description={`${number(data.current.stockAlertCount)} productos activos requieren atención. Se muestran hasta ocho, priorizando saldos negativos.`} action={<Button variant="link" href="/products">Ver productos</Button>}>
          <DataTable className={compactTable} columns={[{ key: 'name', label: 'Producto', emphasis: true }, { key: 'stock', label: 'Stock', align: 'right' }, { key: 'state', label: 'Situación', render: (value) => <span className="font-bold text-danger">{value}</span> }]} rows={data.stockAlerts.map((item) => ({ id: item.id, name: item.name, stock: number(item.stock), state: item.stock < 0 ? 'Stock negativo' : 'Sin stock' }))} emptyContent={empty('No hay productos activos sin stock ni con stock negativo.')} />
        </Panel>
        <Panel title="Cobertura estimada de stock" description="Ocho productos priorizados por menor cobertura. Promedio de unidades netas vendidas en los últimos 30 días completos.">
          <DataTable className={compactTable} columns={[{ key: 'name', label: 'Producto', emphasis: true }, { key: 'stock', label: 'Stock', align: 'right' }, { key: 'daily', label: 'Unidades/día', align: 'right' }, { key: 'days', label: 'Cobertura', align: 'right' }]} rows={data.stockCoverage.map((item) => ({ id: item.id, name: item.name, stock: number(item.stock), daily: new Intl.NumberFormat('es-AR', { maximumFractionDigits: 2 }).format(item.dailyUnits), days: item.days === null ? 'Sin ventas' : `${number(item.days)} días` }))} emptyContent={empty('No hay productos activos para estimar cobertura.')} />
        </Panel>
        <Panel title="Productos más vendidos" description="Los cinco productos con más unidades vendidas en el período. Importes después de descuentos; no descuenta devoluciones de mercadería.">
          <DataTable className={compactTable} columns={[{ key: 'name', label: 'Producto', emphasis: true }, { key: 'units', label: 'Unidades', align: 'right' }, { key: 'amount', label: 'Importe', align: 'right' }]} rows={data.topProducts.map((item) => ({ id: item.id, name: item.name, units: number(item.units), amount: money(item.amount) }))} emptyContent={empty('No hay productos vendidos en este período.')} />
        </Panel>
        <Panel title="Ventas por vendedor" description="Los cinco mayores importes del período, según el vendedor del pedido.">
          <DataTable className={compactTable} columns={[{ key: 'name', label: 'Vendedor', emphasis: true }, { key: 'orders', label: 'Ventas', align: 'right' }, { key: 'amount', label: 'Importe', align: 'right' }]} rows={data.sellers.map((item) => ({ id: item.id ?? 'unassigned', name: item.name, orders: number(item.orders), amount: money(item.amount) }))} emptyContent={empty('No hay ventas en este período.')} />
        </Panel>
      </div>
      <Panel title="Entregas pendientes más antiguas" description="Hasta cinco pedidos pendientes actualmente, sin limitar por el período." action={<Button variant="link" href="/orders?status=CONFIRMED&dateMin=&dateMax=">Ver todos</Button>}>
        <DataTable columns={orderColumns} rows={orderRows(data.pendingOrders)} emptyContent={empty('No hay pedidos pendientes de entrega.')} />
      </Panel>
      <Panel title="Pedidos recientes" description="Últimos cinco pedidos creados en el período seleccionado." action={<Button variant="link" href={`/orders?dateMin=${encodeURIComponent(dateMin)}&dateMax=${encodeURIComponent(dateMax)}`}>Ver todos</Button>}>
        <DataTable columns={orderColumns} rows={orderRows(data.recentOrders)} emptyContent={empty('No hay pedidos en este período.')} />
      </Panel>
    </>}
  </div>
}
