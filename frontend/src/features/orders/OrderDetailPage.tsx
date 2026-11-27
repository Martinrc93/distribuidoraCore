import { CurrencyText } from '../../shared/components/CurrencyText'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet } from '../../shared/api/client'
import { queryKeys } from '../../shared/api/queryKeys'
import { formatMoney as money, formatNumber } from '../../shared/format'
import { formatBusinessDateTime as date } from '../../shared/dates'
import { hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { readOrdersReturnPath } from './orderNavigation'
import { Panel } from '../../shared/components/Panel'
import { orderStatusLabel } from './orderStatus'
import OrderLifecycleActions from './OrderLifecycleActions'
import OrderForm from './OrderForm'
import { OrderOperationalActions, OrderReviewBadge } from './OrderOperationalActions'

type OrderItem = { productId: string; productName: string; quantity: number; unitPrice: number; lineTotal: number; priceListId: string; priceListCode: string; lineDiscountPercent: number }
type Payment = { id: string; amount: number; method: string; transferReference?: string | null; date: string }
type DeliveryAttempt = { id: string; attemptNumber: number; result: 'DELIVERED' | 'FAILED'; observation?: string | null; attemptedAt: string; attemptedBy: string }
type OrderDetail = {
  order: { id: string; number: string; customerId: string; customer: string; seller?: string; status: string; subtotal: number; discount: number; orderDiscountPercent?: number; total: number; customerBalance: number; date: string; previousBalanceAmount?: number; collectionTotal?: number; fulfillmentStatus?: string; preparedAt?: string; legacyFulfillment?: boolean; reviewPending?: boolean; reviewedAt?: string; accountCustomerId?: string; accountCustomer?: string; accountKind?: string | null }
  items: OrderItem[]
  sale: { id: string; number: string; status: string; total: number; paid: number; balance: number; previousDebtAvailable?: number; date: string }
  payments: Payment[]
  deliveryAttempts: DeliveryAttempt[]
}
type Row = Record<string, string>

const paymentName: Record<string, string> = { CASH: 'Efectivo', BANK_TRANSFER: 'Transferencia', CUSTOMER_ACCOUNT: 'Cuenta corriente' }

export default function OrderDetailPage() {
  const { orderId = '' } = useParams()
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryKey = queryKeys.orders.detail(orderId)
  const query = useQuery({ queryKey, queryFn: ({ signal }) => apiGet<OrderDetail>(`/api/orders/${orderId}`, signal), enabled: Boolean(orderId) })
  const [params, setParams] = useSearchParams()
  const editing = params.get('edit') === 'true'
  const [feedback, setFeedback] = useState('')
  useEffect(() => { if (feedback) document.getElementById('order-operations')?.focus() }, [feedback])

  function setEditing(value: boolean) {
    const next = new URLSearchParams(params)
    if (value) next.set('edit', 'true')
    else next.delete('edit')
    setParams(next, { replace: true })
  }

  if (query.isLoading) return <><PageHeader title="Pedido" description="Consultando pedido, venta y cobros." /><Panel><EmptyState state="loading" title="Cargando pedido" description="Consultando snapshots comerciales." /></Panel></>
  if (query.isError || !query.data) return <><PageHeader title="Pedido" description="No se pudo abrir el detalle." /><Panel><EmptyState state="error" title="No se pudo cargar el pedido" description={query.error?.message ?? 'Revisá el número o el acceso al pedido.'} action={<Button variant="secondary" href="/orders">Volver a pedidos</Button>} /></Panel></>

  const { order, items, sale, payments, deliveryAttempts = [] } = query.data
  const commonListId = items[0]?.priceListId
  const samePriceList = items.every((item) => item.priceListId === commonListId)
  const canEdit = hasPermission('ORDER_EDIT') && order.status === 'CONFIRMED' && sale.status === 'CONFIRMED' && samePriceList
  // A reseller owes the orders of its customers: the account differs from the customer that receives the order.
  const resellerAccount = Boolean(order.accountCustomerId && order.accountCustomerId !== order.customerId)
  const previousBalance = Number(order.previousBalanceAmount ?? 0)
  const includesPreviousBalance = previousBalance > 0
  const total = includesPreviousBalance ? order.collectionTotal ?? Number(order.total) + previousBalance : order.total

  if (editing && canEdit) return <OrderForm editOrder={query.data} onCancel={() => setEditing(false)} onSaved={async () => {
    await queryClient.invalidateQueries({ queryKey })
    setEditing(false)
    setFeedback('Pedido actualizado. Se conservaron los cobros existentes.')
  }} />

  const itemRows: Row[] = items.map((item) => ({
    id: item.productId,
    product: item.productName,
    quantity: String(item.quantity),
    price: money(item.unitPrice),
    discount: `${Number(item.lineDiscountPercent ?? 0)}%`,
    list: item.priceListCode,
    total: money(item.lineTotal),
  }))
  const itemColumns: TableColumn[] = [
    { key: 'product', label: 'Producto', emphasis: true },
    { key: 'quantity', label: 'Cantidad', align: 'right' },
    { key: 'price', label: 'Precio unitario', align: 'right' },
    { key: 'discount', label: 'Descuento' },
    { key: 'list', label: 'Lista' },
    { key: 'total', label: 'Total', align: 'right' },
  ]
  const paymentRows: Row[] = payments.map((payment) => ({
    id: payment.id,
    method: paymentName[payment.method] ?? payment.method,
    amount: money(payment.amount),
    reference: payment.transferReference || '—',
    date: date(payment.date),
  }))
  const paymentColumns: TableColumn[] = [
    { key: 'method', label: 'Medio' },
    { key: 'amount', label: 'Importe', align: 'right' },
    { key: 'reference', label: 'Referencia' },
    { key: 'date', label: 'Fecha' },
  ]

  return <>
    <div className="order-customer-header">
      <PageHeader title={order.customer} description={<span className="flex flex-wrap items-center gap-x-[8px] gap-y-[4px]">
        <span>{`Pedido ${order.number} · ${date(order.date)}`}</span>
        <Badge tone={order.status === 'DELIVERED' ? 'strong' : order.status === 'CANCELLED' ? 'muted' : 'soft'}>
          <span className="sr-only">Estado del pedido: </span>{orderStatusLabel(order.status)}
        </Badge>
      </span>} actions={<div className="page-actions"><Button variant="secondary" href={readOrdersReturnPath()}>Volver a pedidos</Button>{canEdit && <Button onClick={() => setEditing(true)}>Editar pedido</Button>}{hasPermission('ORDER_EDIT') && order.status === 'CONFIRMED' && !samePriceList && <span className="helper-text">Este pedido usa listas distintas entre líneas y no puede editarse con una única lista.</span>}</div>} />
    </div>
    {feedback && <p className="success-text" role="status"><CurrencyText>{feedback}</CurrencyText></p>}
    <Panel>
      <div className="order-detail-sections">
        <OrderLifecycleActions orderId={order.id} orderNumber={order.number} customerId={order.customerId} orderStatus={order.status} saleBalance={sale.balance}
          previousDebtAvailable={sale.previousDebtAvailable} accountKind={order.accountKind} onChanged={() => { void query.refetch() }}
          statusBadges={<>
            {order.status !== 'CANCELLED' && <Badge tone={order.fulfillmentStatus === 'PREPARED' ? 'strong' : 'soft'}>{order.fulfillmentStatus === 'PREPARED' ? 'Preparado' : 'A preparar'}</Badge>}
            {isAdmin && <OrderReviewBadge pending={order.reviewPending} />}
          </>}
          notes={<>
            {order.legacyFulfillment && <p className="helper-text">Pedido histórico: el stock ya se descontó al confirmar.</p>}
            {order.preparedAt && <p className="helper-text">Preparado el {date(order.preparedAt)}.</p>}
            {order.reviewedAt && <p className="helper-text">Revisión administrativa confirmada el {date(order.reviewedAt)}.</p>}
          </>}>
          <OrderOperationalActions order={order} onSaved={setFeedback} />
        </OrderLifecycleActions>
        <Panel title="Productos del pedido"><DataTable columns={itemColumns} rows={itemRows} /></Panel>
        {deliveryAttempts.some((attempt) => attempt.result === 'FAILED') && <Panel title="Intentos de entrega">
          <DataTable
            columns={[
              { key: 'attempt', label: 'Intento', emphasis: true },
              { key: 'result', label: 'Resultado', render: (value) => <Badge tone={value === 'DELIVERED' ? 'strong' : 'muted'}>{value === 'DELIVERED' ? 'Entregada' : 'No entregada'}</Badge> },
              { key: 'date', label: 'Fecha' },
              { key: 'observation', label: 'Observación' },
            ]}
            rows={deliveryAttempts.map((attempt) => ({ id: attempt.id, attempt: String(attempt.attemptNumber), result: attempt.result, date: date(attempt.attemptedAt), observation: attempt.observation || '—' }))}
          />
        </Panel>}
        <div className="content-grid two-thirds order-detail-financials">
          <Panel title="Pagos registrados">{paymentRows.length ? <DataTable className="compact-list-table min-w-0" columns={paymentColumns} rows={paymentRows} /> : <EmptyState compact title="Todavía no hay pagos" description={resellerAccount ? `El saldo permanece en la cuenta corriente de ${order.accountCustomer}.` : 'El saldo permanece en la cuenta corriente del cliente.'} />}</Panel>
          <Panel title="Detalle del pedido" description={resellerAccount ? `Titular de la deuda: ${order.accountCustomer} (revendedor)` : undefined}>
            <dl className="order-totals m-0 max-w-none">
              <dt>Subtotal</dt><dd><CurrencyText>{money(order.subtotal)}</CurrencyText></dd>
              <dt>Descuento general</dt><dd>{formatNumber(order.orderDiscountPercent ?? 0, 4)}%</dd>
              <dt>Descuento total</dt><dd><CurrencyText>{money(order.discount ? -order.discount : 0)}</CurrencyText></dd>
              {includesPreviousBalance && <><dt>Saldo anterior de cuenta corriente</dt><dd><CurrencyText>{money(previousBalance)}</CurrencyText></dd></>}
              <dt className="total-label">{includesPreviousBalance ? 'Total a cobrar' : 'Total'}</dt>
              <dd className="total-value"><CurrencyText>{money(total)}</CurrencyText></dd>
            </dl>
          </Panel>
        </div>
      </div>
    </Panel>
  </>
}
