import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type Customer = { id: string; name: string; sellerId?: string | null; seller?: string | null; priceListId?: string | null; balance?: number }
type Product = { id: string; sku: string; name: string; presentation: string; stock?: number; status?: string }
type PriceList = { id: string; code: string; name: string; status: string }
type Seller = { id: string; displayName: string; email: string }
type PriceResolution = { productId: string; priceListId: string; priceListCode: string; unitPrice: number }
type DraftLine = { productId: string; quantity: string; lineDiscountPercent: string; unitPriceOverride: string }
type DraftPayment = { method: 'CASH' | 'BANK_TRANSFER' | 'CUSTOMER_ACCOUNT'; amount: string }
type ConfirmationRequest = {
  idempotencyKey: string
  customerId: string
  sellerId?: string | null
  priceListId: string
  lines: Array<{ productId: string; quantity: number; lineDiscountPercent: number; unitPriceOverride?: number }>
  orderDiscountPercent: number
  payments: Array<{ method: DraftPayment['method']; amount: number }>
}
type ConfirmationResponse = {
  orderId: string
  saleId: string
  orderNumber: string
  saleNumber: string
  total: number
  paid: number
  balance: number
  creditLimitWarning?: { creditLimit: number; projectedBalance: number; exceededBy: number } | null
}

const CUSTOMER_KEY = ['/api/customers?page=0&size=20']
const PRODUCT_KEY = ['/api/products?page=0&size=20']
const PRICE_LIST_KEY = ['/api/pricing/lists?page=0&size=20']
const SELLER_KEY = ['/api/sellers?page=0&size=100']

function money(value: number) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)
}

function message(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.status === 400) return cause.detail || 'Revisá cantidades, pagos y descuentos.'
    if (cause.status === 403) return 'Tu usuario no tiene permiso para confirmar este pedido.'
    if (cause.status === 404) return cause.detail || 'Un cliente, producto o lista dejó de estar disponible.'
    if (cause.status === 409) return cause.detail || 'El stock, el precio o la clave de confirmación entró en conflicto.'
  }
  return cause instanceof Error ? cause.message : 'No se pudo confirmar el pedido.'
}

function newKey() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

