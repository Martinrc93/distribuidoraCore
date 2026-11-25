import { queryKeys } from '../../shared/api/queryKeys'
import { Pagination } from '../../shared/components/Pagination'
import { useEffect, useState, type KeyboardEvent } from 'react'
import { useLocation, useSearchParams } from 'react-router-dom'
import { saveOrderNavigation } from './orderNavigation'
import { useQueries, useQuery } from '@tanstack/react-query'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { SecondaryRowActions } from '../../shared/components/SecondaryRowActions'
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
type DeliveryOrder = { id: string; number: string; saleBalance: number; previousDebtAvailable: number; accountKind?: string | null; returnFocusId: string }

/** The order's state groups the list; preparation splits the orders still to deliver. */
const stages = [
  { status: '', label: 'Todos' },
  { status: 'CONFIRMED', label: 'Por entregar' },
  { status: 'DELIVERED', label: 'Entregados' },
  { status: 'CANCELLED', label: 'Cancelados' },
] as const
const preparationStages = [
  { fulfillment: '', label: 'Todos' },
  { fulfillment: 'PENDING', label: 'A preparar' },
  { fulfillment: 'PREPARED', label: 'Listos para entregar' },
] as const
const knownStatuses: readonly string[] = stages.map((stage) => stage.status)

function money(value: unknown) {
  return formatMoney(value, 2)
}

function formatDate(value: string) {
  return formatBusinessDate(value)
}

function parseDate(value: string) {
  return parseOptionalDate(value)
}

function StageCount({ value }: { value: number | undefined }) {
  if (value === undefined) return null
  return <span className="orders-stage-count"><span className="sr-only">: </span>{value}<span className="sr-only"> pedidos</span></span>
}

