import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { Pagination } from '../../shared/components/Pagination'
import { CurrencyText } from '../../shared/components/CurrencyText'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useLocation } from 'react-router-dom'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { FormModal } from '../../shared/components/FormModal'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { CustomerSelect, SellerSelect } from '../../shared/components/EntitySelect'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { useUrlListState } from '../../shared/useUrlListState'
import { formatBusinessDateMedium, formatBusinessDateTime, parseOptionalDate, rangeError, todayInArgentina } from '../../shared/dates'
import { parseDecimalInput } from '../../shared/decimal'
import { formatMoney } from '../../shared/format'
import { OrderDateFilter } from '../orders/OrderDateFilter'

type FilterOption = { id: string; name: string }
type SaleFilterOptions = { customers: FilterOption[]; sellers: FilterOption[] }
type Sale = { id: string; number: string; customer: string; customerId?: string; accountCustomerId?: string; account?: string; seller?: string; total: number; paid: number; balance: number; status: string; date: string }
type SaleItem = { saleItemId: string; productId: string; productName: string; quantity: number; returnedQuantity: number; returnableQuantity: number }
type SaleDetail = {
  order?: { id: string; number: string; customer: string; customerId?: string; seller?: string; status: string; total: number; date: string; accountCustomerId?: string; accountCustomer?: string; accountKind?: string | null }
  sale: { id: string; number: string; status: string; total?: number; paid?: number; balance?: number; date?: string }
  payments?: Array<{ id: string; amount: number; method: string; transferReference?: string | null; date: string }>
  saleItems: SaleItem[]
}
type Row = Record<string, string>
const money = (value: unknown) => formatMoney(value, 2)
const formatDate = (value: string) => formatBusinessDateMedium(value)
const formatDateTime = (value: string) => formatBusinessDateTime(value)
const stateName: Record<string, string> = { CONFIRMED: 'Confirmada', DELIVERED: 'Entregada', CANCELLED: 'Cancelada', RETURNED: 'Con devoluciones' }
const paymentMethodName: Record<string, string> = { CASH: 'Efectivo', BANK_TRANSFER: 'Transferencia' }

function parseDate(value: string) {
  return parseOptionalDate(value)
}

