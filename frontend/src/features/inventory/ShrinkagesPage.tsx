import { Pagination } from '../../shared/components/Pagination'
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../shared/api/queryKeys'
import { useSearchParams } from 'react-router-dom'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { formatBusinessDateTime, parseDate } from '../../shared/dates'
import { formatNumber } from '../../shared/format'
import { OrderDateFilter } from '../orders/OrderDateFilter'

type Entry = { id: string; product: string; date: string; quantity: number; reason: string }
type Report = { totalQuantity: number; movements: ApiPage<Entry> }

const units = { format: (value: number) => formatNumber(value, 2) }
const columns: TableColumn[] = [
  { key: 'date', label: 'Día y horario' },
  { key: 'product', label: 'Producto', emphasis: true, render: (value) => <span className="[overflow-wrap:anywhere]">{value}</span> },
  { key: 'quantity', label: 'Cantidad perdida', align: 'right' },
  { key: 'reason', label: 'Motivo', render: (value) => <span className="[overflow-wrap:anywhere]">{value}</span> },
]

export default function ShrinkagesPage() {
  const [params, setParams] = useSearchParams()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['dateMin', 'dateMax'])
  const dateMin = getFilter('dateMin')
  const dateMax = getFilter('dateMax')
  const from = parseDate(dateMin)
  const to = parseDate(dateMax)
  const inverted = Boolean(from && to && from > to)
  const invalidStart = Boolean(dateMin && !from)
  const invalidEnd = Boolean(dateMax && !to)
  const dateError = invalidStart || invalidEnd ? 'Ingresá fechas válidas con formato dd/mm/aaaa.'
    : inverted ? 'La fecha inicial no puede ser posterior a la final.' : ''
  const queryParams = new URLSearchParams({ page: String(page), size: String(pageSize) })
  if (from) queryParams.set('dateMin', from)
  if (to) queryParams.set('dateMax', to)
  const path = `/api/inventory/shrinkages?${queryParams}`
  const report = useQuery({
    queryKey: queryKeys.inventory.shrinkages(path), queryFn: ({ signal }) => apiGet<Report>(path, signal), enabled: !dateError, retry: false,
  })
  const totalMovements = report.data?.movements.totalElements ?? 0
  const stockSearch = params.get('stockSearch') ?? ''
  const backToStock = stockSearch.startsWith('?') ? `/products${stockSearch}` : '/products'

  function clearDates() {
    const next = new URLSearchParams(params)
    next.delete('dateMin')
    next.delete('dateMax')
    next.delete('page')
    setParams(next, { replace: true })
  }

  return <>
    <PageHeader title="Mermas" actions={<Button variant="secondary" href={backToStock}>Volver a Stock</Button>} />
    <Panel title="Mermas registradas">
      <div className="toolbar items-end">
        <div className="orders-date-filters ml-0">
          <OrderDateFilter id="shrinkages-from" label="Desde" value={dateMin} isoValue={from ?? ''} invalid={inverted || invalidStart}
            describedBy={dateError ? 'shrinkages-date-error' : undefined} onChange={(value) => setFilter('dateMin', value)} />
          <OrderDateFilter id="shrinkages-to" label="Hasta" value={dateMax} isoValue={to ?? ''} invalid={inverted || invalidEnd}
            describedBy={dateError ? 'shrinkages-date-error' : undefined} onChange={(value) => setFilter('dateMax', value)} />
        </div>
        <Button type="button" variant="secondary" disabled={!dateMin && !dateMax} onClick={clearDates}>Todas las fechas</Button>
      </div>
      {dateError && <p id="shrinkages-date-error" className="error-text" role="alert">{dateError}</p>}
      {!dateError && report.isLoading && <EmptyState state="loading" title="Cargando mermas" description="Consultando movimientos de stock." />}
      {!dateError && report.isError && <EmptyState state="error" title="No se pudo cargar el reporte" description={report.error.message}
        action={<Button variant="secondary" onClick={() => void report.refetch()}>Reintentar</Button>} />}
      {!dateError && !report.isError && report.data && <>
        <p className="helper-text mb-[14px]">Cantidad perdida en el período: <strong>{units.format(report.data.totalQuantity)}</strong></p>
        <DataTable columns={columns} rows={report.data.movements.content.map((entry) => ({
          id: entry.id, product: entry.product, date: formatBusinessDateTime(entry.date),
          quantity: units.format(entry.quantity), reason: entry.reason,
        }))} emptyContent={<p className="table-empty-message">No hay mermas en el período seleccionado.</p>} />
        <Pagination summary={<>Página {page + 1} · {totalMovements} {totalMovements === 1 ? 'merma' : 'mermas'}</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= report.data.movements.totalPages} />
      </>}
    </Panel>
  </>
}
