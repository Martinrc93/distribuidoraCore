import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useUrlListState } from '../../shared/useUrlListState'

type Payment = { id: string; customer: string; sale: string; amount: number; method: string; transferReference?: string | null; date: string }
type Customer = { id: string; name: string; balance?: number }
type CustomerDebt = { saleId: string; saleNumber: string; orderNumber: string; status: string; balance: number; createdAt: string }
type Allocation = { saleId: string; saleNumber: string; paymentId: string; amount: number }
type PaymentResult = { customerId: string; received: number; balanceBefore: number; balanceAfter: number; allocationMode: 'FIFO' | 'SPECIFIC'; allocations: Allocation[] }
type Row = Record<string, string>

const CUSTOMERS_PATH = '/api/customers?page=0&size=20'
const CUSTOMERS_KEY = [CUSTOMERS_PATH]
const money = (value: unknown) => new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(Number(value ?? 0))
const date = (value: string) => new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
const methodName: Record<string, string> = { CASH: 'Efectivo', BANK_TRANSFER: 'Transferencia' }

export default function PaymentsPage() {
  const canPay = hasAuthority('SALE_PAYMENT') || hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [customerId, setCustomerId] = useState('')
  const [saleId, setSaleId] = useState('')
  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState<'CASH' | 'BANK_TRANSFER'>('CASH')
  const [transferReference, setTransferReference] = useState('')
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [result, setResult] = useState<PaymentResult | undefined>()
  const [saving, setSaving] = useState(false)
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const search = getFilter('search')
  const paymentsPath = `/api/payments?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}`
  const paymentsKey = [paymentsPath]
  const paymentsQuery = useQuery({ queryKey: paymentsKey, queryFn: () => apiGet<ApiPage<Payment>>(paymentsPath) })
  const customersQuery = useQuery({ queryKey: CUSTOMERS_KEY, queryFn: () => apiGet<ApiPage<Customer>>(CUSTOMERS_PATH), enabled: canPay })
  const debtsQuery = useQuery({
    queryKey: ['customer-debts', customerId],
    queryFn: () => apiGet<ApiPage<CustomerDebt>>(`/api/customers/${customerId}/debts?page=0&size=100`),
    enabled: showForm && canPay && Boolean(customerId),
  })
  const customers = customersQuery.data?.content ?? []
  const debts = debtsQuery.data?.content ?? []

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    const value = Number(amount)
    if (!customerId) { setError('Seleccioná un cliente.'); return }
    if (!amount.trim() || !Number.isFinite(value) || value <= 0) { setError('El importe debe ser mayor a cero.'); return }
    setError('')
    setSaving(true)
    try {
      const body = { amount: value, method, ...(saleId ? { saleId } : {}), ...(method === 'BANK_TRANSFER' && transferReference.trim() ? { transferReference: transferReference.trim() } : {}) }
      const paymentResult = await apiPost<PaymentResult>(`/api/customers/${customerId}/account-payments`, body)
      await Promise.all([
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/payments?') }),
        queryClient.invalidateQueries({ queryKey: CUSTOMERS_KEY }),
        queryClient.invalidateQueries({ queryKey: ['customer-debts', customerId] }),
      ])
      setResult(paymentResult)
      setFeedback(paymentResult.allocationMode === 'FIFO' ? 'Pago aplicado por FIFO.' : 'Pago aplicado a la deuda seleccionada.')
      setAmount('')
      setTransferReference('')
      setShowForm(false)
    } catch (cause) {
      setError(cause instanceof ApiError && cause.status === 403 ? 'No tenés permiso para registrar pagos.' : cause instanceof Error ? cause.message : 'No se pudo registrar el pago.')
    } finally {
      setSaving(false)
    }
  }

  const paymentRows: Row[] = (paymentsQuery.data?.content ?? []).map((payment) => ({
    id: payment.id,
    customer: payment.customer,
    sale: payment.sale,
    amount: money(payment.amount),
    method: methodName[payment.method] ?? payment.method,
    transferReference: payment.transferReference || '—',
    date: date(payment.date),
  }))
  const paymentColumns: TableColumn[] = [
    { key: 'customer', label: 'Cliente', emphasis: true },
    { key: 'sale', label: 'Venta' },
    { key: 'amount', label: 'Importe', align: 'right' },
    { key: 'method', label: 'Medio' },
    { key: 'transferReference', label: 'Referencia' },
    { key: 'date', label: 'Fecha' },
  ]
  const allocationRows: Row[] = (result?.allocations ?? []).map((allocation) => ({
    id: allocation.saleId,
    sale: allocation.saleNumber,
    amount: money(allocation.amount),
  }))
  const allocationColumns: TableColumn[] = [{ key: 'sale', label: 'Deuda aplicada', emphasis: true }, { key: 'amount', label: 'Importe aplicado', align: 'right' }]

  return <>
    <PageHeader eyebrow="Operación" title="Pagos y cuenta corriente" description="Registrá cobros y consultá el saldo disponible de cada cliente." actions={canPay ? <Button onClick={() => { setShowForm((value) => !value); setError('') }}>+ Registrar pago</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback} Saldo anterior {money(result?.balanceBefore)} · saldo nuevo {money(result?.balanceAfter)}.</p>}
    {error && <p className="error-text" role="alert">{error}</p>}
    {showForm && canPay && <Panel title="Registrar pago" description="Elegí una deuda específica o aplicá el importe a las deudas más antiguas (FIFO).">
      {customersQuery.isLoading ? <EmptyState title="Cargando clientes" description="Consultando saldos de cuenta corriente." /> : customersQuery.isError ? <EmptyState title="No se pudieron cargar clientes" description={customersQuery.error.message} /> : <form className="form-grid" onSubmit={submit}>
        <label className="field"><span>Cliente del pago</span><select className="select" value={customerId} onChange={(event) => { setCustomerId(event.target.value); setSaleId('') }} required><option value="">Seleccionar cliente...</option>{customers.map((customer) => <option value={customer.id} key={customer.id}>{customer.name} · saldo {money(customer.balance)}</option>)}</select></label>
        <label className="field"><span>Imputación</span><select className="select" value={saleId} onChange={(event) => setSaleId(event.target.value)} disabled={!customerId || debtsQuery.isLoading || debtsQuery.isError}><option value="">FIFO · deuda más antigua</option>{debts.map((debt) => <option value={debt.saleId} key={debt.saleId}>{debt.saleNumber} · saldo {money(debt.balance)}</option>)}</select></label>
        {debtsQuery.isError && <p className="error-text" role="alert">No se pudieron cargar las deudas: {debtsQuery.error.message}</p>}
        {debtsQuery.isLoading && customerId && <p className="helper-text" role="status">Cargando deudas abiertas del cliente…</p>}
        <label className="field"><span>Medio del pago</span><select className="select" value={method} onChange={(event) => setMethod(event.target.value as 'CASH' | 'BANK_TRANSFER')}><option value="CASH">Efectivo</option><option value="BANK_TRANSFER">Transferencia</option></select></label>
        <label className="field"><span>Importe a registrar</span><input className="input" type="text" inputMode="decimal" value={amount} onChange={(event) => setAmount(event.target.value)} required disabled={saving} /></label>
        {method === 'BANK_TRANSFER' && <label className="field"><span>Referencia de transferencia (opcional)</span><input className="input" aria-label="Referencia de transferencia" value={transferReference} onChange={(event) => setTransferReference(event.target.value)} maxLength={100} disabled={saving} /></label>}
        <p className="helper-text">Si no seleccionás una venta, el pago se distribuye por FIFO entre las deudas abiertas.</p>
        <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setShowForm(false)} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Guardar pago'}</Button></div>
      </form>}
    </Panel>}
    {result && allocationRows.length > 0 && <Panel title="Deudas cubiertas por este pago"><DataTable columns={allocationColumns} rows={allocationRows} /></Panel>}
    <Panel>
      <div className="toolbar"><label className="field"><span>Buscar pagos</span><input className="input search-input" aria-label="Buscar pagos" placeholder="Cliente o número de venta" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label></div>
      {paymentsQuery.isLoading ? <EmptyState title="Cargando pagos" description="Consultando los movimientos de pago." /> : paymentsQuery.isError ? <EmptyState title="No se pudieron cargar los pagos" description={paymentsQuery.error.message} /> : paymentRows.length === 0 ? <EmptyState title="Todavía no hay pagos" description="Los cobros registrados aparecerán en este listado." /> : <><DataTable columns={paymentColumns} rows={paymentRows} /><div className="pagination"><span>Página {page + 1} · {paymentsQuery.data?.totalElements ?? 0} pagos</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (paymentsQuery.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
  </>
}