export default function OrderCreatePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const customersQuery = useQuery({ queryKey: CUSTOMER_KEY, queryFn: () => apiGet<ApiPage<Customer>>('/api/customers?page=0&size=20') })
  const productsQuery = useQuery({ queryKey: PRODUCT_KEY, queryFn: () => apiGet<ApiPage<Product>>('/api/products?page=0&size=20') })
  const listsQuery = useQuery({ queryKey: PRICE_LIST_KEY, queryFn: () => apiGet<ApiPage<PriceList>>('/api/pricing/lists?page=0&size=20') })
  const sellersQuery = useQuery({ queryKey: SELLER_KEY, queryFn: () => apiGet<ApiPage<Seller>>('/api/sellers?page=0&size=100'), enabled: isAdmin })
  const customers = customersQuery.data?.content ?? []
  const products = productsQuery.data?.content ?? []
  const activeLists = (listsQuery.data?.content ?? []).filter((list) => list.status === 'ACTIVE')
  const [customerId, setCustomerId] = useState('')
  const [sellerId, setSellerId] = useState('')
  const [explicitListId, setExplicitListId] = useState('')
  const [lines, setLines] = useState<DraftLine[]>([])
  const [selectedProductId, setSelectedProductId] = useState('')
  const [payments, setPayments] = useState<DraftPayment[]>([{ method: 'CASH', amount: '' }])
  const [orderDiscountPercent, setOrderDiscountPercent] = useState('0')
  const [selectedProductPriceOverride, setSelectedProductPriceOverride] = useState('')
  const [attempt, setAttempt] = useState<ConfirmationRequest | undefined>()
  const [responseData, setResponseData] = useState<ConfirmationResponse | undefined>()
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const customer = customers.find((item) => item.id === customerId)
  const resolvedListId = explicitListId || customer?.priceListId || ''
  const selectedList = activeLists.find((list) => list.id === resolvedListId)
  const resolutions = useQueries({
    queries: lines.map((line) => ({
      queryKey: ['order-price-resolution', customerId, resolvedListId, line.productId],
      queryFn: () => apiGet<PriceResolution>(`/api/pricing/resolve?customerId=${encodeURIComponent(customerId)}&productId=${encodeURIComponent(line.productId)}&priceListId=${encodeURIComponent(resolvedListId)}`),
      enabled: Boolean(customerId && resolvedListId),
      retry: false,
    })),
  })
  const productsById = useMemo(() => new Map(products.map((product) => [product.id, product])), [products])
  const previewSubtotal = lines.reduce((sum, line, index) => {
    const quantity = Number(line.quantity)
    const unitPrice = Number(line.unitPriceOverride || resolutions[index]?.data?.unitPrice || 0)
    const discount = isAdmin ? Number(line.lineDiscountPercent || 0) : 0
    if (!Number.isFinite(quantity) || !Number.isFinite(unitPrice)) return sum
    return sum + quantity * unitPrice * (1 - discount / 100)
  }, 0)
  const previewDiscount = isAdmin ? previewSubtotal * (Number(orderDiscountPercent || 0) / 100) : 0
  const previewTotal = Math.max(0, previewSubtotal - previewDiscount)
  const hasUnsavedDraft = Boolean(customerId || lines.length || payments.some((payment) => payment.amount.trim()))

  useEffect(() => {
    if (!hasUnsavedDraft || responseData) return
    function warnBeforeLeave(event: BeforeUnloadEvent) {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warnBeforeLeave)
    return () => window.removeEventListener('beforeunload', warnBeforeLeave)
  }, [hasUnsavedDraft, responseData])

  function draftChanged() {
    setAttempt(undefined)
    setError('')
  }

  function selectCustomer(selectedCustomerId: string) {
    const selectedCustomer = customers.find((item) => item.id === selectedCustomerId)
    draftChanged()
    setCustomerId(selectedCustomerId)
    setSellerId(selectedCustomer?.sellerId ?? '')
    setExplicitListId(selectedCustomer?.priceListId ?? '')
  }

  function addProduct() {
    if (!selectedProductId || lines.some((line) => line.productId === selectedProductId)) return
    draftChanged()
    setLines((current) => [...current, { productId: selectedProductId, quantity: '1', lineDiscountPercent: '0', unitPriceOverride: isAdmin ? selectedProductPriceOverride : '' }])
    setSelectedProductId('')
    setSelectedProductPriceOverride('')
  }

  function updateLine(productId: string, field: keyof Omit<DraftLine, 'productId'>, value: string) {
    draftChanged()
    setLines((current) => current.map((line) => line.productId === productId ? { ...line, [field]: value } : line))
  }

  function removeLine(productId: string) {
    draftChanged()
    setLines((current) => current.filter((line) => line.productId !== productId))
  }

  function updatePayment(index: number, field: keyof DraftPayment, value: string) {
    draftChanged()
    setPayments((current) => current.map((payment, paymentIndex) => paymentIndex === index ? { ...payment, [field]: value } : payment))
  }

  function buildPayload(): ConfirmationRequest | undefined {
    if (!customerId) { setError('Seleccioná un cliente.'); return undefined }
    if (!resolvedListId) { setError('Seleccioná una lista de precios.'); return undefined }
    if (lines.length === 0) { setError('Agregá al menos un producto al pedido.'); return undefined }
    if (resolutions.some((resolution) => resolution.isLoading)) { setError('Esperá a que se resuelvan los precios antes de confirmar.'); return undefined }
    if (resolutions.some((resolution) => resolution.isError || !resolution.data)) { setError('No se pudo resolver el precio de una línea. Revisá la lista y los productos.'); return undefined }

    const payloadLines: ConfirmationRequest['lines'] = []
    for (const [index, line] of lines.entries()) {
      const quantity = Number(line.quantity)
      const discount = isAdmin ? Number(line.lineDiscountPercent || 0) : 0
      const override = isAdmin && line.unitPriceOverride.trim() ? Number(line.unitPriceOverride) : undefined
      if (!Number.isFinite(quantity) || quantity <= 0) { setError(`Ingresá una cantidad válida para ${productsById.get(line.productId)?.name ?? 'el producto'}.`); return undefined }
      if (!Number.isFinite(discount) || discount < 0 || discount > 100) { setError('Los descuentos deben estar entre 0 y 100%.'); return undefined }
      if (override !== undefined && (!Number.isFinite(override) || override < 0)) { setError('El precio manual debe ser un número no negativo.'); return undefined }
      payloadLines.push({ productId: line.productId, quantity, lineDiscountPercent: discount, ...(override !== undefined ? { unitPriceOverride: override } : {}) })
      if (resolutions[index].data?.unitPrice === undefined) { setError('Falta resolver el precio del producto.'); return undefined }
    }

    const payloadPayments: ConfirmationRequest['payments'] = []
    for (const payment of payments) {
      if (!payment.amount.trim()) continue
      const amount = Number(payment.amount)
      if (!Number.isFinite(amount) || amount <= 0) { setError('Cada importe cobrado debe ser mayor a cero.'); return undefined }
      payloadPayments.push({ method: payment.method, amount })
    }
    const discount = isAdmin ? Number(orderDiscountPercent || 0) : 0
    if (!Number.isFinite(discount) || discount < 0 || discount > 100) { setError('El descuento general debe estar entre 0 y 100%.'); return undefined }
    return { idempotencyKey: newKey(), customerId, ...(isAdmin ? { sellerId: sellerId || null } : {}), priceListId: resolvedListId, lines: payloadLines, orderDiscountPercent: discount, payments: payloadPayments }
  }

  async function confirm(event: FormEvent) {
    event.preventDefault()
    if (submitting || responseData) return
    setError('')
    const payload = attempt ?? buildPayload()
    if (!payload) return
    if (!attempt) setAttempt(payload)
    setSubmitting(true)
    try {
      const result = await apiPost<ConfirmationResponse>('/api/orders/confirm', payload)
      setResponseData(result)
      setAttempt(undefined)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['/api/orders'] }),
        queryClient.invalidateQueries({ queryKey: ['/api/sales'] }),
        queryClient.invalidateQueries({ queryKey: ['/api/payments'] }),
        queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
        queryClient.invalidateQueries({
          predicate: (query) => typeof query.queryKey[0] === 'string' && query.queryKey[0].startsWith('/api/inventory'),
        }),
      ])
    } catch (cause) {
      setError(message(cause))
    } finally {
      setSubmitting(false)
    }
  }

  if (responseData) return <>
    <PageHeader eyebrow="Pedido confirmado" title={responseData.orderNumber} description="El pedido y la venta quedaron registrados." />
    <Panel title="Resultado de la confirmación">
      <dl className="confirmation-result"><dt>Venta</dt><dd>{responseData.saleNumber}</dd><dt>Total</dt><dd>{money(responseData.total)}</dd><dt>Cobrado</dt><dd>{money(responseData.paid)}</dd><dt>Saldo pendiente</dt><dd>{money(responseData.balance)}</dd></dl>
      {responseData.creditLimitWarning && <p className="warning-text" role="status">El saldo proyectado {money(responseData.creditLimitWarning.projectedBalance)} supera el límite de crédito {money(responseData.creditLimitWarning.creditLimit)} por {money(responseData.creditLimitWarning.exceededBy)}.</p>}
      <div className="page-actions"><Button href={`/orders/${responseData.orderId}`}>Ver pedido</Button><Button variant="secondary" onClick={() => navigate('/orders')}>Volver a pedidos</Button></div>
    </Panel>
  </>

  const readError = customersQuery.error ?? productsQuery.error ?? listsQuery.error ?? (isAdmin ? sellersQuery.error : null)
  const readLoading = customersQuery.isLoading || productsQuery.isLoading || listsQuery.isLoading || (isAdmin && sellersQuery.isLoading)

  return <>
    <PageHeader eyebrow="Operación" title="Nuevo pedido" actions={<Button variant="secondary" href="/orders">Cancelar</Button>} />
    {readLoading ? <Panel><EmptyState title="Cargando datos del pedido" description={isAdmin ? 'Consultando clientes, productos, vendedores y listas activas.' : 'Consultando clientes, productos y listas activas.'} /></Panel>
      : readError ? <Panel><EmptyState title="No se pudo preparar el pedido" description={readError.message} action={isAdmin && sellersQuery.isError && readError === sellersQuery.error ? <Button variant="secondary" onClick={() => sellersQuery.refetch()}>Reintentar vendedores</Button> : undefined} /></Panel>
        : <form onSubmit={confirm}>
          {error && <p className="error-text" role="alert">{error}</p>}
          <div className="order-layout">
            <div className="order-main">
              <Panel title="Datos del pedido">
                <div className="order-customer-grid">
                  <label className="field"><span>Cliente</span><select className="select" value={customerId} onChange={(event) => selectCustomer(event.target.value)} required><option value="">Seleccionar cliente...</option>{customers.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label>
                  {isAdmin
                    ? <label className="field"><span>Vendedor</span><select className="select" aria-label="Vendedor" value={sellerId} onChange={(event) => { draftChanged(); setSellerId(event.target.value) }}><option value="">Usar vendedor del cliente</option>{(sellersQuery.data?.content ?? []).map((seller) => <option value={seller.id} key={seller.id}>{seller.displayName}</option>)}</select></label>
                    : <div className="field"><span>Vendedor</span><span className="read-only-field" aria-label="Vendedor">{customer?.seller || 'Seleccioná un cliente'}</span></div>}
                  <label className="field"><span>Lista de precios</span><select className="select" value={resolvedListId} onChange={(event) => { draftChanged(); setExplicitListId(event.target.value) }} required><option value="">Seleccionar lista...</option>{activeLists.map((list) => <option value={list.id} key={list.id}>{list.code} · {list.name}</option>)}</select></label>
                </div>
                {customer && <p className="helper-text">Saldo de cuenta corriente actual: {money(Number(customer.balance ?? 0))}</p>}
                <div className="section-heading"><div><h3>Productos</h3><p>Los precios se resuelven para el cliente y la lista seleccionada.</p></div></div>
                <div className="product-picker"><label className="field"><span>Producto</span><select className="select" value={selectedProductId} onChange={(event) => { setSelectedProductId(event.target.value); setSelectedProductPriceOverride('') }} disabled={!customerId || !resolvedListId}><option value="">Seleccionar producto...</option>{products.filter((item) => item.status === 'ACTIVE').map((item) => <option value={item.id} key={item.id}>{item.name} · {item.sku} · stock total {item.stock ?? 0}</option>)}</select></label>{isAdmin && <label className="field"><span>Precio manual para el producto</span><input className="input" type="text" inputMode="decimal" value={selectedProductPriceOverride} onChange={(event) => setSelectedProductPriceOverride(event.target.value)} disabled={!selectedProductId} /></label>}<Button type="button" variant="secondary" onClick={addProduct} disabled={!selectedProductId}>Agregar producto</Button></div>
                {lines.length === 0 ? <EmptyState title="Todavía no agregaste productos" description="Elegí un producto para consultar su precio en la lista seleccionada." /> : <div className="order-lines">{lines.map((line, index) => {
                  const product = productsById.get(line.productId)
                  const resolution = resolutions[index]
                  const unitPrice = Number(line.unitPriceOverride || resolution?.data?.unitPrice || 0)
                  const quantity = Number(line.quantity || 0)
                  const discount = isAdmin ? Number(line.lineDiscountPercent || 0) : 0
                  const lineTotal = unitPrice * quantity * (1 - discount / 100)
                  return <article className="order-line" key={line.productId}>
                     <div className="order-line-heading"><div><strong>{product?.name}</strong><small>{product?.sku} · {product?.presentation} · stock total {product?.stock ?? 0}</small></div><Button variant="link" type="button" onClick={() => removeLine(line.productId)}>Quitar</Button></div>
                    <div className="order-line-fields">
                      <label className="field"><span>Cantidad de {product?.name}</span><input className="input" type="text" inputMode="decimal" value={line.quantity} onChange={(event) => updateLine(line.productId, 'quantity', event.target.value)} /></label>
                      <div className="field"><span>Precio de {resolution?.data?.priceListCode ?? selectedList?.code ?? 'lista'}</span><strong>{resolution?.isLoading ? 'Resolviendo...' : resolution?.isError ? 'No disponible' : money(unitPrice)}</strong></div>
                      {isAdmin && <label className="field"><span>Descuento de línea (%)</span><input className="input" type="text" inputMode="decimal" value={line.lineDiscountPercent} onChange={(event) => updateLine(line.productId, 'lineDiscountPercent', event.target.value)} /></label>}
                      <div className="field"><span>Total de línea (preview)</span><strong>{money(lineTotal)}</strong></div>
                    </div>
                  </article>
                })}</div>}
                {isAdmin && <label className="field"><span>Descuento general (%)</span><input className="input" type="text" inputMode="decimal" value={orderDiscountPercent} onChange={(event) => { draftChanged(); setOrderDiscountPercent(event.target.value) }} /></label>}
              </Panel>
            </div>
            <aside className="order-summary">
              <Panel title="Cobro" description="Podés registrar más de un medio o dejar el saldo en cuenta corriente.">
                {payments.map((payment, index) => <div className="payment-entry" key={index}>
                  <label className="field"><span>Medio de pago</span><select className="select" value={payment.method} onChange={(event) => updatePayment(index, 'method', event.target.value as DraftPayment['method'])}><option value="CASH">Efectivo</option><option value="BANK_TRANSFER">Transferencia</option><option value="CUSTOMER_ACCOUNT">Cuenta corriente</option></select></label>
                  <label className="field"><span>Importe de pago</span><input className="input" type="text" inputMode="decimal" value={payment.amount} onChange={(event) => updatePayment(index, 'amount', event.target.value)} /></label>
                  {payments.length > 1 && <Button type="button" variant="link" onClick={() => { draftChanged(); setPayments((current) => current.filter((_item, itemIndex) => itemIndex !== index)) }}>Quitar medio</Button>}
                </div>)}
                <Button type="button" variant="secondary" onClick={() => { draftChanged(); setPayments((current) => [...current, { method: 'CASH', amount: '' }]) }}>Agregar medio de pago</Button>
                <dl className="order-totals"><dt>Subtotal (preview)</dt><dd>{money(previewSubtotal)}</dd>{isAdmin && <><dt>Descuento general (preview)</dt><dd>{money(previewDiscount)}</dd></>}<dt className="total-label">Total estimado</dt><dd className="total-value">{money(previewTotal)}</dd></dl>
                <Button fullWidth disabled={submitting || resolutions.some((resolution) => resolution.isLoading) || lines.length === 0}>{submitting ? 'Confirmando...' : attempt ? 'Reintentar confirmación' : 'Confirmar pedido'}</Button>
                <p className="helper-text">El total definitivo, el stock, los descuentos y el límite de crédito se validan en el servidor.</p>
              </Panel>
            </aside>
          </div>
        </form>}
  </>
}
