import { Pagination } from '../../shared/components/Pagination'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { queryKeys } from '../../shared/api/queryKeys'
import { formatBusinessDateTime } from '../../shared/dates'
import { formatMoney, formatNumber } from '../../shared/format'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { displayDate, parseDate, presetRange, rangeError, todayInArgentina } from '../../shared/dates'
import { OrderDateFilter } from '../orders/OrderDateFilter'

type Metrics = { soldUnits: number; returnedUnits: number; netUnits: number; amount: number }
type ProductTotals = Metrics & { productId: string; product: string; salesCount: number }
type SaleEntry = Metrics & { id: string; orderId: string; number: string; customer: string; status: string; date: string }
type History = { product: ProductTotals | null; sales: ApiPage<SaleEntry> }

const number = { format: (value: number) => formatNumber(value, 4) }
const money = { format: (value: number) => formatMoney(value, 4) }
const statusLabel: Record<string, string> = { CONFIRMED: 'Confirmada', DELIVERED: 'Entregada' }
const metrics = [
  { key: 'soldUnits', label: 'Vendidas', align: 'right' },
  { key: 'returnedUnits', label: 'Devueltas', align: 'right' },
  { key: 'netUnits', label: 'Unidades netas', align: 'right' },
  { key: 'amount', label: 'Importe vendido', align: 'right' },
] as const satisfies readonly TableColumn[]

function formatMetrics(row: Metrics) {
  return {
    soldUnits: number.format(row.soldUnits), returnedUnits: number.format(row.returnedUnits),
    netUnits: number.format(row.netUnits), amount: money.format(row.amount),
  }
}

