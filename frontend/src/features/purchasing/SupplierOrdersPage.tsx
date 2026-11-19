import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { Button } from '../../shared/components/Button'
import { DataTable } from '../../shared/components/DataTable'
import { SupplierSelect } from '../../shared/components/EntitySelect'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { displayDate, parseDate } from '../dashboard/dashboardDates'
import { OrderDateFilter } from '../orders/OrderDateFilter'
import { Badge } from '../../shared/components/Badge'
import { money, purchaseStatusLabel, purchaseStatusTone, supplierOrdersKey, suppliersOptionsKey, type Supplier, type SupplierOrder, type SupplierOrderResult } from './supplierOrders'

export default function SupplierOrdersPage() {
  const [params, setParams] = useSearchParams()
  const { search, state } = useLocation()
  const saved = (state as { savedPurchase?: SupplierOrderResult } | null)?.savedPurchase
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['supplierId', 'dateMin', 'dateMax'])
  const supplierId = getFilter('supplierId')
  const dateMin = getFilter('dateMin')
  const dateMax = getFilter('dateMax')
  const from = dateMin ? parseDate(dateMin) : null
  const to = dateMax ? parseDate(dateMax) : null
  const dateError = (dateMin && !from) || (dateMax && !to) ? 'Ingresa fechas válidas con formato dd/mm/aaaa.' : from && to && from > to ? 'La fecha inicial no puede ser posterior a la final.' : ''
  const queryParams = new URLSearchParams({ page: String(page), size: String(pageSize) })
  if (supplierId) queryParams.set('supplierId', supplierId)
  if (from) queryParams.set('dateMin', from)
  if (to) queryParams.set('dateMax', to)
  const path = `/api/supplier-orders?${queryParams}`
  const orders = useQuery({ queryKey: [...supplierOrdersKey, path], queryFn: ({ signal }) => apiGet<ApiPage<SupplierOrder>>(path, signal), enabled: !dateError, retry: false })
  const suppliers = useQuery({ queryKey: suppliersOptionsKey, queryFn: () => apiGetAllPages<Supplier>('/api/suppliers?page=0&size=100'), retry: false })
  function clearDates() {
    const next = new URLSearchParams(params)
    next.delete('dateMin'); next.delete('dateMax'); next.delete('page')
    setParams(next, { replace: true })
  }

  return <>
    <PageHeader eyebrow="Operación" title="Compras" description="Solicitudes de compra e historial por proveedor y fecha." actions={<Link className="button button-primary" to={`/supplier-orders/new${search}`}>+ Nueva compra</Link>} />
    {saved && <p className="success-text" role="status">Compra {saved.number} {saved.status === 'CONFIRMED' ? 'confirmada: las cantidades se sumaron al stock.' : 'guardada como borrador.'} <Button variant="link" href={`/supplier-orders/${encodeURIComponent(saved.id)}${search}`}>Ver compra registrada</Button></p>}
    <Panel title="Compras realizadas" description="Sin fechas seleccionadas se muestra todo el historial. Los importes corresponden a la solicitud registrada.">
      <div className="toolbar items-end">
        <SupplierSelect options={suppliers.data?.content ?? []} value={supplierId} onChange={(id) => setFilter('supplierId', id)} loading={suppliers.isLoading} disabled={suppliers.isError} />
        <div className="orders-date-filters">
          <OrderDateFilter id="supplier-orders-from" label="Desde" value={dateMin} isoValue={from ?? ''} invalid={Boolean(dateMin && !from)} describedBy={dateError ? 'supplier-orders-date-error' : undefined} onChange={(value) => setFilter('dateMin', value)} />
          <OrderDateFilter id="supplier-orders-to" label="Hasta" value={dateMax} isoValue={to ?? ''} invalid={Boolean(dateMax && !to)} describedBy={dateError ? 'supplier-orders-date-error' : undefined} onChange={(value) => setFilter('dateMax', value)} />
        </div>
        <Button type="button" variant="secondary" disabled={!dateMin && !dateMax} onClick={clearDates}>Todas las fechas</Button>
      </div>
      {suppliers.isError && <p className="error-text" role="alert">No se pudieron cargar los proveedores del filtro. <Button type="button" variant="link" onClick={() => void suppliers.refetch()}>Reintentar proveedores</Button></p>}
      {dateError ? <p id="supplier-orders-date-error" className="error-text" role="alert">{dateError}</p>
        : orders.isLoading ? <div role="status"><EmptyState title="Cargando compras" description="Consultando las solicitudes registradas." /></div>
          : orders.isError ? <div role="alert"><EmptyState title="No se pudieron cargar las compras" description={orders.error.message} action={<Button variant="secondary" onClick={() => void orders.refetch()}>Reintentar compras</Button>} /></div>
            : <DataTable columns={[
              { key: 'number', label: 'Compra', emphasis: true }, { key: 'supplier', label: 'Proveedor' }, { key: 'date', label: 'Fecha' },
              { key: 'total', label: 'Total estimado', align: 'right' },
              { key: 'status', label: 'Estado', render: (value) => value ? <Badge tone={purchaseStatusTone[value as keyof typeof purchaseStatusTone] ?? 'muted'}>{purchaseStatusLabel[value as keyof typeof purchaseStatusLabel] ?? value}</Badge> : null },
              { key: 'action', label: 'Detalle', render: (_, row) => <Button variant="link" href={`/supplier-orders/${row.id}${search}`} aria-label={`Ver compra ${row.number}`}>Ver compra</Button> },
            ]} rows={(orders.data?.content ?? []).map((order) => ({ ...order, date: displayDate(order.date), total: money(order.total), status: order.status ?? '' }))}
              emptyContent={<p className="table-empty-message">No hay compras para los filtros seleccionados.</p>} />}
      {!dateError && orders.data && !orders.isError && <div className="pagination"><span>Página {page + 1} de {Math.max(1, orders.data.totalPages)} · {orders.data.totalElements} compras</span><div><Button type="button" variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Anterior</Button><Button type="button" variant="secondary" disabled={page + 1 >= orders.data.totalPages} onClick={() => setPage(page + 1)}>Siguiente</Button></div></div>}
    </Panel>
  </>
}
