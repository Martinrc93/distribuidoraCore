import { queryKeys } from '../../shared/api/queryKeys'
import { Pagination } from '../../shared/components/Pagination'
import { useEffect, useState, type KeyboardEvent } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { saveOrderNavigation } from './orderNavigation'
import { useQuery } from '@tanstack/react-query'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'
import { orderStatusLabel } from './orderStatus'
import { OrderDateFilter } from './OrderDateFilter'
import { DeliveryAttemptDialog, resellerCollectionsNotice } from './DeliveryAttemptDialog'
import { useOrderDelivery } from './useOrderDelivery'
import { OrderOperationalActions } from './OrderOperationalActions'
import { displayDate, formatBusinessDate, parseOptionalDate, todayInArgentina } from '../../shared/dates'
import { formatMoney } from '../../shared/format'

type Order = { id: string; number: string; customer: string; seller: string; total: number; status: string; date: string; createdByAdmin?: boolean | null; paid?: number; fulfillmentStatus?: string; reviewPending?: boolean }
type Row = Record<string, string>
type DeliveryOrder = { id: string; number: string; saleBalance: number; previousDebtAvailable: number; accountKind?: string | null }

function money(value: unknown) {
  return formatMoney(value, 2)
}

function formatDate(value: string) {
  return formatBusinessDate(value)
}

function parseDate(value: string) {
  return parseOptionalDate(value)
}