export default function SoldProductsPage() {
  const { productId } = useParams()
  const [params, setParams] = useSearchParams()
  const [today] = useState(todayInArgentina)
  const { page, pageSize, setPage } = useUrlListState([], 20, productId ? 'historyPage' : 'page')
  const month = presetRange('month', today)
  const dateMin = params.get('dateMin') ?? displayDate(month.start)
  const dateMax = params.get('dateMax') ?? displayDate(month.end)
  const search = params.get('search') ?? ''
  const from = parseDate(dateMin)
  const to = parseDate(dateMax)
  const error = rangeError(from, to, today)
  const queryParams = new URLSearchParams({ page: String(page), size: String(pageSize), dateMin: from ?? '', dateMax: to ?? '' })
  const productQuery = useQuery({
    queryKey: queryKeys.sales.soldProducts(queryParams.toString(), search.trim()),
    queryFn: ({ signal }) => apiGet<ApiPage<ProductTotals>>(`/api/reports/sold-products?${queryParams}&search=${encodeURIComponent(search.trim())}`, signal),
    enabled: !productId && !error, retry: false,
  })
  const historyQuery = useQuery({
    queryKey: queryKeys.sales.soldProductHistory(productId, queryParams.toString()),
    queryFn: ({ signal }) => apiGet<History>(`/api/reports/sold-products/${encodeURIComponent(productId ?? '')}/sales?${queryParams}`, signal),
    enabled: Boolean(productId) && !error, retry: false,
  })
  const query = productId ? historyQuery : productQuery
  const data = productId ? historyQuery.data?.sales : productQuery.data
  const selected = historyQuery.data?.product
  const selectedMetrics = selected && formatMetrics(selected)

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.set(key, value)
    next.delete('page')
    next.delete('historyPage')
    setParams(next, { replace: true })
  }

  const rawReturnTo = params.get('returnTo') ?? ''
  const safeReturnTo = (rawReturnTo === '/sales' || rawReturnTo.startsWith('/sales?') || rawReturnTo === '/analytics' || rawReturnTo.startsWith('/analytics?'))
    ? rawReturnTo : ''
  const backHref = safeReturnTo || '/sales'
  const backLabel = safeReturnTo.startsWith('/analytics') ? 'Volver al dashboard' : 'Volver a ventas'
  const returnQuery = safeReturnTo.includes('?') ? new URLSearchParams(safeReturnTo.split('?')[1]) : null
  const hasListOnlyFilters = Boolean(!productId && returnQuery && ['search', 'customerId', 'sellerId', 'accountCustomerId', 'pendingBalance'].some((key) => Boolean(returnQuery.get(key))))
  const summaryParams = new URLSearchParams(params)
  summaryParams.delete('historyPage')
  const summarySuffix = summaryParams.size ? `?${summaryParams}` : ''
  const productColumns: TableColumn[] = [
    { key: 'product', label: 'Artículo', emphasis: true, render: (value) => <span className="[overflow-wrap:anywhere]">{value}</span> },
    ...metrics,
    { key: 'actions', label: 'Historial', render: (_, row) => <Button variant="link"
      href={`/sales/products/${encodeURIComponent(row.id)}${summarySuffix}`} aria-label={`Ver ventas de ${row.product}`}>Ver ventas</Button> },
  ]
  const saleColumns: TableColumn[] = [
    { key: 'number', label: 'Venta', emphasis: true, render: (value, row) => <Button variant="link" href={`/orders/${encodeURIComponent(row.orderId)}`} aria-label={`Abrir pedido de la venta ${value}`}>{value}</Button> },
    { key: 'date', label: 'Fecha' },
    { key: 'customer', label: 'Cliente', render: (value) => <span className="[overflow-wrap:anywhere]">{value}</span> },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'DELIVERED' ? 'strong' : 'soft'}>{statusLabel[value] ?? value}</Badge> },
    ...metrics,
    { key: 'actions', label: 'Pedido', render: (_, row) => <Button variant="link"
      href={`/orders/${encodeURIComponent(row.orderId)}`} aria-label={`Ver pedido de la venta ${row.number}`}>Ver pedido</Button> },
  ]

  return <>
    <div className="[&_.page-header>div:first-child]:min-w-0 [&_h1]:[overflow-wrap:anywhere]">
      <PageHeader title={productId ? selected?.product ?? 'Historial por artículo' : 'Artículos vendidos'}
        actions={<Button variant="secondary" href={productId ? `/sales/products${summarySuffix}` : backHref}>
          {productId ? 'Volver a artículos vendidos' : backLabel}
        </Button>} />
    </div>
    <Panel description="El período se aplica a la fecha de venta. Las devoluciones ajustan las unidades. El importe vendido incluye los descuentos registrados.">
      <div className="toolbar items-end">
        {!productId && <label className="field min-w-0 [@media(max-width:640px)]:w-full">
          <span>Buscar artículo</span>
          <input className="input search-input [@media(max-width:640px)]:max-w-none" placeholder="Nombre del artículo"
            value={search} maxLength={200} onChange={(event) => setFilter('search', event.target.value)} />
        </label>}
        <div className={productId ? 'orders-date-filters ml-0' : 'orders-date-filters'}>
          <OrderDateFilter id="sold-products-from" label="Desde" value={dateMin} isoValue={from ?? ''} invalid={Boolean(error)}
            describedBy={error ? 'sold-products-date-error' : undefined} onChange={(value) => setFilter('dateMin', value)} />
          <OrderDateFilter id="sold-products-to" label="Hasta" value={dateMax} isoValue={to ?? ''} invalid={Boolean(error)}
            describedBy={error ? 'sold-products-date-error' : undefined} onChange={(value) => setFilter('dateMax', value)} />
        </div>
      </div>
      {hasListOnlyFilters && <p className="helper-text mt-0" role="status">Este reporte totaliza todas las ventas visibles del período; no aplica los filtros de búsqueda, cliente, vendedor o saldo del listado anterior.</p>}
      {error && <p className="error-text" id="sold-products-date-error" role="alert">{error}</p>}
      {!error && query.isLoading && <EmptyState state="loading" title="Cargando historial" description="Consultando las ventas del período." />}
      {!error && query.isError && <EmptyState state="error" title="No se pudo cargar el historial" description={query.error.message}
          action={<Button variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />}
      {!error && !query.isError && data && <>
        {selectedMetrics && <dl className="mb-[18px] mt-0 flex flex-wrap gap-[14px_22px] text-[13px]">
          {metrics.map((metric) => <div key={metric.key}><dt className="text-muted">{metric.label}</dt>
            <dd className="m-0 mt-[4px] font-bold">{selectedMetrics[metric.key]}</dd></div>)}
        </dl>}
        {productId ? <DataTable className="compact-list-table" columns={saleColumns} rows={(historyQuery.data?.sales.content ?? []).map((row) => ({
          id: row.id, orderId: row.orderId, number: row.number, date: formatBusinessDateTime(row.date),
          customer: row.customer, status: row.status, ...formatMetrics(row),
        }))} emptyContent={<p className="table-empty-message">No hay ventas visibles para este artículo y período.</p>} />
          : <DataTable className="compact-list-table" columns={productColumns} rows={(productQuery.data?.content ?? []).map((row) => ({
            id: row.productId, product: row.product, ...formatMetrics(row),
          }))} emptyContent={<p className="table-empty-message">No hay artículos vendidos con estos filtros.</p>} />}
        <Pagination summary={<>Página {page + 1} · {data.totalElements} {productId ? (data.totalElements === 1 ? 'venta' : 'ventas') : (data.totalElements === 1 ? 'artículo' : 'artículos')}</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= data.totalPages} />
      </>}
    </Panel>
  </>
}
