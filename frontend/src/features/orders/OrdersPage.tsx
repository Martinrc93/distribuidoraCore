import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { OrderDateFilter } from './OrderDateFilter'
import { DeliveryAttemptDialog } from './DeliveryAttemptDialog'
import { useOrderDelivery } from './useOrderDelivery'

type Order = { id: string; number: string; customer: string; seller: string; total: number; status: string; date: string }
type Row = Record<string, string>
type DeliveryOrder = { id: string; number: string; saleBalance: number; previousDebtAvailable: number }

function money(value: unknown) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
}

function formatDate(value: string) {
  return value ? new Intl.DateTimeFormat('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'America/Argentina/Buenos_Aires' }).format(new Date(value)) : '—'
}

function parseDate(value: string) {
  if (!value) return ''
  const match = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value)
  if (!match) return null
  const [, day, month, year] = match
  const iso = `${year}-${month}-${day}`
  const date = new Date(`${iso}T00:00:00Z`)
  return Number(year) > 0 && !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === iso ? iso : null
}

export default function OrdersPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canDeliver = isAdmin || hasAuthority('SALE_DELIVER')
  const [deliveryOrder, setDeliveryOrder] = useState<DeliveryOrder>()
  const [loadingDeliveryId, setLoadingDeliveryId] = useState('')
  const [deliveryLoadError, setDeliveryLoadError] = useState('')
  const [deliveryFeedback, setDeliveryFeedback] = useState('')
  const [deliveryFocusTarget, setDeliveryFocusTarget] = useState('')
  const delivery = useOrderDelivery({ orderId: deliveryOrder?.id ?? '', saleBalance: deliveryOrder?.saleBalance ?? 0, previousDebtAvailable: deliveryOrder?.previousDebtAvailable ?? 0, onSaved: () => {
    const detailLinkId = `order-details-${deliveryOrder?.id}`
    setDeliveryFeedback(`Entrega del pedido ${deliveryOrder?.number} registrada.`)
    setDeliveryOrder(undefined)
    setDeliveryFocusTarget(detailLinkId)
  } })
  useEffect(() => {
    if (deliveryOrder || !deliveryFocusTarget) return
    const target = document.getElementById(deliveryFocusTarget) ?? document.getElementById('orders-search')
    target?.focus()
    setDeliveryFocusTarget('')
  }, [deliveryOrder, deliveryFocusTarget])
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['search', 'status'])
  const search = getFilter('search')
  const status = getFilter('status')
  const [params, setParams] = useSearchParams()
  const [today] = useState(() => formatDate(new Date().toISOString()))
  const dateMin = params.get('dateMin') ?? today
  const dateMax = params.get('dateMax') ?? today
  const min = parseDate(dateMin)
  const max = parseDate(dateMax)
  const invalidRange = Boolean(min && max && min > max)
  const dateError = min === null || max === null ? 'Las fechas deben ser válidas y tener formato dd/mm/aaaa.' : invalidRange ? 'La fecha mínima no puede ser posterior a la fecha máxima.' : ''
  const path = `/api/orders?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}&status=${encodeURIComponent(status)}&dateMin=${min ?? ''}&dateMax=${max ?? ''}`
  const query = useQuery({ queryKey: [path], queryFn: () => apiGet<ApiPage<Order>>(path), enabled: !dateError })

  async function openDelivery(row: Row) {
    if (loadingDeliveryId || !canDeliver) return
    setLoadingDeliveryId(row.id)
    setDeliveryLoadError('')
    setDeliveryFeedback('')
    try {
      const detail = await apiGet<{ order: { status: string }; sale: { balance: number; previousDebtAvailable?: number } }>(`/api/orders/${row.id}`)
      if (detail.order.status !== 'CONFIRMED') {
        await query.refetch()
        throw new Error('El pedido ya no está confirmado. Se actualizó el listado.')
      }
      if (!Number.isFinite(detail.sale.balance) || detail.sale.balance < 0) throw new Error('No se pudo obtener el saldo pendiente de la venta.')
      delivery.reset()
      const previousDebtAvailable = Number(detail.sale.previousDebtAvailable ?? 0)
      if (!Number.isFinite(previousDebtAvailable) || previousDebtAvailable < 0) throw new Error('No se pudo obtener la deuda anterior disponible.')
      setDeliveryOrder({ id: row.id, number: row.number, saleBalance: detail.sale.balance, previousDebtAvailable })
    } catch (cause) {
      setDeliveryLoadError(cause instanceof Error ? cause.message : 'No se pudo cargar el pedido para registrar la entrega.')
    } finally { setLoadingDeliveryId('') }
  }

  function setDateFilter(key: string, value: string) {
    const next = new URLSearchParams(params)
    next.set(key, value)
    next.delete('page')
    setParams(next, { replace: true })
  }
  const hasFilters = Boolean(search || status || dateMin || dateMax)

  function clearFilters() {
    const next = new URLSearchParams(params)
    next.delete('search')
    next.delete('status')
    next.delete('page')
    next.set('dateMin', '')
    next.set('dateMax', '')
    setParams(next, { replace: true })
  }
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
    { key: 'action', label: '', render: (_value, row) => <div className="table-row-actions"><Button id={`order-details-${row.id}`} variant="link" href={`/orders/${row.id}`} aria-label={`Abrir pedido ${row.number}`}>Ver detalle</Button>{isAdmin && row.status === 'CONFIRMED' && <Button variant="secondary" href={`/orders/${row.id}?edit=true`} aria-label={`Editar pedido ${row.number}`}>Editar</Button>}{canDeliver && row.status === 'CONFIRMED' && <Button id={`order-delivery-${row.id}`} type="button" onClick={() => void openDelivery(row)} disabled={Boolean(loadingDeliveryId)} aria-busy={loadingDeliveryId === row.id} aria-label={`Pasar a entregado pedido ${row.number}`}>{loadingDeliveryId === row.id ? 'Cargando...' : 'Pasar a entregado'}</Button>}</div> },
  ]

  return <>
    <PageHeader eyebrow="Operación" title="Pedidos" description="Consultá pedidos, cobros, entregas y snapshots comerciales." actions={<Button href="/orders/new">+ Nuevo pedido</Button>} />
    <Panel>
      <form className="toolbar orders-toolbar" onSubmit={(event) => event.preventDefault()}>
        <label className="field"><span>Buscar pedidos</span><input id="orders-search" className="input search-input" placeholder="Cliente o número" aria-label="Buscar pedidos" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
        <label className="field"><span>Filtrar por estado</span><select className="select" aria-label="Filtrar por estado" value={status} onChange={(event) => setFilter('status', event.target.value)}><option value="">Todos los estados</option><option value="CONFIRMED">Confirmado</option><option value="DELIVERED">Entregado</option><option value="CANCELLED">Cancelado</option></select></label>
        <div className="orders-date-filters">
          <OrderDateFilter id="orders-date-min" label="Desde" value={dateMin} isoValue={min ?? ''} onChange={(value) => setDateFilter('dateMin', value)} invalid={min === null || invalidRange} describedBy={dateError ? 'orders-date-error' : undefined} />
          <OrderDateFilter id="orders-date-max" label="Hasta" value={dateMax} isoValue={max ?? ''} onChange={(value) => setDateFilter('dateMax', value)} invalid={max === null || invalidRange} describedBy={dateError ? 'orders-date-error' : undefined} />
        </div>
      </form>
      {deliveryLoadError && <p className="error-text" role="alert">{deliveryLoadError} Puedes volver a intentar desde el botón del pedido.</p>}
      {deliveryFeedback && <p className="success-text" role="status">{deliveryFeedback}</p>}
      {dateError ? <p id="orders-date-error" className="error-text" role="alert">{dateError}</p> : query.isLoading ? <EmptyState title="Cargando pedidos" description="Consultando pedidos y ventas." /> : query.isError ? <EmptyState title="No se pudieron cargar los pedidos" description={query.error.message} /> : rows.length === 0 ? <EmptyState title={hasFilters ? 'No hay pedidos para estos filtros' : 'No hay pedidos para mostrar'} description={hasFilters ? 'No se encontraron pedidos para la búsqueda y el rango de fechas seleccionados. Podés quitar los filtros para consultar todos los pedidos.' : 'Todavía no hay pedidos registrados para tu usuario.'} action={hasFilters ? <Button variant="secondary" type="button" onClick={clearFilters}>Ver todos los pedidos</Button> : <Button href="/orders/new">+ Nuevo pedido</Button>} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} pedidos</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    {deliveryOrder && <DeliveryAttemptDialog mode="delivery" returnFocusId={`order-delivery-${deliveryOrder.id}`} orderNumber={deliveryOrder.number} saleBalance={deliveryOrder.saleBalance} previousDebtAvailable={deliveryOrder.previousDebtAvailable} value={delivery.draft} pending={delivery.pending} error={delivery.error} onChange={delivery.changeDraft} onSubmit={delivery.submit} onClose={() => setDeliveryOrder(undefined)} />}
  </>
}
