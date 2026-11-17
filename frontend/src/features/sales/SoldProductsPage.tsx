import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { businessTimeZone, displayDate, parseDate, presetRange, rangeError, todayInArgentina } from '../dashboard/dashboardDates'
import { OrderDateFilter } from '../orders/OrderDateFilter'

type Metrics = { soldUnits: number; returnedUnits: number; netUnits: number; amount: number }
type ProductTotals = Metrics & { productId: string; product: string; salesCount: number }
type SaleEntry = Metrics & { id: string; orderId: string; number: string; customer: string; status: string; date: string }
type History = { product: ProductTotals | null; sales: ApiPage<SaleEntry> }

const number = new Intl.NumberFormat('es-AR', { maximumFractionDigits: 4 })
const money = new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 4 })
const dateTime = new Intl.DateTimeFormat('es-AR', { timeZone: businessTimeZone, dateStyle: 'medium', timeStyle: 'short' })
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
    queryKey: ['sold-products', 'products', queryParams.toString(), search.trim()],
    queryFn: ({ signal }) => apiGet<ApiPage<ProductTotals>>(`/api/reports/sold-products?${queryParams}&search=${encodeURIComponent(search.trim())}`, signal),
    enabled: !productId && !error, retry: false,
  })
  const historyQuery = useQuery({
    queryKey: ['sold-products', 'history', productId, queryParams.toString()],
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
    { key: 'number', label: 'Venta', emphasis: true },
    { key: 'date', label: 'Fecha' },
    { key: 'customer', label: 'Cliente', render: (value) => <span className="[overflow-wrap:anywhere]">{value}</span> },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'DELIVERED' ? 'strong' : 'soft'}>{statusLabel[value] ?? value}</Badge> },
    ...metrics,
    { key: 'actions', label: 'Pedido', render: (_, row) => <Button variant="link"
      href={`/orders/${encodeURIComponent(row.orderId)}`} aria-label={`Ver pedido de la venta ${row.number}`}>Ver pedido</Button> },
  ]

  return <>
    <div className="[&_.page-header>div:first-child]:min-w-0 [&_h1]:[overflow-wrap:anywhere]">
      <PageHeader eyebrow="Ventas" title={productId ? selected?.product ?? 'Historial por artículo' : 'Artículos vendidos'}
        actions={<Button variant="secondary" href={productId ? `/sales/products${summarySuffix}` : '/sales'}>
          {productId ? 'Volver a artículos vendidos' : 'Volver a ventas'}
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
      {error && <p className="error-text" id="sold-products-date-error" role="alert">{error}</p>}
      {!error && query.isLoading && <div role="status"><EmptyState title="Cargando historial" description="Consultando las ventas del período." /></div>}
      {!error && query.isError && <div role="alert">
        <EmptyState title="No se pudo cargar el historial" description={query.error.message}
          action={<Button variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
      </div>}
      {!error && !query.isError && data && <>
        {selectedMetrics && <dl className="mb-[18px] mt-0 flex flex-wrap gap-[14px_22px] text-[13px]">
          {metrics.map((metric) => <div key={metric.key}><dt className="text-muted">{metric.label}</dt>
            <dd className="m-0 mt-[4px] font-bold">{selectedMetrics[metric.key]}</dd></div>)}
        </dl>}
        {productId ? <DataTable columns={saleColumns} rows={(historyQuery.data?.sales.content ?? []).map((row) => ({
          id: row.id, orderId: row.orderId, number: row.number, date: dateTime.format(new Date(row.date)),
          customer: row.customer, status: row.status, ...formatMetrics(row),
        }))} emptyContent={<p className="table-empty-message">No hay ventas visibles para este artículo y período.</p>} />
          : <DataTable columns={productColumns} rows={(productQuery.data?.content ?? []).map((row) => ({
            id: row.productId, product: row.product, ...formatMetrics(row),
          }))} emptyContent={<p className="table-empty-message">No hay artículos vendidos con estos filtros.</p>} />}
        <div className="pagination">
          <span>Página {page + 1} · {data.totalElements} {productId ? (data.totalElements === 1 ? 'venta' : 'ventas') : (data.totalElements === 1 ? 'artículo' : 'artículos')}</span>
          <div>
            <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Anterior</Button>
            <Button variant="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Siguiente</Button>
          </div>
        </div>
      </>}
    </Panel>
  </>
}
