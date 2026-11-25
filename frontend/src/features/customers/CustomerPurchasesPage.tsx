import { Pagination } from '../../shared/components/Pagination'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../shared/api/queryKeys'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet, ApiError, type ApiPage } from '../../shared/api/client'
import { hasPermission } from '../../shared/auth/permissions'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { DocumentActions } from '../../shared/components/DocumentActions'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { displayDate, formatBusinessDateMedium, parseDate, presetRange, rangeError, todayInArgentina } from '../../shared/dates'
import { formatMoney, formatNumber } from '../../shared/format'
import { OrderDateFilter } from '../orders/OrderDateFilter'

type Item = { id: string; product: string; soldUnits: number; returnedUnits: number; netUnits: number; amount: number }
type Purchase = {
  id: string; orderId: string; saleNumber: string; orderNumber: string; date: string
  status: 'CONFIRMED' | 'DELIVERED'; amount: number; items: Item[]
}
type Report = {
  summary: { customerId: string; customer: string; dateMin: string; dateMax: string; salesCount: number; totalAmount: number }
  purchases: ApiPage<Purchase>
}

const money = { format: (value: number) => formatMoney(value, 4) }
const units = { format: (value: number) => formatNumber(value, 4) }
const itemColumns: TableColumn[] = [
  { key: 'product', label: 'Producto', emphasis: true, render: (value) => <span className="[overflow-wrap:anywhere]">{value}</span> },
  { key: 'sold', label: 'Vendidas', align: 'right' },
  { key: 'returned', label: 'Devueltas', align: 'right' },
  { key: 'net', label: 'Unidades netas', align: 'right' },
  { key: 'amount', label: 'Importe', align: 'right' },
]

function PurchaseDetails({ purchase }: { purchase: Purchase }) {
  const delivered = purchase.status === 'DELIVERED'
  return <details className="min-w-0 rounded-[5px] border border-line">
    <summary className="cursor-pointer rounded-[5px] p-[12px] text-[13px] leading-[1.7] [overflow-wrap:anywhere] focus-visible:outline-[3px] focus-visible:outline-[#bb8754]">
      <strong className="mr-[8px]">Pedido {purchase.orderNumber}</strong>
      <span className="mr-[8px]">Venta {purchase.saleNumber}</span>
      <span className="mr-[8px] whitespace-nowrap text-muted">{formatBusinessDateMedium(purchase.date)}</span>
      <strong className="mr-[8px]">{money.format(purchase.amount)}</strong>
      <Badge tone={delivered ? 'strong' : 'soft'}>{delivered ? 'Entregada' : 'Confirmada'}</Badge>
    </summary>
    <div className="grid gap-[8px] px-[12px] pb-[12px]">
      <DataTable columns={itemColumns} rows={purchase.items.map((item) => ({
        id: item.id, product: item.product, sold: units.format(item.soldUnits),
        returned: units.format(item.returnedUnits), net: units.format(item.netUnits), amount: money.format(item.amount),
      }))} emptyContent={<p className="table-empty-message">La venta no tiene líneas registradas.</p>} />
      <div className="page-actions">
        <Button variant="link" href={`/orders/${encodeURIComponent(purchase.orderId)}`}>Ver pedido {purchase.orderNumber}</Button>
      </div>
    </div>
  </details>
}

