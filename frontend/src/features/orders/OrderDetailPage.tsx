import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { apiGet, apiPut, ApiError } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import OrderLifecycleActions from './OrderLifecycleActions'

type OrderItem = { productId: string; productName: string; quantity: number; unitPrice: number; lineTotal: number; priceListId: string; priceListCode: string; lineDiscountPercent: number }
type Payment = { id: string; amount: number; method: string; transferReference?: string | null; date: string }
type DeliveryAttempt = { id: string; attemptNumber: number; result: 'DELIVERED' | 'FAILED'; observation?: string | null; attemptedAt: string; attemptedBy: string }
type OrderDetail = {
  order: { id: string; number: string; customerId: string; customer: string; status: string; subtotal: number; discount: number; total: number; customerBalance: number; date: string }
  items: OrderItem[]
  sale: { id: string; number: string; status: string; total: number; paid: number; balance: number; date: string }
  payments: Payment[]
  deliveryAttempts: DeliveryAttempt[]
  account: { debit: number; credit: number; net: number }
}
type EditLine = { productId: string; productName: string; quantity: string; lineDiscountPercent: string }
type Row = Record<string, string>

const money = (value: unknown) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
const date = (value: string) => new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
const paymentName: Record<string, string> = { CASH: 'Efectivo', BANK_TRANSFER: 'Transferencia', CUSTOMER_ACCOUNT: 'Cuenta corriente' }