export default function SalesPage() {
  const queryClient = useQueryClient()
  const location = useLocation()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canReturn = hasPermission('SALE_RETURN')
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState(['search', 'customerId', 'sellerId', 'pendingBalance', 'dateMin', 'dateMax', 'accountCustomerId'])
  const search = getFilter('search')
  const customerId = getFilter('customerId')
  const sellerId = getFilter('sellerId')
  // The account that owes the sales: a reseller owes the orders of its customers.
  const accountCustomerId = getFilter('accountCustomerId')
  const pendingBalance = getFilter('pendingBalance') === 'true'
  const dateMin = getFilter('dateMin')
  const dateMax = getFilter('dateMax')
  const min = parseDate(dateMin)
  const max = parseDate(dateMax)
  const invalidRange = Boolean(min && max && min > max)
  const dateError = min === null || max === null ? 'Las fechas deben ser válidas y tener formato dd/mm/aaaa.' : invalidRange ? 'La fecha mínima no puede ser posterior a la fecha máxima.' : ''
  const [selectedSaleId, setSelectedSaleId] = useState('')
  const [returnReason, setReturnReason] = useState('')
  const [returnQuantities, setReturnQuantities] = useState<Record<string, string>>({})
  const [returnError, setReturnError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [savingReturn, setSavingReturn] = useState(false)
  const path = `/api/sales?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}&customerId=${encodeURIComponent(customerId)}&sellerId=${encodeURIComponent(sellerId)}&pendingBalance=${pendingBalance}&dateMin=${min ?? ''}&dateMax=${max ?? ''}${accountCustomerId ? `&accountCustomerId=${encodeURIComponent(accountCustomerId)}` : ''}`
  const query = useQuery({ queryKey: queryKeys.sales.list(path), queryFn: ({ signal }) => apiGet<ApiPage<Sale>>(path, signal), enabled: !dateError })
  const optionsQuery = useQuery({ queryKey: queryKeys.sales.filterOptions, queryFn: ({ signal }) => apiGet<SaleFilterOptions>('/api/sales/filter-options', signal) })
  const customers = optionsQuery.data?.customers ?? []
  const sellers = optionsQuery.data?.sellers ?? []
  const detailQuery = useQuery({
    queryKey: queryKeys.sales.detail(selectedSaleId),
    queryFn: ({ signal }) => apiGet<SaleDetail>(`/api/sales/${selectedSaleId}`, signal),
    enabled: Boolean(selectedSaleId),
  })
  const hasReturnChanges = Boolean(selectedSaleId && (returnReason.trim() || Object.values(returnQuantities).some((value) => value.trim())))
  function openSale(id: string) {
    setSelectedSaleId(id)
    setReturnReason('')
    setReturnQuantities({})
    setReturnError('')
    setFeedback('')
  }
  const { requestDiscard, discardDialog } = useDiscardChanges({
    hasChanges: hasReturnChanges,
    disabled: savingReturn,
    onDiscard: () => {
      setSelectedSaleId('')
      setReturnReason('')
      setReturnQuantities({})
      setReturnError('')
      setFeedback('')
    },
  })

  async function submitReturn(event: FormEvent) {
    event.preventDefault()
    if (!canReturn || savingReturn || !selectedSaleId || !detailQuery.data) return
    const rawEntries = detailQuery.data.saleItems.flatMap((item) => {
      const raw = (returnQuantities[item.saleItemId] ?? '').trim()
      if (!raw) return []
      return [{ item, quantity: parseDecimalInput(raw) }]
    })
    if (!returnReason.trim()) { setReturnError('Ingresá el motivo de la devolución.'); return }
    if (rawEntries.length === 0) { setReturnError('Ingresá una cantidad para al menos una línea.'); return }
    if (rawEntries.some(({ item, quantity }) =>
      !Number.isFinite(quantity) || quantity <= 0 || quantity > item.returnableQuantity || Math.round(quantity * 2) !== quantity * 2
    )) { setReturnError('Cada cantidad debe ser múltiplo de 0,5 y no superar lo disponible.'); return }
    const items = rawEntries.map(({ item, quantity }) => ({ saleItemId: item.saleItemId, quantity }))
    setSavingReturn(true)
    setReturnError('')
    setFeedback('')
    try {
      await apiPost(`/api/sales/${selectedSaleId}/returns`, { reason: returnReason.trim(), items })
      await invalidateDomains(queryClient, 'orders', 'sales', 'stock', 'reports', 'customers')
      setReturnReason('')
      setReturnQuantities({})
      setFeedback('Devolución registrada y stock actualizado. Esta operación no genera un reintegro ni crédito monetario.')
    } catch (cause) {
      const status = cause instanceof ApiError ? cause.status : undefined
      setReturnError(status === 403 ? 'No tenés permiso para registrar devoluciones.' : status === 409 ? cause instanceof Error ? cause.message : 'La cantidad disponible cambió.' : cause instanceof Error ? cause.message : 'No se pudo registrar la devolución.')
    } finally {
      setSavingReturn(false)
    }
  }
  const rows: Row[] = (query.data?.content ?? []).map((sale) => ({
    id: sale.id,
    number: sale.number,
    customer: sale.customer,
    representative: sale.accountCustomerId && sale.customerId && sale.accountCustomerId !== sale.customerId ? sale.account || 'Sin asignar' : sale.seller || 'Sin asignar',
    date: formatDate(sale.date),
    total: money(sale.total),
    paid: money(sale.paid),
    balance: money(sale.balance),
    status: sale.status,
  }))
  const columns: TableColumn[] = [
    { key: 'number', label: 'Venta', emphasis: true, render: (value, row) => <Button variant="link" aria-label={`Abrir venta ${value}`} onClick={() => openSale(row.id)}>{value}</Button> },
    { key: 'customer', label: 'Cliente' },
    { key: 'representative', label: 'Vendedor / Revendedor' },
    { key: 'date', label: 'Fecha' },
    { key: 'total', label: 'Total', align: 'right' },
    { key: 'paid', label: 'Cobrado', align: 'right' },
    { key: 'balance', label: 'Saldo', align: 'right' },
    { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'DELIVERED' ? 'strong' : value === 'CANCELLED' ? 'muted' : 'soft'}>{stateName[value] ?? value}</Badge> },
    { key: 'actions', label: 'Detalle', render: (_value, row) => <Button variant="link" aria-label={`Ver venta ${row.number}`} onClick={() => openSale(row.id)}>Ver venta</Button> },
  ]
  const soldProductsParams = new URLSearchParams()
  if (min && max && !rangeError(min, max, todayInArgentina())) {
    soldProductsParams.set('dateMin', dateMin)
    soldProductsParams.set('dateMax', dateMax)
  }
  soldProductsParams.set('returnTo', `${location.pathname}${location.search}`)
  const soldProductsHref = `/sales/products?${soldProductsParams}`

  return <>
    <PageHeader eyebrow="Operación" title="Ventas" description={isAdmin ? 'Consultá importes cobrados, saldos y estado de las ventas.' : 'Consultá importes cobrados y saldos de tus ventas entregadas.'}
      actions={hasPermission('REPORTS_VIEW') ? <Button variant="secondary" href={soldProductsHref}>Artículos vendidos</Button> : undefined} />
    <Panel>
      <form className="toolbar items-end" onSubmit={(event) => event.preventDefault()}>
        <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Buscar ventas</span><input className="input search-input [@media(max-width:640px)]:max-w-none" placeholder="Número de venta" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label>
        <CustomerSelect options={customers} value={customerId} onChange={(value) => setFilter('customerId', value)} loading={optionsQuery.isLoading} disabled={optionsQuery.isError} />
        <SellerSelect options={sellers} value={sellerId} onChange={(value) => setFilter('sellerId', value)} loading={optionsQuery.isLoading} disabled={optionsQuery.isError} />
        <label className="field min-w-0 [@media(max-width:640px)]:w-full"><span>Saldo pendiente</span><select className="select [@media(max-width:640px)]:w-full [@media(max-width:640px)]:min-w-0" value={pendingBalance ? 'true' : ''} onChange={(event) => setFilter('pendingBalance', event.target.value)}><option value="">{isAdmin ? 'Todas las ventas' : 'Todas las entregadas'}</option><option value="true">Solo con saldo impago</option></select></label>
        <div className="orders-date-filters">
          <OrderDateFilter id="sales-date-min" label="Desde" value={dateMin} isoValue={min ?? ''} onChange={(value) => setFilter('dateMin', value)} invalid={min === null || invalidRange} describedBy={dateError ? 'sales-date-error' : undefined} />
          <OrderDateFilter id="sales-date-max" label="Hasta" value={dateMax} isoValue={max ?? ''} onChange={(value) => setFilter('dateMax', value)} invalid={max === null || invalidRange} describedBy={dateError ? 'sales-date-error' : undefined} />
        </div>
      </form>
      {accountCustomerId && <p className="helper-text" role="status">Ventas a cargo de la cuenta de {query.data?.content[0]?.account ?? 'la cuenta seleccionada'}, de todos sus clientes. <Button variant="link" type="button" onClick={() => setFilter('accountCustomerId', '')}>Quitar filtro de cuenta</Button></p>}
      {optionsQuery.isError && <p className="error-text" role="alert">No se pudieron cargar los clientes y vendedores. <Button variant="link" type="button" onClick={() => optionsQuery.refetch()}>Reintentar filtros</Button></p>}
      {dateError ? <p id="sales-date-error" className="error-text" role="alert">{dateError}</p> : query.isLoading ? <EmptyState state="loading" title="Cargando ventas" description="Consultando ventas registradas." /> : query.isError ? <EmptyState state="error" title="No se pudieron cargar las ventas" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} /> : rows.length === 0 ? <EmptyState title="No hay ventas para mostrar" description="No se encontraron ventas con los filtros seleccionados." /> : <><DataTable className="compact-list-table sales-list-table" columns={columns} rows={rows} /><Pagination summary={<>Página {page + 1} · {query.data?.totalElements ?? 0} ventas</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= (query.data?.totalPages ?? 0)} /></>}
    </Panel>
    {selectedSaleId && <FormModal label="Detalle de venta" className="sale-detail-dialog" busy={savingReturn} onClose={requestDiscard}>
      <Panel title={detailQuery.data ? 'Venta ' + detailQuery.data.sale.number : 'Detalle de venta'} description="Pedido, cliente, vendedor, cobros y productos de la venta." action={<Button type="button" variant="link" disabled={savingReturn} onClick={requestDiscard}>Cerrar</Button>}>
      {detailQuery.isLoading ? <EmptyState state="loading" title="Cargando venta" description="Consultando snapshots y líneas de venta." /> : detailQuery.isError ? <EmptyState state="error" title="No se pudo cargar la venta" description={detailQuery.error.message} action={<Button type="button" variant="secondary" onClick={() => void detailQuery.refetch()}>Reintentar</Button>} /> : !detailQuery.data ? <EmptyState title="Venta no disponible" description="No se encontró el detalle de esta venta." /> : <>
        {feedback && <p className="success-text" role="status"><CurrencyText>{feedback}</CurrencyText></p>}
        {returnError && <p className="error-text" role="alert">{returnError}</p>}
        <dl className="confirmation-result">
          <dt>Pedido</dt><dd>{detailQuery.data.order?.number ?? '—'}</dd>
          <dt>Cliente</dt><dd>{detailQuery.data.order?.customer ?? '—'}</dd>
          {detailQuery.data.order?.accountCustomerId && detailQuery.data.order.accountCustomerId !== detailQuery.data.order.customerId && <><dt>Cuenta</dt><dd>{detailQuery.data.order.accountCustomer} (revendedor)</dd></>}
          <dt>Vendedor</dt><dd>{detailQuery.data.order?.seller ?? 'Sin asignar'}</dd>
          <dt>Fecha</dt><dd>{detailQuery.data.sale.date ? formatDateTime(detailQuery.data.sale.date) : detailQuery.data.order?.date ? formatDateTime(detailQuery.data.order.date) : '—'}</dd>
          <dt>Total</dt><dd><CurrencyText>{money(detailQuery.data.sale.total ?? detailQuery.data.order?.total)}</CurrencyText></dd>
          <dt>Pagado</dt><dd><CurrencyText>{money(detailQuery.data.sale.paid)}</CurrencyText></dd>
          <dt>Saldo pendiente</dt><dd><CurrencyText>{money(detailQuery.data.sale.balance)}</CurrencyText></dd>
          <dt>Estado</dt><dd>{stateName[detailQuery.data.sale.status] ?? detailQuery.data.sale.status}</dd>
        </dl>
        <h3>Pagos registrados</h3>
        {(detailQuery.data.payments ?? []).length === 0 ? <p className="helper-text">No hay pagos registrados.</p> : <DataTable columns={[
          { key: 'date', label: 'Día y horario' },
          { key: 'method', label: 'Medio de pago' },
          { key: 'amount', label: 'Monto', align: 'right' },
          { key: 'reference', label: 'Referencia' },
        ]} rows={(detailQuery.data.payments ?? []).map((payment) => ({
          id: payment.id,
          date: formatDateTime(payment.date),
          method: paymentMethodName[payment.method] ?? payment.method,
          amount: money(payment.amount),
          reference: payment.transferReference || '—',
        }))} />}
        <DataTable columns={[
          { key: 'product', label: 'Producto', emphasis: true },
          { key: 'sold', label: 'Vendida', align: 'right' },
          { key: 'returned', label: 'Devuelta', align: 'right' },
          { key: 'available', label: 'Disponible', align: 'right' },
        ]} rows={detailQuery.data.saleItems.map((item) => ({ id: item.saleItemId, product: item.productName, sold: String(item.quantity), returned: String(item.returnedQuantity), available: String(item.returnableQuantity) }))} />
        {canReturn && detailQuery.data.sale.status === 'DELIVERED' && detailQuery.data.order?.accountKind === 'RESELLER' && <p className="helper-text">Las ventas a cargo de un revendedor no admiten devoluciones.</p>}
        {canReturn && detailQuery.data.sale.status === 'DELIVERED' && detailQuery.data.order?.accountKind !== 'RESELLER' && <form className="form-grid" onSubmit={submitReturn}>
          <label className="field"><span>Motivo de la devolución</span><input className="input" value={returnReason} onChange={(event) => setReturnReason(event.target.value)} maxLength={500} required disabled={savingReturn} /></label>
          {detailQuery.data.saleItems.filter((item) => item.returnableQuantity > 0).map((item) => <label className="field" key={item.saleItemId}><span>Cantidad a devolver · {item.productName} (máx. {item.returnableQuantity})</span><input className="input" type="text" inputMode="decimal" aria-label={`Cantidad a devolver · ${item.productName}`} value={returnQuantities[item.saleItemId] ?? ''} onChange={(event) => setReturnQuantities((current) => ({ ...current, [item.saleItemId]: event.target.value }))} disabled={savingReturn} /></label>)}
          <p className="helper-text">La devolución reincorpora unidades al stock del depósito original. El comando actual no realiza reintegros ni acredita la cuenta corriente.</p>
          <div className="col-span-full page-actions justify-end">
            <Button type="submit" disabled={savingReturn}>{savingReturn ? 'Registrando…' : 'Registrar devolución'}</Button>
          </div>
        </form>}
      </>}
      </Panel>
    </FormModal>}
    {discardDialog}
  </>
}
