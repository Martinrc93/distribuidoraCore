import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { apiGet } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import OrderLifecycleActions from './OrderLifecycleActions'
import OrderForm from './OrderForm'

type OrderItem = { productId: string; productName: string; quantity: number; unitPrice: number; lineTotal: number; priceListId: string; priceListCode: string; lineDiscountPercent: number }
type Payment = { id: string; amount: number; method: string; transferReference?: string | null; date: string }
type DeliveryAttempt = { id: string; attemptNumber: number; result: 'DELIVERED' | 'FAILED'; observation?: string | null; attemptedAt: string; attemptedBy: string }
type OrderDetail = {
  order: { id: string; number: string; customerId: string; customer: string; seller?: string; status: string; subtotal: number; discount: number; orderDiscountPercent?: number; total: number; customerBalance: number; date: string; previousBalanceAmount?: number; collectionTotal?: number }
  items: OrderItem[]
  sale: { id: string; number: string; status: string; total: number; paid: number; balance: number; date: string }
  payments: Payment[]
  deliveryAttempts: DeliveryAttempt[]
  account: { debit: number; credit: number; net: number }
}
type Row = Record<string, string>

const money = (value: unknown) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
const date = (value: string) => new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
const paymentName: Record<string, string> = { CASH: 'Efectivo', BANK_TRANSFER: 'Transferencia', CUSTOMER_ACCOUNT: 'Cuenta corriente' }

export default function OrderDetailPage() {
  const { orderId = '' } = useParams()
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryKey = [`/api/orders/${orderId}`]
  const query = useQuery({ queryKey, queryFn: () => apiGet<OrderDetail>(`/api/orders/${orderId}`), enabled: Boolean(orderId) })
  const [params, setParams] = useSearchParams()
  const editing = params.get('edit') === 'true'
  const [feedback, setFeedback] = useState('')

  function setEditing(value: boolean) {
    const next = new URLSearchParams(params)
    if (value) next.set('edit', 'true')
    else next.delete('edit')
    setParams(next, { replace: true })
  }

  if (query.isLoading) return <><PageHeader eyebrow="Operación" title="Pedido" description="Consultando pedido, venta y cobros." /><Panel><EmptyState title="Cargando pedido" description="Consultando snapshots comerciales." /></Panel></>
  if (query.isError || !query.data) return <><PageHeader eyebrow="Operación" title="Pedido" description="No se pudo abrir el detalle." /><Panel><EmptyState title="No se pudo cargar el pedido" description={query.error?.message ?? 'Revisá el número o el acceso al pedido.'} action={<Button variant="secondary" href="/orders">Volver a pedidos</Button>} /></Panel></>

  const { order, items, sale, payments, account, deliveryAttempts = [] } = query.data
  const commonListId = items[0]?.priceListId
  const samePriceList = items.every((item) => item.priceListId === commonListId)
  const canEdit = isAdmin && order.status === 'CONFIRMED' && sale.status === 'CONFIRMED' && samePriceList

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
    <PageHeader eyebrow="Operación" title={order.number} description={`${order.customer} · ${date(order.date)}`} actions={<div className="page-actions"><Button variant="secondary" href="/orders">Volver a pedidos</Button>{canEdit && <Button onClick={() => setEditing(true)}>Editar pedido</Button>}{isAdmin && order.status === 'CONFIRMED' && !samePriceList && <span className="helper-text">Este pedido usa listas distintas entre líneas y no puede editarse con una única lista.</span>}</div>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {Number(order.previousBalanceAmount ?? 0) > 0 && <Panel title="Importe para la entrega"><dl className="confirmation-result"><dt>Total del pedido</dt><dd>{money(order.total)}</dd><dt>Saldo anterior incluido</dt><dd>{money(order.previousBalanceAmount)}</dd><dt>Total a cobrar</dt><dd>{money(order.collectionTotal ?? order.total + Number(order.previousBalanceAmount))}</dd></dl></Panel>}
    <div className="stats-grid compact">
      <article className="stat-card"><span>Estado del pedido</span><strong><Badge tone="soft">{order.status}</Badge></strong><small>{order.customer}</small></article>
      <article className="stat-card"><span>Total de la venta</span><strong>{money(sale.total)}</strong><small>Venta {sale.number}</small></article>
      <article className="stat-card"><span>Saldo de la venta</span><strong>{money(sale.balance)}</strong><small>{money(sale.paid)} cobrados</small></article>
    </div>
    <>
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
      <div className="content-grid two-thirds">
        <Panel title="Pagos registrados">{paymentRows.length ? <DataTable columns={paymentColumns} rows={paymentRows} /> : <EmptyState title="Todavía no hay pagos" description="El saldo permanece en la cuenta corriente del cliente." />}</Panel>
        <Panel title="Cuenta corriente"><dl className="account-ledger"><dt>Débitos de esta venta</dt><dd>{money(account.debit)}</dd><dt>Créditos aplicados</dt><dd>{money(account.credit)}</dd><dt>Saldo pendiente</dt><dd>{money(account.net)}</dd><dt>Saldo total del cliente</dt><dd>{money(order.customerBalance)}</dd></dl></Panel>
      </div>
      <Panel title="Resumen"><dl className="order-totals"><dt>Subtotal</dt><dd>{money(order.subtotal)}</dd><dt>Descuentos</dt><dd>{money(order.discount)}</dd><dt className="total-label">Total</dt><dd className="total-value">{money(order.total)}</dd></dl><p className="helper-text">Lista(s) usada(s): {Array.from(new Set(items.map((item) => item.priceListCode))).join(', ') || '—'}</p></Panel>
      <OrderLifecycleActions orderId={order.id} orderNumber={order.number} orderStatus={order.status} saleBalance={sale.balance} onChanged={() => { void query.refetch() }} />
    </>
  </>
}