export default function OrdersPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canCreate = isAdmin || hasAuthority('ORDER_CREATE')
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
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['search', 'status', 'createdBy', 'paid', 'fulfillment', 'review'])
  const fulfillment = ['PENDING', 'PREPARED'].includes(getFilter('fulfillment')) ? getFilter('fulfillment') : ''
  const reviewPending = isAdmin && getFilter('review') === 'PENDING'
  const createdBy = isAdmin && ['ADMIN', 'SELLER'].includes(getFilter('createdBy')) ? getFilter('createdBy') : ''
  const paidUndelivered = getFilter('paid') === 'UNDELIVERED'
  const location = useLocation()
  const search = getFilter('search')
  const status = getFilter('status')
  const [params, setParams] = useSearchParams()
  const [today] = useState(() => displayDate(todayInArgentina()))
  const activeSecondaryCount = [status, paidUndelivered ? '1' : '', fulfillment, reviewPending ? '1' : '', createdBy].filter(Boolean).length
  const [mobileFiltersOpen, setMobileFiltersOpen] = useState(() => activeSecondaryCount > 0)
  const dateMin = params.get('dateMin') ?? today
  const dateMax = params.get('dateMax') ?? today
  const min = parseDate(dateMin)
  const max = parseDate(dateMax)
  const invalidRange = Boolean(min && max && min > max)
  const dateError = min === null || max === null ? 'Las fechas deben ser válidas y tener formato dd/mm/aaaa.' : invalidRange ? 'La fecha mínima no puede ser posterior a la fecha máxima.' : ''
  const path = `/api/orders?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}&status=${encodeURIComponent(status)}&dateMin=${min ?? ''}&dateMax=${max ?? ''}${createdBy ? `&createdBy=${createdBy}` : ''}${paidUndelivered ? '&paidUndelivered=true' : ''}${fulfillment ? `&fulfillmentStatus=${fulfillment}` : ''}${reviewPending ? '&reviewPending=true' : ''}`
  const query = useQuery({ queryKey: queryKeys.orders.list(path), queryFn: ({ signal }) => apiGet<ApiPage<Order>>(path, signal), enabled: !dateError })

  async function openDelivery(row: Row) {
    if (loadingDeliveryId || !canDeliver) return
    setLoadingDeliveryId(row.id)
    setDeliveryLoadError('')
    setDeliveryFeedback('')
    try {
      const detail = await apiGet<{ order: { status: string; accountKind?: string | null }; sale: { balance: number; previousDebtAvailable?: number } }>(`/api/orders/${row.id}`)
      if (detail.order.status !== 'CONFIRMED') {
        await query.refetch()
        throw new Error('El pedido ya no está confirmado. Se actualizó el listado.')
      }
      if (!Number.isFinite(detail.sale.balance) || detail.sale.balance < 0) throw new Error('No se pudo obtener el saldo pendiente de la venta.')
      delivery.reset()
      const previousDebtAvailable = Number(detail.sale.previousDebtAvailable ?? 0)
      if (!Number.isFinite(previousDebtAvailable) || previousDebtAvailable < 0) throw new Error('No se pudo obtener la deuda anterior disponible.')
      setDeliveryOrder({ id: row.id, number: row.number, saleBalance: detail.sale.balance, previousDebtAvailable, accountKind: detail.order.accountKind })
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
  const hasFilters = Boolean(search || status || dateMin || dateMax || createdBy || paidUndelivered || fulfillment || reviewPending)

  function clearDates() {
    if (!dateMin && !dateMax) return
    const next = new URLSearchParams(params)
    next.delete('page')
    next.set('dateMin', '')
    next.set('dateMax', '')
    setParams(next, { replace: true })
  }

  function clearFilters() {
    const next = new URLSearchParams(params)
    next.delete('search')
    next.delete('status')
    next.delete('createdBy')
    next.delete('paid')
    next.delete('fulfillment')
    next.delete('review')
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
    fulfillmentStatus: order.fulfillmentStatus ?? '',
    reviewPending: order.reviewPending ? 'true' : '',
    date: formatDate(order.date),
    fullyPaid: order.status === 'CONFIRMED' && Number(order.paid ?? 0) >= Number(order.total) ? 'true' : '',
    action: order.number,
  }))
  // The detail page moves between the orders of this page with ← and →.
  useEffect(() => {
    const content = Array.isArray(query.data?.content) ? query.data.content : null
    if (!content) return
    saveOrderNavigation({ ids: content.map((order) => order.id), numbers: content.map((order) => order.number), returnTo: `${location.pathname}${location.search}` })
  }, [query.data, location.pathname, location.search])

  /** ↑/↓ move between the «Ver detalle» links of the rows. */
  function moveBetweenRows(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    const links = [...event.currentTarget.querySelectorAll<HTMLElement>('[id^="order-details-"]')]
    const current = links.indexOf(document.activeElement as HTMLElement)
    if (current < 0) return
    event.preventDefault()
    links[Math.min(links.length - 1, Math.max(0, current + (event.key === 'ArrowDown' ? 1 : -1)))]?.focus()
  }

  const columns: TableColumn[] = [
    { key: 'number', label: 'Pedido', emphasis: true },
    { key: 'customer', label: 'Cliente' },
    { key: 'seller', label: 'Vendedor' },
    { key: 'date', label: 'Fecha', render: (value) => <span className="whitespace-nowrap">{value}</span> },
    { key: 'total', label: 'Total', align: 'right' },
    { key: 'fulfillmentStatus', label: 'Preparación', render: (value, row) => row.status === 'CANCELLED' ? '—' : <Badge tone={value === 'PREPARED' ? 'strong' : 'soft'}>{value === 'PREPARED' ? 'Preparado' : value === 'PENDING' ? 'A preparar' : '—'}</Badge> },
    { key: 'status', label: 'Estado', render: (value, row) => <span className="order-status-badges"><Badge tone={value === 'CANCELLED' ? 'muted' : value === 'DELIVERED' ? 'strong' : 'soft'}>{orderStatusLabel(value)}</Badge>{row.fullyPaid && <Badge tone="strong">Pagado</Badge>}</span> },
    { key: 'action', label: 'Acciones', render: (_value, row) => <div className="table-row-actions"><Button id={`order-details-${row.id}`} variant="link" href={`/orders/${row.id}`} aria-label={`Abrir pedido ${row.number}`}>Ver detalle</Button>{hasPermission('ORDER_EDIT') && row.status === 'CONFIRMED' && <Button variant="secondary" href={`/orders/${row.id}?edit=true`} aria-label={`Editar pedido ${row.number}`}>Editar</Button>}<OrderOperationalActions order={{ id: row.id, number: row.number, status: row.status, fulfillmentStatus: row.fulfillmentStatus, reviewPending: row.reviewPending === 'true' }} onSaved={message => { setDeliveryFeedback(message); setDeliveryFocusTarget(`order-details-${row.id}`) }} />{canDeliver && row.status === 'CONFIRMED' && <Button id={`order-delivery-${row.id}`} type="button" onClick={() => void openDelivery(row)} disabled={Boolean(loadingDeliveryId)} aria-busy={loadingDeliveryId === row.id} aria-label={`Pasar a entregado pedido ${row.number}`}>{loadingDeliveryId === row.id ? 'Cargando...' : 'Pasar a entregado'}</Button>}</div> },
  ]

  return <>
    <PageHeader eyebrow="Operación" title="Pedidos" description="Consultá pedidos, cobros, entregas y snapshots comerciales." actions={canCreate ? <Button href="/orders/new">+ Nuevo pedido</Button> : undefined} />
    <Panel>
      <form className="toolbar orders-toolbar items-end" onSubmit={(event) => event.preventDefault()}>
        <label className="field"><span>Buscar pedidos</span><input id="orders-search" className="input search-input" placeholder="Cliente o número" aria-label="Buscar pedidos" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
        <Button type="button" variant="secondary" className="orders-more-filters-toggle"
          aria-expanded={mobileFiltersOpen} aria-controls="orders-secondary-filters"
          onClick={() => setMobileFiltersOpen((open) => !open)}>
          {activeSecondaryCount ? `Más filtros (${activeSecondaryCount})` : 'Más filtros'}
        </Button>
        <div id="orders-secondary-filters" className={`orders-secondary-filters${mobileFiltersOpen ? ' is-open' : ''}`}>
          <label className="field"><span>Filtrar por estado</span><select className="select" aria-label="Filtrar por estado" value={status} onChange={(event) => setFilter('status', event.target.value)}><option value="">Todos los estados</option><option value="CONFIRMED">Confirmado</option><option value="DELIVERED">Entregado</option><option value="CANCELLED">Cancelado</option></select></label>
          <label className="field"><span>Cobro</span><select className="select" aria-label="Filtrar por cobro" value={paidUndelivered ? 'UNDELIVERED' : ''} onChange={(event) => setFilter('paid', event.target.value)}><option value="">Todos</option><option value="UNDELIVERED">Pagados sin entregar</option></select></label>
          <label className="field"><span>Preparación</span><select className="select" aria-label="Filtrar por preparación" value={fulfillment} onChange={(event) => setFilter('fulfillment', event.target.value)}><option value="">Todas</option><option value="PENDING">A preparar</option><option value="PREPARED">Listos para entregar</option></select></label>
          {isAdmin && <label className="field"><span>Revisión administrativa</span><select className="select" aria-label="Filtrar por revisión" value={reviewPending ? 'PENDING' : ''} onChange={(event) => setFilter('review', event.target.value)}><option value="">Todas</option><option value="PENDING">Pendientes de revisión</option></select></label>}
          {isAdmin && <label className="field"><span>Cargado por</span><select className="select" aria-label="Filtrar por quién cargó el pedido" value={createdBy} onChange={(event) => setFilter('createdBy', event.target.value)}><option value="">Todos</option><option value="ADMIN">Administrador</option><option value="SELLER">Vendedor</option></select></label>}
        </div>
        <div className="orders-date-filters">
          <OrderDateFilter id="orders-date-min" label="Desde" value={dateMin} isoValue={min ?? ''} onChange={(value) => setDateFilter('dateMin', value)} invalid={min === null || invalidRange} describedBy={dateError ? 'orders-date-error' : undefined} />
          <OrderDateFilter id="orders-date-max" label="Hasta" value={dateMax} isoValue={max ?? ''} onChange={(value) => setDateFilter('dateMax', value)} invalid={max === null || invalidRange} describedBy={dateError ? 'orders-date-error' : undefined} />
        </div>
        <Button type="button" variant="secondary" aria-pressed={!dateMin && !dateMax} onClick={clearDates}>Todas las fechas</Button>
      </form>
      {deliveryLoadError && <p className="error-text" role="alert">{deliveryLoadError} Podés volver a intentar desde el botón del pedido.</p>}
      {deliveryFeedback && <p className="success-text" role="status">{deliveryFeedback}</p>}
      {dateError ? <p id="orders-date-error" className="error-text" role="alert">{dateError}</p> : query.isLoading ? <EmptyState state="loading" title="Cargando pedidos" description="Consultando pedidos y ventas." /> : query.isError ? <EmptyState state="error" title="No se pudieron cargar los pedidos" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} /> : rows.length === 0 ? <EmptyState title={hasFilters ? 'No hay pedidos para estos filtros' : 'No hay pedidos para mostrar'} description={hasFilters ? 'No se encontraron pedidos para la búsqueda y el rango de fechas seleccionados. Podés quitar los filtros para consultar todos los pedidos.' : 'Todavía no hay pedidos registrados para tu usuario.'} action={hasFilters ? <Button variant="secondary" type="button" onClick={clearFilters}>Ver todos los pedidos</Button> : canCreate ? <Button href="/orders/new">+ Nuevo pedido</Button> : undefined} /> : <><div onKeyDown={moveBetweenRows}><DataTable className="compact-list-table orders-list-table" columns={columns} rows={rows} /></div><Pagination summary={<>Página {page + 1} · {query.data?.totalElements ?? 0} pedidos</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= (query.data?.totalPages ?? 0)} /></>}
    </Panel>
    {deliveryOrder && <DeliveryAttemptDialog mode="delivery" returnFocusId={`order-delivery-${deliveryOrder.id}`} orderNumber={deliveryOrder.number} saleBalance={deliveryOrder.saleBalance} previousDebtAvailable={deliveryOrder.previousDebtAvailable} value={delivery.draft} pending={delivery.pending} error={delivery.error} onChange={delivery.changeDraft} onSubmit={delivery.submit} onClose={() => setDeliveryOrder(undefined)} collectionsNotice={resellerCollectionsNotice(deliveryOrder.accountKind)} />}
  </>
}