export default function CustomerPurchasesPage() {
  const { customerId = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const [today] = useState(todayInArgentina)
  const [feedback, setFeedback] = useState<{ path: string; message: string }>()
  const { page, pageSize, setPage } = useUrlListState([], 20)
  const month = presetRange('month', today)
  const dateMin = params.get('dateMin') ?? displayDate(month.start)
  const dateMax = params.get('dateMax') ?? displayDate(month.end)
  const from = parseDate(dateMin)
  const to = parseDate(dateMax)
  const error = rangeError(from, to, today)
  const id = encodeURIComponent(customerId)
  const dateParams = new URLSearchParams({ dateMin: from ?? '', dateMax: to ?? '' })
  const path = `/api/reports/customers/${id}/purchases?${dateParams}&page=${page}&size=${pageSize}`
  const documentPath = `/api/documents/customers/${id}/purchases?${dateParams}`
  const report = useQuery({
    queryKey: queryKeys.customers.purchases(path), queryFn: ({ signal }) => apiGet<Report>(path, signal),
    enabled: Boolean(customerId) && !error, retry: false,
  })
  const customerFilters = new URLSearchParams(params.get('customerFilters') ?? '')
  const canOpenCustomer = hasPermission('CUSTOMER_VIEW')
  const back = canOpenCustomer ? `/customers/${id}${customerFilters.size ? `?${customerFilters}` : ''}` : '/customers'
  const summary = report.data?.summary
  const purchases = report.data?.purchases
  const unavailable = report.error instanceof ApiError && report.error.status === 404

  function setDate(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.set(key, value)
    next.delete('page')
    setFeedback(undefined)
    setParams(next, { replace: true })
  }

  return <>
    <div className="[&_.page-header>div:first-child]:min-w-0 [&_.page-header_p]:[overflow-wrap:anywhere]">
      <PageHeader eyebrow="Clientes" title="Compras del cliente" description={summary?.customer}
        actions={<Button variant="secondary" href={back}>{canOpenCustomer ? 'Volver a la ficha' : 'Volver a clientes'}</Button>} />
    </div>
    <div className="content-grid">
      <Panel title="Período">
        <div className="toolbar mb-0 items-end">
          <div className="orders-date-filters ml-0">
            <OrderDateFilter id="customer-purchases-from" label="Desde" value={dateMin} isoValue={from ?? ''} invalid={Boolean(error)}
              describedBy={error ? 'customer-purchases-date-error' : undefined} onChange={(value) => setDate('dateMin', value)} />
            <OrderDateFilter id="customer-purchases-to" label="Hasta" value={dateMax} isoValue={to ?? ''} invalid={Boolean(error)}
              describedBy={error ? 'customer-purchases-date-error' : undefined} onChange={(value) => setDate('dateMax', value)} />
          </div>
        </div>
        {error && <p id="customer-purchases-date-error" className="error-text mt-[12px] mb-0" role="alert">{error}</p>}
      </Panel>
      {!error && report.isLoading && <Panel><EmptyState state="loading" title="Cargando compras" description="Consultando las ventas del cliente." /></Panel>}
      {!error && report.isError && <Panel>
        <EmptyState state="error" title={unavailable ? 'Cliente no disponible' : 'No se pudo cargar el reporte'}
          description={unavailable ? 'El cliente no existe o está fuera de tu alcance.' : report.error.message}
          action={!unavailable && <Button variant="secondary" onClick={() => void report.refetch()}>Reintentar</Button>} />
      </Panel>}
      {!error && !report.isError && summary && purchases && <>
        <Panel title="Total comprado">
          <p className="m-0 text-[25px] font-bold">{money.format(summary.totalAmount)}</p>
          <p className="helper-text mt-[4px] mb-[14px]">
            {summary.salesCount} {summary.salesCount === 1 ? 'venta' : 'ventas'} en el período. Importe con descuentos.
          </p>
          <DocumentActions key={documentPath} path={documentPath}
            filename={`compras-${from}-${to}.pdf`} printLabel="Imprimir compras" downloadLabel="Descargar PDF"
            onDone={(message) => setFeedback({ path: documentPath, message })} />
          {feedback?.path === documentPath && <p className="success-text mt-[12px] mb-0" role="status">{feedback.message}</p>}
        </Panel>
        <Panel title="Detalle por pedido"
          description="Abrí un pedido para ver sus productos. Las devoluciones se muestran en unidades.">
          {purchases.content.length ? <div className="grid gap-[8px]">
            {purchases.content.map((sale) => <PurchaseDetails key={sale.id} purchase={sale} />)}
          </div> : <EmptyState title="No hay compras para mostrar"
            description={summary.salesCount === 0 ? 'Este cliente no tiene ventas visibles en el período seleccionado.' : 'Esta página no contiene ventas.'} />}
          {(purchases.totalElements > 0 || page > 0) && <Pagination label="Paginación de compras" summary={<>Página {page + 1} · {purchases.totalElements} {purchases.totalElements === 1 ? 'venta' : 'ventas'}</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= purchases.totalPages} />}
        </Panel>
      </>}
    </div>
  </>
}
