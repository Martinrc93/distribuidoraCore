import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState, type FormEvent, type MouseEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { apiGet, apiPost, apiPut, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { CustomerSelect, SellerSelect } from '../../shared/components/EntitySelect'
import { SearchableSelect } from '../../shared/components/SearchableSelect'
import { apiGetAllPages } from '../../shared/api/pagination'
import { loadOrderPrices } from './orderPrice'
import { PreviousOrderDiscountDialog } from './PreviousOrderDiscountDialog'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { OrderDateFilter } from './OrderDateFilter'

type Customer = { id: string; name: string; sellerId?: string | null; seller?: string | null; priceListId?: string | null; balance?: number }
type Product = { id: string; name: string; status?: string }
type PreviousOrderItem = { productId: string; productName: string; status: string; quantity: number; lineDiscountPercent: number }
type PreviousOrder = { available: boolean; orderId?: string; orderNumber?: string; orderDiscountPercent?: number; items?: PreviousOrderItem[] }
type PriceList = { id: string; code: string; name: string; status: string }
type Seller = { id: string; displayName: string; email: string }
type DraftLine = { productId: string; quantity: string; lineDiscountPercent: string; unitPriceOverride: string }
export type EditableOrder = {
  order: { id: string; number: string; customerId: string; customer: string; seller?: string; customerBalance: number; orderDiscountPercent?: number; previousBalanceAmount?: number }
  items: Array<{ productId: string; productName: string; quantity: number; unitPrice: number; lineDiscountPercent: number; priceListId: string }>
}
type Props = { editOrder?: EditableOrder; onSaved?: () => Promise<void>; onCancel?: () => void }
type ConfirmationRequest = {
  idempotencyKey: string
  customerId: string
  orderDate?: string
  sellerId?: string | null
  priceListId: string
  lines: Array<{ productId: string; quantity: number; lineDiscountPercent: number; unitPriceOverride?: number }>
  orderDiscountPercent: number
  previousBalanceAmount?: number
  payments: []
}
type ConfirmationResponse = {
  orderId: string
  saleId: string
  orderNumber: string
  saleNumber: string
  total: number
  paid: number
  balance: number
  previousBalanceAmount?: number
  collectionTotal?: number
  creditLimitWarning?: { creditLimit: number; projectedBalance: number; exceededBy: number } | null
}

const CUSTOMER_KEY = ['/api/customers?page=0&size=20']
const PRODUCT_KEY = ['order-create-products', '/api/products?page=0&size=100&includeStock=false']
const PRICE_LIST_KEY = ['/api/pricing/lists?page=0&size=20']
const SELLER_KEY = ['/api/sellers?page=0&size=100']

function money(value: number) {
  return new Intl.NumberFormat('es-AR', { style: 'currency', currency: 'ARS', maximumFractionDigits: 2 }).format(value)
}

function quantityValue(value: string) {
  return Number(value.trim().replace(',', '.'))
}

function currentOrderDate() {
  return new Intl.DateTimeFormat('es-AR', { timeZone: 'America/Argentina/Buenos_Aires', day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date())
}

function parseOrderDate(value: string) {
  const match = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value)
  if (!match) return null
  const [, day, month, year] = match
  const iso = `${year}-${month}-${day}`
  const date = new Date(`${iso}T00:00:00Z`)
  return Number(year) > 0 && !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === iso ? iso : null
}

function validQuantity(value: string) {
  const quantity = quantityValue(value)
  return /^\d+(?:[.,]\d+)?$/.test(value.trim()) && Number.isFinite(quantity) && quantity > 0 && Number.isInteger(quantity * 2)
}

function message(cause: unknown) {
  if (cause instanceof ApiError) {
    if (cause.status === 400) return cause.detail || 'Revisá cantidades y descuentos.'
    if (cause.status === 403) return 'Tu usuario no tiene permiso para confirmar este pedido.'
    if (cause.status === 404) return cause.detail || 'Un cliente, producto o lista dejó de estar disponible.'
    if (cause.status === 409) return cause.detail || 'El stock, el precio o la clave de confirmación entró en conflicto.'
  }
  return cause instanceof Error ? cause.message : 'No se pudo confirmar el pedido.'
}

function newKey() {
  return globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function loadPriceLists() {
  const first = await apiGet<ApiPage<PriceList>>('/api/pricing/lists?page=0&size=20')
  const lists = [...first.content]
  for (let page = 1; page < first.totalPages; page += 1) {
    const next = await apiGet<ApiPage<PriceList>>(`/api/pricing/lists?page=${page}&size=20`)
    lists.push(...next.content)
  }
  return { ...first, content: lists }
}

export default function OrderForm({ editOrder, onSaved, onCancel }: Props) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const [initialOrderDate] = useState(currentOrderDate)
  const [orderDate, setOrderDate] = useState(initialOrderDate)
  const parsedOrderDate = parseOrderDate(orderDate)
  const invalidOrderDate = !editOrder && !parsedOrderDate
  const customersQuery = useQuery({ queryKey: CUSTOMER_KEY, queryFn: () => apiGetAllPages<Customer>('/api/customers?page=0&size=20'), enabled: !editOrder })
  const productsQuery = useQuery({ queryKey: PRODUCT_KEY, queryFn: () => apiGetAllPages<Product>('/api/products?page=0&size=100&includeStock=false') })
  const listsQuery = useQuery({ queryKey: ['order-create-price-lists', ...PRICE_LIST_KEY], queryFn: loadPriceLists })
  const sellersQuery = useQuery({ queryKey: SELLER_KEY, queryFn: () => apiGetAllPages<Seller>('/api/sellers?page=0&size=100'), enabled: isAdmin && !editOrder })
  const customers: Customer[] = editOrder ? [{ id: editOrder.order.customerId, name: editOrder.order.customer, seller: editOrder.order.seller, balance: editOrder.order.customerBalance }] : customersQuery.data?.content ?? []
  const [importedProducts, setImportedProducts] = useState<Product[]>(() => editOrder?.items.map((item) => ({ id: item.productId, name: item.productName })) ?? [])
  const products = [...new Map([...importedProducts, ...(productsQuery.data?.content ?? [])].map((product) => [product.id, product])).values()]
  const activeLists = (listsQuery.data?.content ?? []).filter((list) => list.status === 'ACTIVE')
  const [customerId, setCustomerId] = useState(editOrder?.order.customerId ?? '')
  const [sellerId, setSellerId] = useState('')
  const [explicitListId, setExplicitListId] = useState(editOrder?.items[0]?.priceListId ?? '')
  const [lines, setLines] = useState<DraftLine[]>(() => editOrder?.items.map((item) => ({ productId: item.productId, quantity: String(item.quantity), lineDiscountPercent: String(item.lineDiscountPercent ?? 0), unitPriceOverride: String(item.unitPrice) })) ?? [])
  const [selectedProductId, setSelectedProductId] = useState('')
  const [selectedProductQuantity, setSelectedProductQuantity] = useState('1')
  const [selectedProductDiscount, setSelectedProductDiscount] = useState('0')
  const [orderDiscountPercent, setOrderDiscountPercent] = useState(String(editOrder?.order.orderDiscountPercent ?? 0))
  const [selectedProductPriceOverride, setSelectedProductPriceOverride] = useState<string | undefined>()
  const [attempt, setAttempt] = useState<ConfirmationRequest | undefined>()
  const [responseData, setResponseData] = useState<ConfirmationResponse | undefined>()
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [previousBalanceInput, setPreviousBalanceInput] = useState(editOrder?.order.previousBalanceAmount ? String(editOrder.order.previousBalanceAmount).replace('.', ',') : '')
  const [selectedPreviousBalance, setSelectedPreviousBalance] = useState(editOrder?.order.previousBalanceAmount ?? 0)
  const [editChanged, setEditChanged] = useState(false)
  const [previousBalanceError, setPreviousBalanceError] = useState('')
  const [discountPrompt, setDiscountPrompt] = useState<{ order: PreviousOrder; anchor: HTMLElement }>()
  const customer = customers.find((item) => item.id === customerId)
  const customerBalance = Number(customer?.balance ?? 0)
  const defaultListId = customer ? customer.priceListId || activeLists.find((list) => list.code === 'GENERAL')?.id || '' : ''
  const resolvedListId = (isAdmin ? explicitListId : '') || defaultListId
  const selectedList = activeLists.find((list) => list.id === resolvedListId)
  const previousOrderQuery = useQuery({
    queryKey: ['order-create-previous-order', customerId],
    queryFn: ({ signal }) => apiGet<PreviousOrder>(`/api/customers/${encodeURIComponent(customerId)}/last-order`, signal),
    enabled: Boolean(customerId),
    retry: false,
  })
  const priceProductIds = products.filter((product) => product.status === 'ACTIVE' || lines.some((line) => line.productId === product.id)).map((product) => product.id).sort()
  const pricesQuery = useQuery({
    queryKey: ['order-price-batch', customerId, resolvedListId, priceProductIds],
    queryFn: ({ signal }) => loadOrderPrices(customerId, resolvedListId, priceProductIds, signal),
    enabled: Boolean(customerId && selectedList && priceProductIds.length),
    staleTime: 60_000,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })
  const pricesById = useMemo(() => new Map((pricesQuery.data ?? []).map((price) => [price.productId, price])), [pricesQuery.data])
  function priceResolution(productId: string) {
    const savedPrice = editOrder && lines.find((line) => line.productId === productId)?.unitPriceOverride
    const data = savedPrice ? { productId, unitPrice: quantityValue(savedPrice) } : pricesById.get(productId)
    const waiting = !data && (pricesQuery.isPending || pricesQuery.isFetching)
    return { data, isLoading: waiting, isFetching: waiting && pricesQuery.isFetching, isError: Boolean(productId) && !waiting && (pricesQuery.isError || !data), error: pricesQuery.error ?? new Error('Este producto no tiene precio disponible en la lista.'), refetch: pricesQuery.refetch }
  }
  const selectedPriceQuery = priceResolution(selectedProductId)
  const resolutions = lines.map((line) => priceResolution(line.productId))
  const productsById = useMemo(() => new Map(products.map((product) => [product.id, product])), [products])
  const invalidQuantityLines = lines.filter((line) => !validQuantity(line.quantity))
  const invalidSelectedQuantity = Boolean(selectedProductId) && !validQuantity(selectedProductQuantity)
  const previewSubtotal = lines.reduce((sum, line, index) => {
    const quantity = quantityValue(line.quantity)
    const unitPrice = line.unitPriceOverride !== '' ? quantityValue(line.unitPriceOverride) : Number(resolutions[index]?.data?.unitPrice ?? 0)
    const discount = isAdmin ? quantityValue(line.lineDiscountPercent || '0') : 0
    if (!validQuantity(line.quantity) || !Number.isFinite(unitPrice)) return sum
    return sum + quantity * unitPrice * (1 - discount / 100)
  }, 0)
  const orderDiscountValue = Number(orderDiscountPercent.trim().replace(',', '.') || 0)
  const invalidOrderDiscount = isAdmin && (!Number.isFinite(orderDiscountValue) || orderDiscountValue < 0 || orderDiscountValue > 100)
  const previewDiscount = isAdmin && !invalidOrderDiscount ? previewSubtotal * (orderDiscountValue / 100) : 0
  const previewTotal = Math.max(0, previewSubtotal - previewDiscount)
  const lineRows = lines.map((line, index) => {
    const product = productsById.get(line.productId)
    const resolution = resolutions[index]
    const unitPrice = line.unitPriceOverride !== '' ? quantityValue(line.unitPriceOverride) : Number(resolution?.data?.unitPrice ?? 0)
    const discount = isAdmin ? quantityValue(line.lineDiscountPercent || '0') : 0
    return {
      kind: 'PRODUCT',
      id: line.productId,
      name: product?.name ?? 'Producto',
      quantity: line.quantity,
      price: resolution?.isLoading ? 'Resolviendo...' : resolution?.isError ? 'No disponible' : money(unitPrice),
      discount: isAdmin ? line.lineDiscountPercent : '0',
      subtotal: validQuantity(line.quantity) ? money(unitPrice * quantityValue(line.quantity) * (1 - discount / 100)) : '—',
    }
  })
  if (selectedPreviousBalance > 0) lineRows.push({ kind: 'BALANCE', id: 'previous-balance', name: 'Saldo anterior', quantity: '—', price: money(selectedPreviousBalance), discount: '—', subtotal: money(selectedPreviousBalance) })
  const lineColumns: TableColumn[] = [
    { key: 'name', label: 'Producto', emphasis: true, render: (value) => <span className="order-line-product" title={value}>{value}</span> },
    { key: 'quantity', label: 'Cantidad', render: (value, row) => row.kind === 'BALANCE' ? value : <input className="input order-compact-input" size={4} aria-label={`Cantidad de ${row.name}`} aria-invalid={!validQuantity(value)} aria-describedby={!validQuantity(value) ? `order-quantity-${row.id}-error` : undefined} type="text" inputMode="decimal" required disabled={submitting} value={value} onChange={(event) => updateLine(row.id, 'quantity', event.target.value)} /> },
    { key: 'price', label: 'Precio unitario', align: 'right', render: (value, row) => editOrder && row.kind !== 'BALANCE' ? <input className="input" aria-label={`Precio unitario de ${row.name}`} type="text" inputMode="decimal" value={lines.find((line) => line.productId === row.id)?.unitPriceOverride || String(pricesById.get(row.id)?.unitPrice ?? '').replace('.', ',')} onChange={(event) => updateLine(row.id, 'unitPriceOverride', event.target.value)} disabled={submitting} /> : value },
    { key: 'discount', label: 'Descuento (%)', render: (value, row) => row.kind === 'BALANCE' ? value : isAdmin ? <input className="input order-compact-input" size={4} aria-label={`Descuento de ${row.name}`} type="text" inputMode="decimal" disabled={submitting} value={value} onChange={(event) => updateLine(row.id, 'lineDiscountPercent', event.target.value)} /> : `${value}%` },
    { key: 'subtotal', label: 'Subtotal', align: 'right', emphasis: true },
    { key: 'remove', label: '', align: 'right', render: (_value, row) => <Button variant="danger" type="button" aria-label={`Quitar ${row.name}`} title={`Quitar ${row.name}`} disabled={submitting} onClick={() => removeLine(row.id)}><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 6h18M9 6V4h6v2M5 6l1 14h12l1-14M10 10v6M14 10v6" /></svg></Button> },
  ]
  const hasUnsavedDraft = editOrder ? editChanged : Boolean(customerId || sellerId || explicitListId || lines.length || selectedProductId || orderDiscountPercent !== '0' || previousBalanceInput || orderDate !== initialOrderDate)
  const { requestDiscard, discardDialog } = useDiscardChanges({
    hasChanges: hasUnsavedDraft && !responseData,
    onDiscard: () => onCancel ? onCancel() : navigate('/orders'),
    disabled: submitting,
    protectUnload: true,
  })

  function draftChanged() {
    setEditChanged(true)
    setAttempt(undefined)
    setError('')
  }

  function selectPriceList(value: string) {
    if (!isAdmin) return
    draftChanged()
    setExplicitListId(value)
    setSelectedProductPriceOverride(undefined)
    if (editOrder) setLines((current) => current.map((line) => ({ ...line, unitPriceOverride: '' })))
  }

  function selectCustomer(selectedCustomerId: string) {
    const selectedCustomer = customers.find((item) => item.id === selectedCustomerId)
    draftChanged()
    setCustomerId(selectedCustomerId)
    setSellerId(selectedCustomer?.sellerId ?? '')
    setExplicitListId('')
    setSelectedProductPriceOverride(undefined)
    setPreviousBalanceInput('')
    setSelectedPreviousBalance(0)
    setPreviousBalanceError('')
  }

  function includePreviousBalance(amountText = previousBalanceInput) {
    const normalized = amountText.trim().replace(',', '.')
    const amount = Number(normalized)
    if (!/^\d+(?:\.\d{1,4})?$/.test(normalized) || !Number.isFinite(amount) || amount <= 0 || amount > customerBalance) {
      setPreviousBalanceError(`Ingresá un importe mayor a cero y hasta ${money(Math.max(0, customerBalance))}.`)
      return
    }
    draftChanged()
    setPreviousBalanceError('')
    setPreviousBalanceInput(normalized.replace('.', ','))
    setSelectedPreviousBalance(amount)
  }

  function loadPreviousOrder(event: MouseEvent<HTMLButtonElement>) {
    const previous = previousOrderQuery.data
    if (!previous?.available || !previous.items?.length || previousOrderQuery.isFetching || !selectedList) return
    const unavailable = previous.items.filter((item) => item.status !== 'ACTIVE')
    if (unavailable.length) {
      setError(`No se puede cargar el pedido anterior: hay productos no disponibles (${unavailable.map((item) => item.productName).join(', ')}).`)
      return
    }
    if (isAdmin && (Number(previous.orderDiscountPercent) > 0 || previous.items.some((item) => Number(item.lineDiscountPercent) > 0))) {
      setDiscountPrompt({ order: previous, anchor: event.currentTarget })
      return
    }
    applyPreviousOrder(previous, false)
  }

  function applyPreviousOrder(previous: PreviousOrder, copyDiscounts: boolean) {
    if (!previous.items?.length) return
    draftChanged()
    setImportedProducts(previous.items.map((item) => ({ id: item.productId, name: item.productName, status: item.status })))
    setLines(previous.items.map((item) => ({ productId: item.productId, quantity: String(item.quantity), lineDiscountPercent: String(isAdmin && copyDiscounts ? item.lineDiscountPercent : 0), unitPriceOverride: '' })))
    setOrderDiscountPercent(String(isAdmin && copyDiscounts ? previous.orderDiscountPercent ?? 0 : 0))
    setSelectedProductId('')
    setSelectedProductQuantity('1')
    setSelectedProductDiscount('0')
    setSelectedProductPriceOverride(undefined)
  }

  function addProduct() {
    if (!customerId || !selectedList || !selectedProductId || selectedPriceQuery.isFetching || selectedPriceQuery.isError || !selectedPriceQuery.data || lines.some((line) => line.productId === selectedProductId)) return
    const quantity = quantityValue(selectedProductQuantity)
    const discountInput = selectedProductDiscount.trim().replace(',', '.')
    const discount = isAdmin ? Number(discountInput) : 0
    const override = selectedProductPriceOverride?.trim().replace(',', '.') ?? ''
    if (!validQuantity(selectedProductQuantity)) { setError('La cantidad debe ser positiva y múltiplo de 0,5.'); return }
    if (!Number.isFinite(discount) || discount < 0 || discount > 100) { setError('El descuento debe estar entre 0 y 100%.'); return }
    if (isAdmin && selectedProductPriceOverride !== undefined && (!override || !Number.isFinite(Number(override)) || Number(override) < 0)) { setError('El precio debe ser un número no negativo.'); return }
    draftChanged()
    setLines((current) => [...current, { productId: selectedProductId, quantity: String(quantity), lineDiscountPercent: String(discount), unitPriceOverride: isAdmin ? override : '' }])
    setSelectedProductId('')
    setSelectedProductQuantity('1')
    setSelectedProductDiscount('0')
    setSelectedProductPriceOverride(undefined)
  }

  function updateLine(productId: string, field: keyof Omit<DraftLine, 'productId'>, value: string) {
    draftChanged()
    setLines((current) => current.map((line) => line.productId === productId ? { ...line, [field]: value } : line))
  }

  function removeLine(productId: string) {
    draftChanged()
    if (productId === 'previous-balance') {
      setSelectedPreviousBalance(0)
      setPreviousBalanceInput('')
      setPreviousBalanceError('')
      return
    }
    setLines((current) => current.filter((line) => line.productId !== productId))
  }

  function buildPayload(): ConfirmationRequest | undefined {
    if (invalidOrderDate) { setError('Ingresá una fecha válida con formato dd/mm/aaaa.'); return undefined }
    if (!customerId) { setError('Seleccioná un cliente.'); return undefined }
    if (!resolvedListId) { setError('Seleccioná una lista de precios.'); return undefined }
    if (!selectedList) { setError('La lista asignada no está disponible. Seleccioná una lista activa.'); return undefined }
    if (lines.length === 0) { setError('Agregá al menos un producto al pedido.'); return undefined }
    if (selectedPreviousBalance > Math.max(0, customerBalance) && selectedPreviousBalance !== editOrder?.order.previousBalanceAmount) { setError('El importe del saldo anterior supera el saldo actual del cliente.'); return undefined }
    if (resolutions.some((resolution) => resolution.isLoading)) { setError('Esperá a que se resuelvan los precios antes de confirmar.'); return undefined }
    if (resolutions.some((resolution) => resolution.isError || !resolution.data)) { setError('No se pudo resolver el precio de una línea. Revisá la lista y los productos.'); return undefined }

    const payloadLines: ConfirmationRequest['lines'] = []
    for (const [index, line] of lines.entries()) {
      const quantity = quantityValue(line.quantity)
      const discount = isAdmin ? quantityValue(line.lineDiscountPercent || '0') : 0
      const override = isAdmin && line.unitPriceOverride.trim() ? quantityValue(line.unitPriceOverride) : undefined
      if (!validQuantity(line.quantity)) { setError(`La cantidad de ${productsById.get(line.productId)?.name ?? 'el producto'} debe ser positiva y múltiplo de 0,5.`); return undefined }
      if (!Number.isFinite(discount) || discount < 0 || discount > 100) { setError('Los descuentos deben estar entre 0 y 100%.'); return undefined }
      if (override !== undefined && (!Number.isFinite(override) || override < 0)) { setError('El precio manual debe ser un número no negativo.'); return undefined }
      payloadLines.push({ productId: line.productId, quantity, lineDiscountPercent: discount, ...(override !== undefined ? { unitPriceOverride: override } : {}) })
      if (resolutions[index].data?.unitPrice === undefined) { setError('Falta resolver el precio del producto.'); return undefined }
    }

    const discount = isAdmin ? orderDiscountValue : 0
    if (!Number.isFinite(discount) || discount < 0 || discount > 100) { setError('El descuento general debe estar entre 0 y 100%.'); return undefined }
    return { idempotencyKey: newKey(), customerId, ...(!editOrder ? { orderDate: parsedOrderDate! } : {}), ...(isAdmin ? { sellerId: sellerId || null } : {}), priceListId: resolvedListId, lines: payloadLines, orderDiscountPercent: discount, ...(selectedPreviousBalance > 0 ? { previousBalanceAmount: selectedPreviousBalance } : {}), payments: [] }
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
      if (editOrder) {
        await apiPut(`/api/orders/${editOrder.order.id}`, { priceListId: payload.priceListId, lines: payload.lines, orderDiscountPercent: payload.orderDiscountPercent, previousBalanceAmount: selectedPreviousBalance })
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: ['/api/orders'] }),
          queryClient.invalidateQueries({ queryKey: ['/api/sales'] }),
          queryClient.invalidateQueries({ queryKey: CUSTOMER_KEY }),
          queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
          queryClient.invalidateQueries({ predicate: (query) => typeof query.queryKey[0] === 'string' && query.queryKey[0].startsWith('/api/inventory') }),
        ])
        await onSaved?.()
        return
      }
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
      setError(editOrder && cause instanceof ApiError && cause.status === 403 ? 'Tu usuario no tiene permiso para editar este pedido.' : message(cause))
    } finally {
      setSubmitting(false)
    }
  }

  if (responseData) return <>
    <div className="order-customer-header"><PageHeader eyebrow="Pedido confirmado" title={customer?.name ?? 'Pedido confirmado'} description={`Pedido ${responseData.orderNumber} · El pedido y la venta quedaron registrados.`} /></div>
    <Panel title="Resultado de la confirmación">
      <dl className="confirmation-result"><dt>Venta</dt><dd>{responseData.saleNumber}</dd><dt>Total</dt><dd>{money(responseData.total)}</dd><dt>Cobrado</dt><dd>{money(responseData.paid)}</dd><dt>Saldo pendiente</dt><dd>{money(responseData.balance)}</dd></dl>
      {Number(responseData.previousBalanceAmount ?? 0) > 0 && <dl className="confirmation-result"><dt>Saldo anterior para entrega</dt><dd>{money(responseData.previousBalanceAmount!)}</dd><dt>Total a cobrar con la entrega</dt><dd>{money(responseData.collectionTotal ?? responseData.total + responseData.previousBalanceAmount!)}</dd></dl>}
      {responseData.creditLimitWarning && <p className="warning-text" role="status">El saldo proyectado {money(responseData.creditLimitWarning.projectedBalance)} supera el límite de crédito {money(responseData.creditLimitWarning.creditLimit)} por {money(responseData.creditLimitWarning.exceededBy)}.</p>}
      <div className="page-actions"><Button href={`/orders/${responseData.orderId}`}>Ver pedido</Button><Button variant="secondary" onClick={() => navigate('/orders')}>Volver a pedidos</Button></div>
    </Panel>
  </>

  const readError = (!editOrder ? customersQuery.error : null) ?? productsQuery.error ?? listsQuery.error ?? (isAdmin && !editOrder ? sellersQuery.error : null)
  const readLoading = (!editOrder && customersQuery.isLoading) || productsQuery.isLoading || listsQuery.isLoading || (isAdmin && !editOrder && sellersQuery.isLoading)

  return <>
    <PageHeader eyebrow="Operación" title={editOrder ? 'Editar pedido' : 'Nuevo pedido'} description={editOrder?.order.number} actions={<Button variant="secondary" type="button" onClick={requestDiscard} disabled={submitting}>Cancelar</Button>} />
    {discardDialog}
    {readLoading ? <Panel><EmptyState title="Cargando datos del pedido" description={isAdmin ? 'Consultando clientes, productos, vendedores y listas activas.' : 'Consultando clientes, productos y listas activas.'} /></Panel>
      : readError ? <Panel><EmptyState title="No se pudo preparar el pedido" description={readError.message} action={isAdmin && sellersQuery.isError && readError === sellersQuery.error ? <Button variant="secondary" onClick={() => sellersQuery.refetch()}>Reintentar vendedores</Button> : undefined} /></Panel>
        : <form className="order-form" onSubmit={confirm}>
          {error && <p className="error-text" role="alert">{error}</p>}
          <Panel title="Datos del pedido" action={!editOrder ? <div className="order-form-date"><OrderDateFilter id="order-date" label="Fecha del pedido" value={orderDate} isoValue={parsedOrderDate ?? ''} invalid={invalidOrderDate} describedBy={invalidOrderDate ? 'order-date-error' : undefined} required disabled={submitting} onChange={(value) => { draftChanged(); setOrderDate(value) }} /></div> : undefined}>
            {invalidOrderDate && <p id="order-date-error" className="error-text" role="alert">Ingresá una fecha válida con formato dd/mm/aaaa.</p>}
            {discountPrompt && <PreviousOrderDiscountDialog orderNumber={discountPrompt.order.orderNumber ?? ''} orderDiscount={Number(discountPrompt.order.orderDiscountPercent ?? 0)} items={discountPrompt.order.items ?? []} anchor={discountPrompt.anchor} onDismiss={() => setDiscountPrompt(undefined)} onChoose={(copyDiscounts) => { applyPreviousOrder(discountPrompt.order, copyDiscounts); setDiscountPrompt(undefined) }} />}
            <div className="order-customer-grid">
              <CustomerSelect mode="selection" label="Cliente" options={customers} value={customerId} onChange={selectCustomer} required disabled={submitting || Boolean(editOrder)} />
              {editOrder ? <SellerSelect mode="selection" label="Vendedor" options={[{ id: 'locked-seller', name: editOrder.order.seller ?? 'Sin asignar' }]} value="locked-seller" onChange={() => {}} disabled /> : isAdmin
                ? <SellerSelect mode="selection" label="Vendedor" options={(sellersQuery.data?.content ?? []).map((seller) => ({ id: seller.id, name: seller.displayName }))} value={sellerId} onChange={(value) => { draftChanged(); setSellerId(value) }} emptyLabel="Usar vendedor del cliente" disabled={submitting} />
                : <div className="field"><span>Vendedor</span><span className="read-only-field" aria-label="Vendedor">{customer?.seller || 'Seleccioná un cliente'}</span></div>}
              <label className="field"><span>Lista de precios</span><select className="select" aria-label="Lista de precios" aria-invalid={Boolean(customerId && resolvedListId && !selectedList)} aria-describedby={customerId && resolvedListId && !selectedList ? 'order-price-list-error' : undefined} value={resolvedListId} onChange={(event) => selectPriceList(event.target.value)} required disabled={submitting || !isAdmin}><option value="">Seleccionar lista...</option>{resolvedListId && !selectedList && <option value={resolvedListId} disabled>Lista asignada no disponible</option>}{activeLists.map((list) => <option value={list.id} key={list.id}>{list.code} · {list.name}</option>)}</select>{customerId && resolvedListId && !selectedList && <span id="order-price-list-error" className="error-text">{isAdmin ? 'Seleccioná una lista activa para continuar.' : 'La lista asignada no está disponible. Solicitá su revisión a un administrador.'}</span>}</label>
              <div className="order-previous-action"><Button type="button" variant="secondary" onClick={loadPreviousOrder} disabled={!customerId || !selectedList || previousOrderQuery.isFetching || previousOrderQuery.isError || !previousOrderQuery.data?.available || submitting} title={!customerId ? 'Seleccioná un cliente para consultar su último pedido.' : previousOrderQuery.isFetching ? 'Consultando el último pedido del cliente.' : previousOrderQuery.isError ? 'No se pudo consultar el pedido anterior.' : !previousOrderQuery.data?.available ? 'Este cliente no tiene pedidos anteriores.' : 'Reemplazar los productos del borrador con los del último pedido del cliente.'}>Cargar pedido anterior</Button></div>
            </div>
            {previousOrderQuery.isError && <div className="page-actions"><p className="error-text" role="alert">No se pudo consultar el pedido anterior.</p><Button type="button" variant="secondary" onClick={() => previousOrderQuery.refetch()}>Reintentar pedido anterior</Button></div>}
            <div className={`product-picker order-product-picker${isAdmin ? ' order-product-picker-admin' : ''}`}>
              <SearchableSelect key={customerId} label="Producto" className="order-product-choice" fullWidth preserveSearch allLabel="Seleccionar producto..." unavailableLabel="Producto no disponible" loadingLabel="Cargando productos…" options={products.filter((item) => item.status === 'ACTIVE' && !lines.some((line) => line.productId === item.id)).map((item) => ({ id: item.id, name: item.name }))} value={selectedProductId} onChange={(value) => { setSelectedProductId(value); setSelectedProductQuantity('1'); setSelectedProductDiscount('0'); setSelectedProductPriceOverride(undefined); setError('') }} disabled={!customerId || !selectedList || submitting} />
              {isAdmin
                ? <label className="field"><span>Precio</span><input className="input" aria-label="Precio" aria-busy={selectedPriceQuery.isFetching} placeholder={selectedProductId && selectedPriceQuery.isFetching ? 'Consultando...' : selectedProductId && selectedPriceQuery.isError ? 'No disponible' : '—'} type="text" inputMode="decimal" value={selectedProductPriceOverride ?? (selectedProductId && selectedPriceQuery.data ? String(selectedPriceQuery.data.unitPrice).replace('.', ',') : '')} onChange={(event) => setSelectedProductPriceOverride(event.target.value)} disabled={submitting || !customerId || !selectedList || !selectedProductId || selectedPriceQuery.isFetching || selectedPriceQuery.isError || !selectedPriceQuery.data} /></label>
                : <div className="field"><span>Precio</span><span className="read-only-field" aria-label="Precio" aria-live="polite" aria-busy={selectedPriceQuery.isFetching}>{!selectedProductId ? '—' : selectedPriceQuery.isFetching ? 'Consultando...' : selectedPriceQuery.isError ? 'No disponible' : selectedPriceQuery.data ? String(selectedPriceQuery.data.unitPrice).replace('.', ',') : '—'}</span></div>}
              <label className="field"><span>Cantidad</span><input className="input order-compact-input" size={4} type="text" inputMode="decimal" aria-invalid={invalidSelectedQuantity} aria-describedby={invalidSelectedQuantity ? 'order-selected-quantity-error' : undefined} value={selectedProductQuantity} onChange={(event) => setSelectedProductQuantity(event.target.value)} disabled={!selectedProductId || submitting} /></label>
              {isAdmin && <label className="field"><span>Descuento</span><input className="input order-compact-input" size={4} type="text" inputMode="decimal" value={selectedProductDiscount} onChange={(event) => setSelectedProductDiscount(event.target.value)} disabled={!selectedProductId || submitting} /></label>}
              <Button type="button" variant="secondary" onClick={addProduct} disabled={submitting || !customerId || !selectedList || !selectedProductId || invalidSelectedQuantity || selectedPriceQuery.isFetching || selectedPriceQuery.isError || !selectedPriceQuery.data}>Agregar producto</Button>
              {invalidSelectedQuantity && <p id="order-selected-quantity-error" className="error-text" role="alert">La cantidad debe ser positiva y múltiplo de 0,5.</p>}
              {selectedProductId && selectedPriceQuery.isError && <p className="order-product-choice error-text" role="alert">No se pudo consultar el precio de la lista. {selectedPriceQuery.error?.message} <Button type="button" variant="link" onClick={() => selectedPriceQuery.refetch()}>Reintentar precio</Button></p>}
            </div>
            <DataTable className="order-lines order-lines-table" columns={lineColumns} rows={lineRows} emptyContent={<p className="table-empty-message">Elegí un producto para consultar su precio en la lista seleccionada.</p>} />
            {invalidQuantityLines.map((line) => <p key={line.productId} id={`order-quantity-${line.productId}-error`} className="error-text" role="alert">La cantidad de {productsById.get(line.productId)?.name ?? 'el producto'} debe ser positiva y múltiplo de 0,5.</p>)}
            {customer && <section className="order-account" aria-labelledby="order-account-title">
              <div className="order-account-balance"><h3 id="order-account-title">Cuenta corriente</h3><span>Saldo actual</span><strong>{money(customerBalance)}</strong></div>
              <div className="order-account-controls">
                <label className="field"><span>Importe para el remito</span><input className="input" type="text" inputMode="decimal" value={previousBalanceInput} placeholder="0,00" aria-invalid={Boolean(previousBalanceError)} aria-describedby={previousBalanceError ? 'order-account-error' : undefined} onChange={(event) => { draftChanged(); setPreviousBalanceInput(event.target.value); setPreviousBalanceError('') }} disabled={customerBalance <= 0 || submitting} /></label>
                <Button type="button" variant="secondary" onClick={() => includePreviousBalance()} disabled={customerBalance <= 0 || submitting || !previousBalanceInput.trim()}>{selectedPreviousBalance > 0 ? 'Actualizar importe' : 'Agregar importe'}</Button>
                <Button type="button" variant="link" onClick={() => includePreviousBalance(String(customerBalance))} disabled={customerBalance <= 0 || submitting}>Agregar saldo total</Button>
              </div>
              {previousBalanceError && <p className="error-text" id="order-account-error" role="alert">{previousBalanceError}</p>}
            </section>}
            <div className={`order-checkout${isAdmin ? '' : ' order-checkout-standard'}`}>
              {isAdmin && <div className="order-checkout-discount">
                <label className="field"><span>Descuento general</span><span className="order-discount-input"><input className="input" type="text" inputMode="decimal" aria-label="Descuento general (%)" disabled={submitting} aria-invalid={invalidOrderDiscount} aria-describedby={invalidOrderDiscount ? 'order-discount-error' : 'order-discount-hint'} value={orderDiscountPercent} onChange={(event) => { draftChanged(); setOrderDiscountPercent(event.target.value) }} /><span aria-hidden="true">%</span></span></label>
                {invalidOrderDiscount ? <p id="order-discount-error" className="error-text" role="alert">Ingresá un descuento entre 0 y 100%.</p> : <p id="order-discount-hint" className="helper-text">De 0 a 100% sobre el subtotal.</p>}
              </div>}
              <dl className="order-totals"><dt>Subtotal</dt><dd>{invalidQuantityLines.length ? '—' : money(previewSubtotal)}</dd>{isAdmin && <><dt>Descuento general</dt><dd className={previewDiscount > 0 ? 'order-discount-value' : undefined}>{previewDiscount > 0 ? '− ' : ''}{money(previewDiscount)}</dd></>}{selectedPreviousBalance > 0 && <><dt>Total del pedido</dt><dd>{invalidQuantityLines.length ? '—' : money(previewTotal)}</dd><dt>Saldo anterior</dt><dd>{money(selectedPreviousBalance)}</dd></>}</dl>
              <div className="order-checkout-actions">
                <dl className="order-checkout-total"><dt>{selectedPreviousBalance > 0 ? 'Total a cobrar' : 'Total estimado'}</dt><dd className="total-value">{invalidQuantityLines.length ? '—' : money(previewTotal + selectedPreviousBalance)}</dd></dl>
                <Button type="submit" fullWidth disabled={submitting || invalidOrderDate || invalidOrderDiscount || invalidQuantityLines.length > 0 || resolutions.some((resolution) => resolution.isLoading) || lines.length === 0}>{editOrder ? submitting ? 'Guardando...' : 'Guardar cambios' : submitting ? 'Confirmando...' : attempt ? 'Reintentar confirmación' : 'Confirmar pedido'}</Button>
              </div>
            </div>
          </Panel>
        </form>}
  </>
}