export default function OrderDetailPage() {
  const { orderId = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const queryKey = [`/api/orders/${orderId}`]
  const query = useQuery({ queryKey, queryFn: () => apiGet<OrderDetail>(`/api/orders/${orderId}`), enabled: Boolean(orderId) })
  const [editing, setEditing] = useState(false)
  const [editLines, setEditLines] = useState<EditLine[]>([])
  const [orderDiscountPercent, setOrderDiscountPercent] = useState('0')
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)

  if (query.isLoading) return <><PageHeader eyebrow="Operación" title="Pedido" description="Consultando pedido, venta y cobros." /><Panel><EmptyState title="Cargando pedido" description="Consultando snapshots comerciales." /></Panel></>
  if (query.isError || !query.data) return <><PageHeader eyebrow="Operación" title="Pedido" description="No se pudo abrir el detalle." /><Panel><EmptyState title="No se pudo cargar el pedido" description={query.error?.message ?? 'Revisá el número o el acceso al pedido.'} action={<Button variant="secondary" href="/orders">Volver a pedidos</Button>} /></Panel></>

  const { order, items, sale, payments, account, deliveryAttempts = [] } = query.data
  const commonListId = items[0]?.priceListId
  const samePriceList = items.every((item) => item.priceListId === commonListId)
  const hasSavedDiscount = Number(order.discount ?? 0) !== 0 || items.some((item) => Number(item.lineDiscountPercent ?? 0) !== 0)
  const canEdit = isAdmin && order.status === 'CONFIRMED' && sale.status === 'CONFIRMED' && samePriceList && !hasSavedDiscount

  function startEditing() {
    setEditLines(items.map((item) => ({ productId: item.productId, productName: item.productName, quantity: String(item.quantity), lineDiscountPercent: String(item.lineDiscountPercent ?? 0) })))
    setOrderDiscountPercent('0')
    setError('')
    setEditing(true)
  }

  async function saveEdit(event: FormEvent) {
    event.preventDefault()
    if (saving || !editLines.length) return
    const lines = [] as Array<{ productId: string; quantity: number; lineDiscountPercent: number }>
    for (const line of editLines) {
      const quantity = Number(line.quantity)
      const lineDiscount = Number(line.lineDiscountPercent || 0)
      if (!Number.isFinite(quantity) || quantity <= 0 || !Number.isFinite(lineDiscount) || lineDiscount < 0 || lineDiscount > 100) {
        setError(`Revisá cantidad y descuento de ${line.productName}.`)
        return
      }
      lines.push({ productId: line.productId, quantity, lineDiscountPercent: lineDiscount })
    }
    const orderDiscount = Number(orderDiscountPercent)
    if (!Number.isFinite(orderDiscount) || orderDiscount < 0 || orderDiscount > 100) {
      setError('El descuento general debe estar entre 0 y 100%.')
      return
    }
    setSaving(true)
    setError('')
    try {
      await apiPut(`/api/orders/${order.id}`, { priceListId: commonListId, lines, orderDiscountPercent: orderDiscount })
      await queryClient.invalidateQueries({ queryKey })
      await queryClient.invalidateQueries({ queryKey: ['/api/orders'] })
      await queryClient.invalidateQueries({ queryKey: ['/api/sales'] })
      setEditing(false)
      setFeedback('Pedido actualizado. Se conservaron los cobros existentes.')
    } catch (cause) {
      const status = cause instanceof ApiError ? cause.status : undefined
      setError(status === 403 ? 'No tenés permiso para editar este pedido.' : status === 409 ? cause instanceof Error ? cause.message : 'El stock o el estado cambió.' : cause instanceof Error ? cause.message : 'No se pudo actualizar el pedido.')
    } finally {
      setSaving(false)
    }
  }

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
    <PageHeader eyebrow="Operación" title={order.number} description={`${order.customer} · ${date(order.date)}`} actions={<div className="page-actions"><Button variant="secondary" href="/orders">Volver a pedidos</Button>{canEdit && !editing && <Button onClick={startEditing}>Editar pedido</Button>}{isAdmin && order.status === 'CONFIRMED' && !canEdit && <span className="helper-text">No se puede editar desde el frontend porque este pedido contiene descuentos o usa listas distintas entre líneas.</span>}</div>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
    <div className="stats-grid compact">
      <article className="stat-card"><span>Estado del pedido</span><strong><Badge tone="soft">{order.status}</Badge></strong><small>{order.customer}</small></article>
      <article className="stat-card"><span>Total de la venta</span><strong>{money(sale.total)}</strong><small>Venta {sale.number}</small></article>
      <article className="stat-card"><span>Saldo de la venta</span><strong>{money(sale.balance)}</strong><small>{money(sale.paid)} cobrados</small></article>
    </div>
    {editing ? <Panel title="Editar pedido confirmado" description="Los precios se vuelven a resolver; los pagos ya registrados se conservan."><form className="form-grid" onSubmit={saveEdit}>
      <label className="field"><span>Descuento general (%)</span><input className="input" type="text" inputMode="decimal" value={orderDiscountPercent} onChange={(event) => setOrderDiscountPercent(event.target.value)} disabled={saving} /></label>
      {editLines.map((line) => <div className="order-line-fields" key={line.productId}>
        <strong>{line.productName}</strong>
        <label className="field"><span>Cantidad de {line.productName}</span><input className="input" type="text" inputMode="decimal" value={line.quantity} onChange={(event) => setEditLines((current) => current.map((item) => item.productId === line.productId ? { ...item, quantity: event.target.value } : item))} disabled={saving} /></label>
        <label className="field"><span>Descuento de {line.productName} (%)</span><input className="input" type="text" inputMode="decimal" value={line.lineDiscountPercent} onChange={(event) => setEditLines((current) => current.map((item) => item.productId === line.productId ? { ...item, lineDiscountPercent: event.target.value } : item))} disabled={saving} /></label>
      </div>)}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setEditing(false)} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Guardar cambios'}</Button></div>
    </form></Panel> : <>
      <Panel title="Productos del pedido"><DataTable columns={itemColumns} rows={itemRows} /></Panel>
      <Panel title="Intentos de entrega">
        {deliveryAttempts.length === 0 ? <EmptyState title="Todavía no hay intentos" description="Las visitas de entrega registradas aparecerán acá." /> : <DataTable
          columns={[
            { key: 'attempt', label: 'Intento', emphasis: true },
            { key: 'result', label: 'Resultado', render: (value) => <Badge tone={value === 'DELIVERED' ? 'strong' : 'muted'}>{value === 'DELIVERED' ? 'Entregada' : 'No entregada'}</Badge> },
            { key: 'date', label: 'Fecha' },
            { key: 'observation', label: 'Observación' },
          ]}
          rows={deliveryAttempts.map((attempt) => ({ id: attempt.id, attempt: String(attempt.attemptNumber), result: attempt.result, date: date(attempt.attemptedAt), observation: attempt.observation || '—' }))}
        />}
      </Panel>
      <div className="content-grid two-thirds">
        <Panel title="Pagos registrados">{paymentRows.length ? <DataTable columns={paymentColumns} rows={paymentRows} /> : <EmptyState title="Todavía no hay pagos" description="El saldo permanece en la cuenta corriente del cliente." />}</Panel>
        <Panel title="Cuenta corriente"><dl className="account-ledger"><dt>Débitos de esta venta</dt><dd>{money(account.debit)}</dd><dt>Créditos aplicados</dt><dd>{money(account.credit)}</dd><dt>Saldo pendiente</dt><dd>{money(account.net)}</dd><dt>Saldo total del cliente</dt><dd>{money(order.customerBalance)}</dd></dl></Panel>
      </div>
      <Panel title="Resumen"><dl className="order-totals"><dt>Subtotal</dt><dd>{money(order.subtotal)}</dd><dt>Descuentos</dt><dd>{money(order.discount)}</dd><dt className="total-label">Total</dt><dd className="total-value">{money(order.total)}</dd></dl><p className="helper-text">Lista(s) usada(s): {Array.from(new Set(items.map((item) => item.priceListCode))).join(', ') || '—'}</p></Panel>
      <OrderLifecycleActions orderId={order.id} orderNumber={order.number} orderStatus={order.status} saleBalance={sale.balance} onChanged={() => { void query.refetch() }} />
    </>}
  </>
}