export default function OrdersPage() {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canCreate = isAdmin || hasAuthority('ORDER_CREATE')
  const canDeliver = isAdmin || hasAuthority('SALE_DELIVER')
  const canPrepare = isAdmin || hasAuthority('ORDER_PREPARE')
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
  const status = knownStatuses.includes(getFilter('status')) ? getFilter('status') : ''
  const fulfillment = status === 'CONFIRMED' && ['PENDING', 'PREPARED'].includes(getFilter('fulfillment')) ? getFilter('fulfillment') : ''
  const reviewPending = isAdmin && getFilter('review') === 'PENDING'
  const createdBy = isAdmin && ['ADMIN', 'SELLER'].includes(getFilter('createdBy')) ? getFilter('createdBy') : ''
  const paidUndelivered = getFilter('paid') === 'UNDELIVERED'
  const location = useLocation()
  const search = getFilter('search')
  const [params, setParams] = useSearchParams()
  const [today] = useState(() => displayDate(todayInArgentina()))
  const activeSecondaryCount = [paidUndelivered, reviewPending, createdBy].filter(Boolean).length
  const [moreFiltersOpen, setMoreFiltersOpen] = useState(() => activeSecondaryCount > 0)
  const dateMin = params.get('dateMin') ?? today
  const dateMax = params.get('dateMax') ?? today
  const min = parseDate(dateMin)
  const max = parseDate(dateMax)
  const invalidRange = Boolean(min && max && min > max)
  const dateError = min === null || max === null ? 'Las fechas deben ser válidas y tener formato dd/mm/aaaa.' : invalidRange ? 'La fecha mínima no puede ser posterior a la fecha máxima.' : ''

  // Links saved before the stage tabs could filter preparation without the order's state.
  const legacyFulfillment = params.get('fulfillment') ?? ''
  useEffect(() => {
    if (!['PENDING', 'PREPARED'].includes(legacyFulfillment) || params.get('status')) return
    const next = new URLSearchParams(params)
    next.set('status', 'CONFIRMED')
    setParams(next, { replace: true })
  }, [legacyFulfillment, params, setParams])

  function ordersPath(pageNumber: number, size: number, stageStatus: string, stageFulfillment: string) {
    return `/api/orders?page=${pageNumber}&size=${size}&search=${encodeURIComponent(search.trim())}&status=${encodeURIComponent(stageStatus)}&dateMin=${min ?? ''}&dateMax=${max ?? ''}${createdBy ? `&createdBy=${createdBy}` : ''}${paidUndelivered ? '&paidUndelivered=true' : ''}${stageFulfillment ? `&fulfillmentStatus=${stageFulfillment}` : ''}${reviewPending ? '&reviewPending=true' : ''}`
  }
  const path = ordersPath(page, pageSize, status, fulfillment)
  const query = useQuery({ queryKey: queryKeys.orders.list(path), queryFn: ({ signal }) => apiGet<ApiPage<Order>>(path, signal), enabled: !dateError })

  // Each stage shows how many orders match the other filters; a failed count hides only that number.
  const countRequests = [
    ...stages.map((stage) => ordersPath(0, 1, stage.status, '')),
    ...(status === 'CONFIRMED' ? preparationStages.slice(1).map((stage) => ordersPath(0, 1, 'CONFIRMED', stage.fulfillment)) : []),
  ]
  const counts = useQueries({ queries: countRequests.map((request) => ({
    queryKey: queryKeys.orders.list(request),
    queryFn: ({ signal }: { signal: AbortSignal }) => apiGet<ApiPage<Order>>(request, signal),
    enabled: !dateError,
    retry: false,
  })) }).map((result) => typeof result.data?.totalElements === 'number' ? result.data.totalElements : undefined)
  const stageCount = (index: number) => counts[index]
  const preparationCount = (index: number) => index === 0 ? counts[1] : counts[stages.length + index - 1]

  async function openDelivery(row: Row, fromMenu: boolean) {
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
      setDeliveryOrder({ id: row.id, number: row.number, saleBalance: detail.sale.balance, previousDebtAvailable, accountKind: detail.order.accountKind, returnFocusId: fromMenu ? `order-more-${row.id}` : `order-delivery-${row.id}` })
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

  function selectStage(stageStatus: string) {
    const next = new URLSearchParams(params)
    if (stageStatus) next.set('status', stageStatus)
    else next.delete('status')
    next.delete('fulfillment')
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

  /** ↑/↓ move between the «Ver» links of the rows. */
  function moveBetweenRows(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    const links = [...event.currentTarget.querySelectorAll<HTMLElement>('[id^="order-details-"]')]
    const current = links.indexOf(document.activeElement as HTMLElement)
    if (current < 0) return
    event.preventDefault()
    links[Math.min(links.length - 1, Math.max(0, current + (event.key === 'ArrowDown' ? 1 : -1)))]?.focus()
  }

  function deliveryButton(row: Row, fromMenu: boolean) {
    return <Button id={`order-delivery-${row.id}`} type="button" variant={fromMenu ? 'secondary' : 'primary'} onClick={() => void openDelivery(row, fromMenu)} disabled={Boolean(loadingDeliveryId)} aria-busy={loadingDeliveryId === row.id} aria-label={`Pasar a entregado pedido ${row.number}`}>{loadingDeliveryId === row.id ? 'Cargando...' : 'Pasar a entregado'}</Button>
  }

  /** One visible next step per row: preparation while pending, delivery once prepared. The rest goes to the row menu. */
  function rowActions(row: Row) {
    const confirmed = row.status === 'CONFIRMED'
    const operations = { id: row.id, number: row.number, status: row.status, fulfillmentStatus: row.fulfillmentStatus, reviewPending: row.reviewPending === 'true' }
    const onSaved = (message: string) => { setDeliveryFeedback(message); setDeliveryFocusTarget(`order-details-${row.id}`) }
    const prepareFirst = confirmed && canPrepare && row.fulfillmentStatus === 'PENDING'
    const canEditRow = confirmed && hasPermission('ORDER_EDIT')
    const deliverInMenu = confirmed && canDeliver && prepareFirst
    const reviewInMenu = isAdmin && row.reviewPending === 'true'
    return <div className="table-row-actions">
      <Button id={`order-details-${row.id}`} variant="link" href={`/orders/${row.id}`} aria-label={`Abrir pedido ${row.number}`}>Ver</Button>
      {prepareFirst && <OrderOperationalActions order={operations} only="prepare" prepareVariant="primary" onSaved={onSaved} />}
      {confirmed && canDeliver && !prepareFirst && deliveryButton(row, false)}
      {(canEditRow || deliverInMenu || reviewInMenu) && <SecondaryRowActions iconOnly label={`Más acciones del pedido ${row.number}`} triggerId={`order-more-${row.id}`}>
        {canEditRow && <Button variant="secondary" href={`/orders/${row.id}?edit=true`} aria-label={`Editar pedido ${row.number}`}>Editar</Button>}
        {deliverInMenu && deliveryButton(row, true)}
        {reviewInMenu && <OrderOperationalActions order={operations} only="review" onSaved={onSaved} />}
      </SecondaryRowActions>}
    </div>
  }

  const columns: TableColumn[] = [
    { key: 'number', label: 'Pedido', emphasis: true },
    { key: 'customer', label: 'Cliente' },
    { key: 'seller', label: 'Vendedor' },
    { key: 'date', label: 'Fecha', render: (value) => <span className="whitespace-nowrap">{value}</span> },
    { key: 'total', label: 'Total', align: 'right' },
    // A confirmed order shows its preparation, which already implies the confirmation.
    { key: 'status', label: 'Estado', render: (value, row) => <span className="order-status-badges">
      {value === 'CONFIRMED' && row.fulfillmentStatus === 'PREPARED' ? <Badge tone="soft">Preparado</Badge>
        : value === 'CONFIRMED' && row.fulfillmentStatus === 'PENDING' ? <Badge tone="muted">A preparar</Badge>
        : <Badge tone={value === 'CANCELLED' ? 'muted' : value === 'DELIVERED' ? 'strong' : 'soft'}>{orderStatusLabel(value)}</Badge>}
      {row.fullyPaid && <Badge tone="strong">Pagado</Badge>}
    </span> },
    { key: 'action', label: 'Acciones', render: (_value, row) => rowActions(row) },
  ]

  return <>
    <PageHeader title="Pedidos" description="Registrá y seguí preparación, entrega y cobros." actions={canCreate ? <Button href="/orders/new">+ Nuevo pedido</Button> : undefined} />
    <div className="orders-stages" role="group" aria-label="Estado del pedido">
      {stages.map((stage, index) => <button key={stage.label} type="button" className="orders-stage" aria-pressed={status === stage.status} onClick={() => selectStage(stage.status)}>
        {stage.label}<StageCount value={stageCount(index)} />
      </button>)}
    </div>
    <Panel>
      {status === 'CONFIRMED' && <div className="orders-preparation" role="group" aria-label="Preparación">
        {preparationStages.map((stage, index) => <button key={stage.label} type="button" className="orders-preparation-option" aria-pressed={fulfillment === stage.fulfillment} onClick={() => setFilter('fulfillment', stage.fulfillment)}>
          {stage.label}<StageCount value={preparationCount(index)} />
        </button>)}
      </div>}
      <form className="toolbar orders-toolbar items-end" onSubmit={(event) => event.preventDefault()}>
        <label className="field"><span>Buscar pedidos</span><input id="orders-search" className="input search-input" placeholder="Cliente o número" aria-label="Buscar pedidos" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
        <div className="orders-date-filters">
          <OrderDateFilter id="orders-date-min" label="Desde" value={dateMin} isoValue={min ?? ''} onChange={(value) => setDateFilter('dateMin', value)} invalid={min === null || invalidRange} describedBy={dateError ? 'orders-date-error' : undefined} />
          <OrderDateFilter id="orders-date-max" label="Hasta" value={dateMax} isoValue={max ?? ''} onChange={(value) => setDateFilter('dateMax', value)} invalid={max === null || invalidRange} describedBy={dateError ? 'orders-date-error' : undefined} />
        </div>
        <Button type="button" variant="secondary" aria-pressed={!dateMin && !dateMax} onClick={clearDates}>Todas las fechas</Button>
        <Button type="button" variant="secondary" className="orders-more-filters-toggle"
          aria-expanded={moreFiltersOpen} aria-controls="orders-secondary-filters"
          onClick={() => setMoreFiltersOpen((open) => !open)}>
          {activeSecondaryCount ? `Más filtros (${activeSecondaryCount})` : 'Más filtros'}
        </Button>
        <div id="orders-secondary-filters" className={`orders-secondary-filters${moreFiltersOpen ? ' is-open' : ''}`}>
          <label className="field"><span>Cobro</span><select className="select" aria-label="Filtrar por cobro" value={paidUndelivered ? 'UNDELIVERED' : ''} onChange={(event) => setFilter('paid', event.target.value)}><option value="">Todos</option><option value="UNDELIVERED">Pagados sin entregar</option></select></label>
          {isAdmin && <label className="field"><span>Revisión administrativa</span><select className="select" aria-label="Filtrar por revisión" value={reviewPending ? 'PENDING' : ''} onChange={(event) => setFilter('review', event.target.value)}><option value="">Todas</option><option value="PENDING">Pendientes de revisión</option></select></label>}
          {isAdmin && <label className="field"><span>Cargado por</span><select className="select" aria-label="Filtrar por quién cargó el pedido" value={createdBy} onChange={(event) => setFilter('createdBy', event.target.value)}><option value="">Todos</option><option value="ADMIN">Administrador</option><option value="SELLER">Vendedor</option></select></label>}
        </div>
      </form>
      {deliveryLoadError && <p className="error-text" role="alert">{deliveryLoadError} Podés volver a intentar desde el botón del pedido.</p>}
      {deliveryFeedback && <p className="success-text" role="status">{deliveryFeedback}</p>}
      {dateError ? <p id="orders-date-error" className="error-text" role="alert">{dateError}</p> : query.isLoading ? <EmptyState state="loading" title="Cargando pedidos" description="Consultando pedidos y ventas." /> : query.isError ? <EmptyState state="error" title="No se pudieron cargar los pedidos" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} /> : rows.length === 0 ? <EmptyState title={hasFilters ? 'No hay pedidos para estos filtros' : 'No hay pedidos para mostrar'} description={hasFilters ? 'No se encontraron pedidos para la búsqueda y el rango de fechas seleccionados. Podés quitar los filtros para consultar todos los pedidos.' : 'Todavía no hay pedidos registrados para tu usuario.'} action={hasFilters ? <Button variant="secondary" type="button" onClick={clearFilters}>Ver todos los pedidos</Button> : canCreate ? <Button href="/orders/new">+ Nuevo pedido</Button> : undefined} /> : <><div onKeyDown={moveBetweenRows}><DataTable className="compact-list-table orders-list-table" columns={columns} rows={rows} /></div><Pagination summary={<>Página {page + 1} · {query.data?.totalElements ?? 0} pedidos</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= (query.data?.totalPages ?? 0)} /></>}
    </Panel>
    {deliveryOrder && <DeliveryAttemptDialog mode="delivery" returnFocusId={deliveryOrder.returnFocusId} fallbackFocusId={`order-details-${deliveryOrder.id}`} orderNumber={deliveryOrder.number} saleBalance={deliveryOrder.saleBalance} previousDebtAvailable={deliveryOrder.previousDebtAvailable} value={delivery.draft} pending={delivery.pending} error={delivery.error} onChange={delivery.changeDraft} onSubmit={delivery.submit} onClose={() => setDeliveryOrder(undefined)} collectionsNotice={resellerCollectionsNotice(deliveryOrder.accountKind)} />}
  </>
}
