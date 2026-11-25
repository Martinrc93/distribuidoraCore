import { CurrencyText } from '../../shared/components/CurrencyText'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent, type MouseEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { apiGet, apiPost, apiPut, ApiError } from '../../shared/api/client'
import { instanceStorageKey } from '../../shared/appBase'
import { createIdempotencyKey } from '../../shared/api/createIdempotencyKey'
import { invalidateDomains, queryKeys } from '../../shared/api/queryKeys'
import { getCurrentUserId, hasAuthority, hasPermission } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { CustomerSelect, SellerSelect } from '../../shared/components/EntitySelect'
import { type SearchableSelectHandle } from '../../shared/components/SearchableSelect'
import { apiGetAllPages } from '../../shared/api/pagination'
import { displayDate, parseDate, todayInArgentina } from '../../shared/dates'
import { formatMoney, formatNumber } from '../../shared/format'
import { loadOrderPrices } from './orderPrice'
import { readOrderDraft, type CollectionDraft, type DraftLine, type OrderDraft } from './orderDraft'
import { PreviousOrderDiscountDialog } from './PreviousOrderDiscountDialog'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { OrderDateFilter } from './OrderDateFilter'
import { OrderConfirmationSummary, type ConfirmationResponse } from './OrderConfirmationSummary'
import { OrderProductPicker } from './OrderProductPicker'
import { OrderCheckoutSummary } from './OrderCheckoutSummary'

type Customer = { id: string; name: string; sellerId?: string | null; seller?: string | null; priceListId?: string | null; balance?: number; kind?: string | null; resellerId?: string | null; reseller?: string | null }
type Product = { id: string; name: string; status?: string; stock?: number }
type PreviousOrderItem = { productId: string; productName: string; status: string; quantity: number; lineDiscountPercent: number }
type PreviousOrder = { available: boolean; orderId?: string; orderNumber?: string; orderDiscountPercent?: number; items?: PreviousOrderItem[] }
type PriceList = { id: string; code: string; name: string; status: string }
type Seller = { id: string; displayName: string; email: string }
export type EditableOrder = {
  order: { id: string; number: string; customerId: string; customer: string; seller?: string; customerBalance: number; orderDiscountPercent?: number; previousBalanceAmount?: number; accountCustomerId?: string; accountCustomer?: string }
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
  payments: CollectionPayment[]
  previousDebtPayments?: CollectionPayment[]
}
type CollectionPayment = { method: 'CASH' | 'BANK_TRANSFER'; amount: number; transferReference?: string }
const emptyPayment: CollectionDraft = { method: 'CASH', amount: '', reference: '' }
/** Parses an es-AR amount; empty means 0, invalid means NaN. */
const collectionAmount = (value: string) => !value.trim() ? 0 : /^\d+(?:[.,]\d{1,4})?$/.test(value.trim()) ? Number(value.trim().replace(',', '.')) : NaN
const collectionPayment = (draft: CollectionDraft, amount: number): CollectionPayment => ({
  method: draft.method, amount, ...(draft.method === 'BANK_TRANSFER' && draft.reference.trim() ? { transferReference: draft.reference.trim() } : {}),
})
const CUSTOMER_PATH = '/api/customers?page=0&size=100'
const PRODUCT_PATH = '/api/products?page=0&size=100&includeStock=true'
const PRICE_LIST_PATH = '/api/pricing/lists?page=0&size=100'
const SELLER_PATH = '/api/sellers?page=0&size=100'
const DOUBLE_ENTER_WINDOW_MS = 700

function money(value: number) {
  return formatMoney(value, 2)
}

function quantityValue(value: string) {
  return Number(value.trim().replace(',', '.'))
}

function currentOrderDate() {
  return displayDate(todayInArgentina())
}

function parseOrderDate(value: string) {
  return parseDate(value)
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

export default function OrderForm({ editOrder, onSaved, onCancel }: Props) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canOverride = hasPermission('ORDER_PRICE_OVERRIDE')
  const canDiscount = hasPermission('ORDER_DISCOUNT')
  // Sellers may pick another active list when their role has ORDER_PRICE_LIST_CHANGE.
  const canChangeList = isAdmin || hasAuthority('ORDER_PRICE_LIST_CHANGE')
  const userId = getCurrentUserId()
  const draftStorageKey = !editOrder && userId ? instanceStorageKey(`distribuidora.order-draft.v1.${userId}`) : undefined
  const [savedDraft, setSavedDraft] = useState(() => readOrderDraft(draftStorageKey))
  const savedFields = savedDraft?.fields
  const [initialOrderDate] = useState(currentOrderDate)
  const [orderDate, setOrderDate] = useState(savedFields?.orderDate ?? initialOrderDate)
  const parsedOrderDate = parseOrderDate(orderDate)
  const invalidOrderDate = !editOrder && !parsedOrderDate
  const customersQuery = useQuery({ queryKey: queryKeys.customers.orderOptions, queryFn: ({ signal }) => apiGetAllPages<Customer>(CUSTOMER_PATH, signal), enabled: !editOrder })
  const productsQuery = useQuery({ queryKey: queryKeys.products.allOptions('order-create'), queryFn: ({ signal }) => apiGetAllPages<Product>(PRODUCT_PATH, signal) })
  const listsQuery = useQuery({ queryKey: queryKeys.pricing.listOptions('order-create'), queryFn: ({ signal }) => apiGetAllPages<PriceList>(PRICE_LIST_PATH, signal) })
  const sellersQuery = useQuery({ queryKey: queryKeys.sellers.options('order-create'), queryFn: ({ signal }) => apiGetAllPages<Seller>(SELLER_PATH, signal), enabled: isAdmin && !editOrder })
  const customers: Customer[] = editOrder ? [{ id: editOrder.order.customerId, name: editOrder.order.customer, seller: editOrder.order.seller, balance: editOrder.order.customerBalance,
    ...(editOrder.order.accountCustomerId && editOrder.order.accountCustomerId !== editOrder.order.customerId ? { resellerId: editOrder.order.accountCustomerId, reseller: editOrder.order.accountCustomer } : {}) }] : customersQuery.data?.content ?? []
  const [importedProducts, setImportedProducts] = useState<Product[]>(() => editOrder?.items.map((item) => ({ id: item.productId, name: item.productName })) ?? [])
  const products = [...new Map([...importedProducts, ...(productsQuery.data?.content ?? [])].map((product) => [product.id, product])).values()]
  const activeLists = (listsQuery.data?.content ?? []).filter((list) => list.status === 'ACTIVE')
  const [customerId, setCustomerId] = useState(editOrder?.order.customerId ?? savedFields?.customerId ?? '')
  const [sellerId, setSellerId] = useState(isAdmin ? savedFields?.sellerId ?? '' : '')
  const [explicitListId, setExplicitListId] = useState(editOrder?.items[0]?.priceListId ?? (canChangeList ? savedFields?.explicitListId ?? '' : ''))
  const [lines, setLines] = useState<DraftLine[]>(() => {
    if (editOrder) return editOrder.items.map((item) => ({ productId: item.productId, quantity: String(item.quantity), lineDiscountPercent: String(item.lineDiscountPercent ?? 0), unitPriceOverride: String(item.unitPrice) }))
    return (savedFields?.lines ?? []).map((line) => ({ ...line, lineDiscountPercent: canDiscount ? line.lineDiscountPercent : '0', unitPriceOverride: canOverride ? line.unitPriceOverride : '' }))
  })
  const [selectedProductId, setSelectedProductId] = useState(savedFields?.selectedProductId ?? '')
  const [selectedProductQuantity, setSelectedProductQuantity] = useState(savedFields?.selectedProductQuantity ?? '1')
  const [selectedProductDiscount, setSelectedProductDiscount] = useState(canDiscount ? savedFields?.selectedProductDiscount ?? '0' : '0')
  const [orderDiscountPercent, setOrderDiscountPercent] = useState(String(editOrder?.order.orderDiscountPercent ?? (canDiscount ? savedFields?.orderDiscountPercent ?? 0 : 0)))
  const [selectedProductPriceOverride, setSelectedProductPriceOverride] = useState(canOverride ? savedFields?.selectedProductPriceOverride : undefined)
  const [attempt, setAttempt] = useState<ConfirmationRequest | undefined>()
  const [responseData, setResponseData] = useState<ConfirmationResponse | undefined>()
  const [error, setError] = useState('')
  const [notifyWhatsApp, setNotifyWhatsApp] = useState(false)
  const [notificationRequested, setNotificationRequested] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [previousBalanceInput, setPreviousBalanceInput] = useState(editOrder?.order.previousBalanceAmount ? String(editOrder.order.previousBalanceAmount).replace('.', ',') : savedFields?.previousBalanceInput ?? '')
  const [selectedPreviousBalance, setSelectedPreviousBalance] = useState(editOrder?.order.previousBalanceAmount ?? savedFields?.selectedPreviousBalance ?? 0)
  const [editChanged, setEditChanged] = useState(false)
  const [previousBalanceError, setPreviousBalanceError] = useState('')
  const [discountPrompt, setDiscountPrompt] = useState<{ order: PreviousOrder; anchor: HTMLElement }>()
  const [draftSaveError, setDraftSaveError] = useState('')
  const customer = customers.find((item) => item.id === customerId)
  const customerBalance = Number(customer?.balance ?? 0)
  // A reseller owes its customers' orders: the debt, the balance and any collection belong to its account.
  const resellerId = customer?.resellerId ?? ''
  const resellerName = customer?.reseller ?? ''
  const canCollect = isAdmin || (!resellerId && customer?.kind !== 'RESELLER')
  const resellerBalanceQuery = useQuery({
    queryKey: queryKeys.customers.accountBalance(resellerId),
    queryFn: ({ signal }) => apiGet<{ balance: number }>(`/api/customers/${resellerId}/account-balance`, signal),
    enabled: Boolean(resellerId) && isAdmin && !editOrder,
  })
  const accountBalance = resellerId ? Number((editOrder ? customer?.balance : resellerBalanceQuery.data?.balance) ?? 0) : customerBalance
  // Optional collection at confirmation: this order and older debts are paid separately.
  const [orderPayment, setOrderPayment] = useState(savedFields?.orderPayment ?? emptyPayment)
  const [debtPayment, setDebtPayment] = useState(savedFields?.debtPayment ?? emptyPayment)
  const orderPaid = canCollect ? collectionAmount(orderPayment.amount) : 0
  const debtPaid = canCollect ? collectionAmount(debtPayment.amount) : 0
  const defaultListId = customer ? customer.priceListId || activeLists.find((list) => list.code === 'GENERAL')?.id || '' : ''
  const resolvedListId = (editOrder || canChangeList ? explicitListId : '') || defaultListId
  const selectedList = activeLists.find((list) => list.id === resolvedListId)
  const previousOrderQuery = useQuery({
    queryKey: queryKeys.orders.previousOrder(customerId),
    queryFn: ({ signal }) => apiGet<PreviousOrder>(`/api/customers/${encodeURIComponent(customerId)}/last-order`, signal),
    enabled: Boolean(customer) && (!editOrder || isAdmin),
    retry: false,
  })
  const priceProductIds = products.filter((product) => product.status === 'ACTIVE' || lines.some((line) => line.productId === product.id)).map((product) => product.id).sort()
  const pricesQuery = useQuery({
    queryKey: queryKeys.pricing.batch(customerId, resolvedListId, priceProductIds, editOrder?.order.id ?? null),
    queryFn: ({ signal }) => loadOrderPrices(customerId, resolvedListId, priceProductIds, signal, editOrder?.order.id),
    enabled: Boolean(customer && selectedList && priceProductIds.length),
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
    const discount = quantityValue(line.lineDiscountPercent || '0')
    if (!validQuantity(line.quantity) || !Number.isFinite(unitPrice)) return sum
    return sum + quantity * unitPrice * (1 - discount / 100)
  }, 0)
  const orderDiscountValue = Number(orderDiscountPercent.trim().replace(',', '.') || 0)
  const invalidOrderDiscount = (!Number.isFinite(orderDiscountValue) || orderDiscountValue < 0 || orderDiscountValue > 100)
  const previewDiscount = !invalidOrderDiscount ? previewSubtotal * (orderDiscountValue / 100) : 0
  const previewTotal = Math.max(0, previewSubtotal - previewDiscount)
  const lineRows = lines.map((line, index) => {
    const product = productsById.get(line.productId)
    const resolution = resolutions[index]
    const unitPrice = line.unitPriceOverride !== '' ? quantityValue(line.unitPriceOverride) : Number(resolution?.data?.unitPrice ?? 0)
    const discount = quantityValue(line.lineDiscountPercent || '0')
    return {
      kind: 'PRODUCT',
      id: line.productId,
      name: product?.name ?? 'Producto',
      quantity: line.quantity,
      price: resolution?.isLoading ? 'Resolviendo...' : resolution?.isError ? 'No disponible' : money(unitPrice),
      discount: line.lineDiscountPercent,
      subtotal: validQuantity(line.quantity) ? money(unitPrice * quantityValue(line.quantity) * (1 - discount / 100)) : '—',
    }
  })
  if (selectedPreviousBalance > 0) lineRows.push({ kind: 'BALANCE', id: 'previous-balance', name: 'Saldo anterior', quantity: '—', price: money(selectedPreviousBalance), discount: '—', subtotal: money(selectedPreviousBalance) })
  const lineColumns: TableColumn[] = [
    { key: 'name', label: 'Producto', emphasis: true, render: (value) => <span className="order-line-product" title={value}>{value}</span> },
    { key: 'quantity', label: 'Cantidad', render: (value, row) => row.kind === 'BALANCE' ? value : <input className="input order-compact-input" size={4} aria-label={`Cantidad de ${row.name}`} aria-invalid={!validQuantity(value)} aria-describedby={!validQuantity(value) ? `order-quantity-${row.id}-error` : undefined} type="text" inputMode="decimal" required disabled={submitting} value={value} onChange={(event) => updateLine(row.id, 'quantity', event.target.value)} /> },
    { key: 'price', label: 'Precio unitario', align: 'right', render: (value, row) => editOrder && canOverride && row.kind !== 'BALANCE' ? <input className="input currency-input" aria-label={`Precio unitario de ${row.name}`} type="text" inputMode="decimal" value={lines.find((line) => line.productId === row.id)?.unitPriceOverride || String(pricesById.get(row.id)?.unitPrice ?? '').replace('.', ',')} onChange={(event) => updateLine(row.id, 'unitPriceOverride', event.target.value)} disabled={submitting} /> : value },
    { key: 'discount', label: 'Descuento (%)', render: (value, row) => row.kind === 'BALANCE' ? value : canDiscount ? <input className="input order-compact-input" size={4} aria-label={`Descuento de ${row.name}`} type="text" inputMode="decimal" disabled={submitting} value={value} onChange={(event) => updateLine(row.id, 'lineDiscountPercent', event.target.value)} /> : `${value}%` },
    { key: 'subtotal', label: 'Subtotal', align: 'right', emphasis: true },
    { key: 'remove', label: 'Acciones', align: 'right', render: (_value, row) => <Button variant={editOrder ? 'danger' : 'secondary'} type="button" aria-label={`Quitar ${row.name}`} title={`Quitar ${row.name}`} disabled={submitting} onClick={() => removeLine(row.id)}><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={editOrder ? 'M3 6h18M9 6V4h6v2M5 6l1 14h12l1-14M10 10v6M14 10v6' : 'm6 6 12 12M18 6 6 18'} /></svg></Button> },
  ]
  const draftFields: OrderDraft['fields'] = {
    orderDate, customerId, sellerId, explicitListId, lines, selectedProductId, selectedProductQuantity,
    selectedProductDiscount, selectedProductPriceOverride, orderDiscountPercent, previousBalanceInput,
    selectedPreviousBalance, orderPayment, debtPayment,
  }
  const draftIsSaved = Boolean(savedDraft && JSON.stringify(savedDraft.fields) === JSON.stringify(draftFields))
  const hasNewDraft = Boolean(customerId || sellerId || explicitListId || lines.length || selectedProductId || orderDiscountPercent !== '0' || previousBalanceInput || orderDate !== initialOrderDate)
  const hasUnsavedDraft = editOrder ? editChanged : savedDraft ? !draftIsSaved : hasNewDraft
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

  function changeOrderDiscount(value: string) {
    draftChanged()
    setOrderDiscountPercent(value)
  }

  function stepOrderDiscount(step: 1 | -1) {
    if (invalidOrderDiscount) return
    const next = Math.min(100, Math.max(0, orderDiscountValue + step))
    if (next === orderDiscountValue) return
    changeOrderDiscount(String(Number(next.toFixed(4))).replace('.', ','))
  }

  function saveDraft() {
    if (!draftStorageKey || !hasUnsavedDraft || submitting) return
    const draft = { idempotencyKey: attempt?.idempotencyKey ?? createIdempotencyKey(), fields: draftFields }
    try {
      localStorage.setItem(draftStorageKey, JSON.stringify(draft))
      setSavedDraft(draft)
      setDraftSaveError('')
    } catch {
      setDraftSaveError('No se pudo guardar el borrador en este navegador. Podés reintentar.')
    }
  }

  function selectPriceList(value: string) {
    if (!canChangeList) return
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
    setOrderPayment({ method: 'CASH', amount: '', reference: '' })
    setDebtPayment({ method: 'CASH', amount: '', reference: '' })
    setPreviousBalanceError('')
    if (selectedCustomer) focusProductSearch()
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
    if (canDiscount && (Number(previous.orderDiscountPercent) > 0 || previous.items.some((item) => Number(item.lineDiscountPercent) > 0))) {
      setDiscountPrompt({ order: previous, anchor: event.currentTarget })
      return
    }
    applyPreviousOrder(previous, false)
  }

  function applyPreviousOrder(previous: PreviousOrder, copyDiscounts: boolean) {
    if (!previous.items?.length) return
    draftChanged()
    setImportedProducts(previous.items.map((item) => ({ id: item.productId, name: item.productName, status: item.status })))
    setLines(previous.items.map((item) => ({ productId: item.productId, quantity: String(item.quantity), lineDiscountPercent: String(canDiscount && copyDiscounts ? item.lineDiscountPercent : 0), unitPriceOverride: '' })))
    setOrderDiscountPercent(String(canDiscount && copyDiscounts ? previous.orderDiscountPercent ?? 0 : 0))
    setSelectedProductId('')
    setSelectedProductQuantity('1')
    setSelectedProductDiscount('0')
    setSelectedProductPriceOverride(undefined)
  }

  /** Adds the selected product; returns true when a line was added. */
  function addProduct(): boolean {
    if (!customerId || !selectedList || !selectedProductId || selectedPriceQuery.isFetching || selectedPriceQuery.isError || !selectedPriceQuery.data || lines.some((line) => line.productId === selectedProductId)) return false
    const quantity = quantityValue(selectedProductQuantity)
    const discountInput = selectedProductDiscount.trim().replace(',', '.')
    const discount = canDiscount ? Number(discountInput) : 0
    const override = selectedProductPriceOverride?.trim().replace(',', '.') ?? ''
    if (!validQuantity(selectedProductQuantity)) { setError('La cantidad debe ser positiva y múltiplo de 0,5.'); return false }
    if (!Number.isFinite(discount) || discount < 0 || discount > 100) { setError('El descuento debe estar entre 0 y 100%.'); return false }
    if (canOverride && selectedProductPriceOverride !== undefined && (!override || !Number.isFinite(Number(override)) || Number(override) < 0)) { setError('El precio debe ser un número no negativo.'); return false }
    draftChanged()
    setLines((current) => [...current, { productId: selectedProductId, quantity: String(quantity), lineDiscountPercent: String(discount), unitPriceOverride: canOverride ? override : '' }])
    setSelectedProductId('')
    setSelectedProductQuantity('1')
    setSelectedProductDiscount('0')
    setSelectedProductPriceOverride(undefined)
    return true
  }

  // Keyboard loading: choose a product → quantity → Enter adds it and returns to the product search.
  const productSearch = useRef<SearchableSelectHandle>(null)
  const quantityInput = useRef<HTMLInputElement>(null)
  const focusQuantityAfterSelect = useRef(false)
  const lastProductEnter = useRef<number | null>(null)
  useEffect(() => {
    // Runs after the quantity input is enabled for the newly selected product.
    if (!selectedProductId || !focusQuantityAfterSelect.current) return
    focusQuantityAfterSelect.current = false
    quantityInput.current?.focus()
    quantityInput.current?.select()
  }, [selectedProductId])
  function focusProductSearch(open = false) {
    // Selecting a customer remounts the product search with the new customer's price list.
    window.setTimeout(() => productSearch.current?.focus({ open }), 0)
  }
  function addFromKeyboard(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== 'Enter') return
    event.preventDefault()
    if (addProduct()) focusProductSearch(!editOrder)
  }

  function handleProductPickerKeyDown(event: KeyboardEvent<HTMLElement>) {
    const input = event.target
    if (event.defaultPrevented || event.key !== 'Enter' || event.nativeEvent.isComposing ||
        !(input instanceof HTMLInputElement) || input.getAttribute('role') !== 'combobox') {
      lastProductEnter.current = null
      return
    }

    event.preventDefault()
    if (event.repeat) return
    if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || !lines.length || selectedProductId) {
      lastProductEnter.current = null
      return
    }

    const previousEnter = lastProductEnter.current
    if (previousEnter !== null && event.timeStamp - previousEnter <= DOUBLE_ENTER_WINDOW_MS) {
      lastProductEnter.current = null
      input.form?.querySelector<HTMLButtonElement>('button[type="submit"]')?.click()
    } else {
      lastProductEnter.current = event.timeStamp
    }
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
    if (!customer) { setError('Seleccioná un cliente disponible.'); return undefined }
    if (!resolvedListId) { setError('Seleccioná una lista de precios.'); return undefined }
    if (!selectedList) { setError('La lista asignada no está disponible. Seleccioná una lista activa.'); return undefined }
    if (lines.length === 0) { setError('Agregá al menos un producto al pedido.'); return undefined }
    if (selectedPreviousBalance > Math.max(0, customerBalance) && selectedPreviousBalance !== editOrder?.order.previousBalanceAmount) { setError('El importe del saldo anterior supera el saldo actual del cliente.'); return undefined }
    if (resolutions.some((resolution) => resolution.isLoading)) { setError('Esperá a que se resuelvan los precios antes de confirmar.'); return undefined }
    if (resolutions.some((resolution) => resolution.isError || !resolution.data)) { setError('No se pudo resolver el precio de una línea. Revisá la lista y los productos.'); return undefined }

    const payloadLines: ConfirmationRequest['lines'] = []
    for (const [index, line] of lines.entries()) {
      const quantity = quantityValue(line.quantity)
      const discount = quantityValue(line.lineDiscountPercent || '0')
      const override = (canOverride || Boolean(editOrder)) && line.unitPriceOverride.trim() ? quantityValue(line.unitPriceOverride) : undefined
      if (!validQuantity(line.quantity)) { setError(`La cantidad de ${productsById.get(line.productId)?.name ?? 'el producto'} debe ser positiva y múltiplo de 0,5.`); return undefined }
      if (!Number.isFinite(discount) || discount < 0 || discount > 100) { setError('Los descuentos deben estar entre 0 y 100%.'); return undefined }
      if (override !== undefined && (!Number.isFinite(override) || override < 0)) { setError('El precio manual debe ser un número no negativo.'); return undefined }
      payloadLines.push({ productId: line.productId, quantity, lineDiscountPercent: discount, ...(override !== undefined ? { unitPriceOverride: override } : {}) })
      if (resolutions[index].data?.unitPrice === undefined) { setError('Falta resolver el precio del producto.'); return undefined }
    }

    const discount = orderDiscountValue
    if (!Number.isFinite(discount) || discount < 0 || discount > 100) { setError('El descuento general debe estar entre 0 y 100%.'); return undefined }
    if (!editOrder) {
      if (!Number.isFinite(orderPaid) || orderPaid < 0) { setError('Ingresá un importe válido para el pago de este pedido.'); return undefined }
      if (orderPaid > previewTotal + 0.00005) { setError(`El pago de este pedido no puede superar su total (${money(previewTotal)}).`); return undefined }
      if (!Number.isFinite(debtPaid) || debtPaid < 0) { setError('Ingresá un importe válido para el pago de deuda anterior.'); return undefined }
      if (debtPaid > Math.max(0, accountBalance) + 0.00005) { setError(`El pago de deuda anterior no puede superar la deuda actual (${money(Math.max(0, accountBalance))}).`); return undefined }
    }
    const payments = !editOrder && canCollect && orderPaid > 0 ? [collectionPayment(orderPayment, orderPaid)] : []
    const previousDebtPayments = !editOrder && canCollect && debtPaid > 0 ? [collectionPayment(debtPayment, debtPaid)] : []
    return { idempotencyKey: (draftIsSaved && savedDraft?.idempotencyKey) || createIdempotencyKey(), customerId, ...(!editOrder ? { orderDate: parsedOrderDate! } : {}), ...(isAdmin ? { sellerId: sellerId || null } : {}), priceListId: resolvedListId, lines: payloadLines, orderDiscountPercent: discount, ...(selectedPreviousBalance > 0 ? { previousBalanceAmount: selectedPreviousBalance } : {}), payments, ...(previousDebtPayments.length ? { previousDebtPayments } : {}) }
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
        await invalidateDomains(queryClient, 'orders', 'sales', 'customers', 'stock', 'reports')
        await onSaved?.()
        return
      }
      const result = await apiPost<ConfirmationResponse>('/api/orders/confirm', payload)
      setResponseData(result)
      setAttempt(undefined)
      if (draftStorageKey && savedDraft && readOrderDraft(draftStorageKey)?.idempotencyKey === savedDraft.idempotencyKey) {
        try {
          localStorage.removeItem(draftStorageKey)
        } catch {
          // The order is already confirmed. Retrying an unchanged saved draft uses the same key.
        }
      }
      await invalidateDomains(queryClient, 'orders', 'sales', 'customers', 'stock', 'reports')
    } catch (cause) {
      setError(editOrder && cause instanceof ApiError && cause.status === 403 ? 'Tu usuario no tiene permiso para editar este pedido.' : message(cause))
    } finally {
      setSubmitting(false)
    }
  }

  if (responseData) return <OrderConfirmationSummary
    customerName={customer?.name}
    customerId={customer?.id}
    responseData={responseData}
    notifyWhatsApp={notifyWhatsApp}
    notificationRequested={notificationRequested}
    onOpenNotify={() => setNotifyWhatsApp(true)}
    onCloseNotify={() => setNotifyWhatsApp(false)}
    onNotificationRequested={() => { setNotifyWhatsApp(false); setNotificationRequested(true) }}
    onBackToOrders={() => navigate('/orders')}
  />

  const failedQueries = [productsQuery, listsQuery, ...(!editOrder ? [customersQuery] : []),
    ...(isAdmin && !editOrder ? [sellersQuery] : [])].filter(query => query.isError)
  const readError = failedQueries[0]?.error
  const readLoading = (!editOrder && customersQuery.isLoading) || productsQuery.isLoading || listsQuery.isLoading || (isAdmin && !editOrder && sellersQuery.isLoading)

  const cancel = <Button variant="secondary" type="button" onClick={requestDiscard} disabled={submitting}>Cancelar</Button>
  const previousOrderAction = <div className="order-previous-action"><Button type="button" variant="secondary" onClick={loadPreviousOrder} disabled={!customerId || !selectedList || previousOrderQuery.isFetching || previousOrderQuery.isError || !previousOrderQuery.data?.available || submitting} title={!customerId ? 'Seleccioná un cliente para consultar su último pedido.' : previousOrderQuery.isFetching ? 'Consultando el último pedido del cliente.' : previousOrderQuery.isError ? 'No se pudo consultar el pedido anterior.' : !previousOrderQuery.data?.available ? 'Este cliente no tiene pedidos anteriores.' : 'Reemplazar los productos del borrador con los del último pedido del cliente.'}>Cargar pedido anterior</Button></div>
  const customerField = <CustomerSelect mode="selection" label="Cliente" options={customers} value={customerId} onChange={selectCustomer} required autoFocus={!editOrder} disabled={submitting || Boolean(editOrder)} />
  const sellerField = editOrder ? <SellerSelect mode="selection" label="Vendedor" options={[{ id: 'locked-seller', name: editOrder.order.seller ?? 'Sin asignar' }]} value="locked-seller" onChange={() => {}} disabled /> : isAdmin
    ? <SellerSelect mode="selection" label="Vendedor" options={(sellersQuery.data?.content ?? []).map((seller) => ({ id: seller.id, name: seller.displayName }))} value={sellerId} onChange={(value) => { draftChanged(); setSellerId(value) }} emptyLabel="Del cliente" disabled={submitting} />
    : <div className="field"><span>Vendedor</span><span className="read-only-field" aria-label="Vendedor">{customer?.seller || 'Seleccioná un cliente'}</span></div>
  const priceListField = <label className="field"><span>Lista de precios</span><select className="select" aria-label="Lista de precios" aria-invalid={Boolean(customerId && resolvedListId && !selectedList)} aria-describedby={customerId && resolvedListId && !selectedList ? 'order-price-list-error' : undefined} value={resolvedListId} onChange={(event) => selectPriceList(event.target.value)} required disabled={submitting || !canChangeList}><option value="">Seleccionar lista...</option>{resolvedListId && !selectedList && <option value={resolvedListId} disabled>Lista asignada no disponible</option>}{activeLists.map((list) => <option value={list.id} key={list.id}>{list.code} · {list.name}</option>)}</select>{customerId && resolvedListId && !selectedList && <span id="order-price-list-error" className="error-text">{canChangeList ? 'Seleccioná una lista activa para continuar.' : 'La lista asignada no está disponible. Solicitá su revisión a un administrador.'}</span>}</label>
  const conditions = <>
    {discountPrompt && <PreviousOrderDiscountDialog orderNumber={discountPrompt.order.orderNumber ?? ''} orderDiscount={Number(discountPrompt.order.orderDiscountPercent ?? 0)} items={discountPrompt.order.items ?? []} anchor={discountPrompt.anchor} onDismiss={() => setDiscountPrompt(undefined)} onChoose={(copyDiscounts) => { applyPreviousOrder(discountPrompt.order, copyDiscounts); setDiscountPrompt(undefined) }} />}
    {editOrder ? <div className="order-customer-grid">
      {customerField}
      {sellerField}
      {priceListField}
      {previousOrderAction}
    </div> : <div className="order-customer-grid order-create-conditions">
      <div className="w-full [@media(min-width:761px)]:w-[calc(35ch_+_44px)]">{customerField}</div>
      <div className="w-full [@media(min-width:761px)]:w-[calc(10ch_+_46px)] [@media(min-width:761px)]:shrink-0">
        <OrderDateFilter id="order-date" label="Fecha del pedido" value={orderDate} isoValue={parsedOrderDate ?? ''} invalid={invalidOrderDate} describedBy={invalidOrderDate ? 'order-date-error' : undefined} required disabled={submitting} onChange={(value) => { draftChanged(); setOrderDate(value) }} />
        {invalidOrderDate && <p id="order-date-error" className="error-text mt-[6px]" role="alert">Ingresá una fecha válida con formato dd/mm/aaaa.</p>}
      </div>
      <div className="w-full [@media(min-width:761px)]:w-[calc(20ch_+_44px)] [@media(min-width:761px)]:min-w-[min(100%,_112px)]">{sellerField}</div>
      <div className="w-full [@media(min-width:761px)]:w-[calc(30ch_+_40px)]">{priceListField}</div>
    </div>}
    {resellerId && <p className="helper-text">Cuenta: <strong>{resellerName}</strong> (revendedor). El pedido queda a nombre del cliente y la deuda se carga a esa cuenta corriente.</p>}
    {previousOrderQuery.isError && <div className="page-actions"><p className="error-text" role="alert">No se pudo consultar el pedido anterior.</p><Button type="button" variant="secondary" onClick={() => previousOrderQuery.refetch()}>Reintentar pedido anterior</Button></div>}
  </>
  const productsEditor = <OrderProductPicker
    isEdit={Boolean(editOrder)}
    canDiscount={canDiscount}
    canOverride={canOverride}
    customerId={customerId}
    hasSelectedList={Boolean(selectedList)}
    submitting={submitting}
    products={products}
    productsById={productsById}
    lines={lines}
    lineColumns={lineColumns}
    lineRows={lineRows}
    invalidQuantityLines={invalidQuantityLines}
    selectedProductId={selectedProductId}
    selectedProductQuantity={selectedProductQuantity}
    selectedProductDiscount={selectedProductDiscount}
    selectedProductPriceOverride={selectedProductPriceOverride}
    selectedPriceQuery={selectedPriceQuery}
    invalidSelectedQuantity={invalidSelectedQuantity}
    productSearchRef={productSearch}
    quantityInputRef={quantityInput}
    onSelectProduct={(value) => { setSelectedProductId(value); setSelectedProductQuantity('1'); setSelectedProductDiscount('0'); setSelectedProductPriceOverride(undefined); setError(''); focusQuantityAfterSelect.current = Boolean(value) }}
    onChangeQuantity={setSelectedProductQuantity}
    onChangeDiscount={setSelectedProductDiscount}
    onChangePriceOverride={setSelectedProductPriceOverride}
    onAddButtonClick={(event) => { if (addProduct()) focusProductSearch(!editOrder && event.detail === 0) }}
    onAddFromKeyboard={addFromKeyboard}
    onPickerKeyDown={editOrder ? undefined : handleProductPickerKeyDown}
    onBlurPicker={() => { lastProductEnter.current = null }}
    onPointerDownPicker={() => { lastProductEnter.current = null }}
  />
  const accountAndCollection = <>
    {customer && resellerId && isAdmin && <section className="order-account" aria-labelledby="order-account-title">
      <div className="order-account-balance"><h3 id="order-account-title">Cuenta corriente de {resellerName}</h3><span>Saldo actual</span><strong>{resellerBalanceQuery.isError ? 'No disponible' : <CurrencyText>{money(accountBalance)}</CurrencyText>}</strong></div>
      <p className="helper-text">El remito del cliente no incluye saldo anterior: la deuda es del revendedor.</p>
    </section>}
    {customer && !resellerId && <section className="order-account" aria-labelledby="order-account-title">
      <div className="order-account-balance"><h3 id="order-account-title">Cuenta corriente</h3><span>Saldo actual</span><strong><CurrencyText>{money(customerBalance)}</CurrencyText></strong></div>
      <div className="order-account-controls">
        <label className="field"><span>Importe para el remito</span><input className="input currency-input" type="text" inputMode="decimal" value={previousBalanceInput} placeholder="0,00" aria-invalid={Boolean(previousBalanceError)} aria-describedby={previousBalanceError ? 'order-account-error' : undefined} onChange={(event) => { draftChanged(); setPreviousBalanceInput(event.target.value); setPreviousBalanceError('') }} disabled={customerBalance <= 0 || submitting} /></label>
        <Button type="button" variant="secondary" onClick={() => includePreviousBalance()} disabled={customerBalance <= 0 || submitting || !previousBalanceInput.trim()}>{selectedPreviousBalance > 0 ? 'Actualizar importe' : 'Agregar importe'}</Button>
        <Button type="button" variant="link" onClick={() => includePreviousBalance(String(customerBalance))} disabled={customerBalance <= 0 || submitting}>Agregar saldo total</Button>
      </div>
      {previousBalanceError && <p className="error-text" id="order-account-error" role="alert"><CurrencyText>{previousBalanceError}</CurrencyText></p>}
    </section>}
    {customer && !editOrder && !canCollect && <p className="helper-text">Los cobros de cuentas de revendedor los registra un administrador.</p>}
    {customer && !editOrder && canCollect && <section className="order-collection" aria-labelledby="order-collection-title">
      <h3 id="order-collection-title">Cobro al confirmar (opcional)</h3>
      <p className="helper-text">El pago de este pedido y el de la deuda anterior se registran por separado. Lo que no se cobre queda en la cuenta corriente.</p>
      {([
        ['order', 'Pago de este pedido', orderPayment, setOrderPayment, previewTotal, 'Cobrar el total del pedido', 'Se aplica solo a este pedido.'],
        ...(accountBalance > 0 ? [['debt', 'Pago de deuda anterior', debtPayment, setDebtPayment, accountBalance, 'Cobrar toda la deuda', resellerId ? `Deuda anterior de ${resellerName}: ${money(accountBalance)}. Se aplica a las deudas más antiguas de su cuenta, nunca a este pedido.` : `Deuda anterior: ${money(accountBalance)}. Se aplica a las deudas más antiguas, nunca a este pedido.`]] : []),
      ] as Array<[string, string, CollectionDraft, (value: CollectionDraft) => void, number, string, string]>).map(([key, title, draft, setDraft, max, fillLabel, help]) => <fieldset className="order-collection-row" key={key} disabled={submitting}>
        <legend>{title}</legend>
        <label className="field"><span>Medio de pago</span><select className="select" aria-label={`Medio: ${title}`} value={draft.method} onChange={(event) => { draftChanged(); setDraft({ ...draft, method: event.target.value as CollectionDraft['method'] }) }}><option value="CASH">Efectivo</option><option value="BANK_TRANSFER">Transferencia</option></select></label>
        <label className="field"><span>Importe</span><input className="input currency-input" type="text" inputMode="decimal" aria-label={`Importe: ${title}`} placeholder="0,00" value={draft.amount} onChange={(event) => { draftChanged(); setDraft({ ...draft, amount: event.target.value }) }} /></label>
        {draft.method === 'BANK_TRANSFER' && <label className="field"><span>Nº de transferencia</span><input className="input" aria-label={`Número de transferencia: ${title}`} maxLength={100} value={draft.reference} onChange={(event) => { draftChanged(); setDraft({ ...draft, reference: event.target.value }) }} /></label>}
        <Button type="button" variant="link" disabled={max <= 0} onClick={() => { draftChanged(); setDraft({ ...draft, amount: String(Number(max.toFixed(4))).replace('.', ',') }) }}>{fillLabel}</Button>
        <p className="helper-text order-collection-help"><CurrencyText>{help}</CurrencyText></p>
      </fieldset>)}
      {(orderPaid > 0 || debtPaid > 0) && Number.isFinite(orderPaid) && Number.isFinite(debtPaid) && <dl className="order-collection-summary" aria-live="polite">
        <div><dt>Cobrado ahora</dt><dd><CurrencyText>{money(orderPaid + debtPaid)}</CurrencyText></dd></div>
        <div><dt>Queda de este pedido en cuenta corriente</dt><dd><CurrencyText>{money(Math.max(0, previewTotal - orderPaid))}</CurrencyText></dd></div>
      </dl>}
    </section>}
  </>
  const confirmDisabled = submitting || (!editOrder && (!customer || !selectedList)) || invalidOrderDate || invalidOrderDiscount || invalidQuantityLines.length > 0 || resolutions.some((resolution) => resolution.isLoading) || lines.length === 0
  const confirmLabel = editOrder ? (submitting ? 'Guardando...' : 'Guardar cambios') : (submitting ? 'Confirmando...' : attempt ? 'Reintentar confirmación' : 'Confirmar pedido')
  const units = lines.reduce((sum, line) => sum + (validQuantity(line.quantity) ? quantityValue(line.quantity) : 0), 0)

  return <>
    {editOrder ? <PageHeader eyebrow="Operación" title="Editar pedido" description={editOrder.order.number} actions={cancel} />
      : <header className="order-create-header">
        <nav aria-label="Ruta de navegación" className="order-create-breadcrumb">
          <span>Operación</span><span aria-hidden="true"> / </span><Link to="/orders" onClick={(event) => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return
            event.preventDefault()
            requestDiscard()
          }}>Pedidos</Link>
        </nav>
        <div className="page-header">
          <h1>Nuevo pedido</h1>
          {cancel}
        </div>
      </header>}
    {discardDialog}
    {readLoading ? <Panel><EmptyState state="loading" title="Cargando datos del pedido" description={isAdmin ? 'Consultando clientes, productos, vendedores y listas activas.' : 'Consultando clientes, productos y listas activas.'} /></Panel>
      : readError ? <Panel><EmptyState state="error" title="No se pudo preparar el pedido" description={readError.message} action={<Button type="button" variant="secondary" onClick={() => failedQueries.forEach(query => void query.refetch())}>
        {failedQueries.length === 1 && sellersQuery.isError ? 'Reintentar vendedores' : 'Reintentar'}
      </Button>} /></Panel>
        : <form className={`order-form${editOrder ? '' : ' order-create-form'}`} onSubmit={confirm}>
          {error && <p className="error-text order-form-error" role="alert"><CurrencyText>{error}</CurrencyText></p>}
          {editOrder ? <Panel title="Datos del pedido">
            {conditions}
            {productsEditor}
            {accountAndCollection}
            <OrderCheckoutSummary
              isEdit
              canDiscount={canDiscount}
              submitting={submitting}
              orderDiscountPercent={orderDiscountPercent}
              orderDiscountValue={orderDiscountValue}
              invalidOrderDiscount={invalidOrderDiscount}
              invalidQuantityCount={invalidQuantityLines.length}
              previewSubtotal={previewSubtotal}
              previewDiscount={previewDiscount}
              previewTotal={previewTotal}
              selectedPreviousBalance={selectedPreviousBalance}
              confirmDisabled={confirmDisabled}
              confirmLabel={confirmLabel}
              hasUnsavedDraft={hasUnsavedDraft}
              draftIsSaved={draftIsSaved}
              draftSaveError={draftSaveError}
              onChangeOrderDiscount={changeOrderDiscount}
              onStepOrderDiscount={stepOrderDiscount}
              onSaveDraft={saveDraft}
            />
          </Panel> : <div className="order-create-layout">
            <div className="order-create-main">
              <section className="panel order-create-panel" aria-labelledby="order-conditions-title">
                <div className="panel-header">
                  <h2 id="order-conditions-title">Cliente y condiciones</h2>
                  {previousOrderAction}
                </div>
                {conditions}
              </section>
              <section className="panel order-create-panel" aria-labelledby="order-products-title">
                <div className="panel-header">
                  <h2 id="order-products-title">Productos</h2>
                </div>
                {productsEditor}
                <p className="order-create-line-count">
                  {lines.length} {lines.length === 1 ? 'producto' : 'productos'}
                  {invalidQuantityLines.length === 0 && ` · ${formatNumber(units, 2)} ${units === 1 ? 'unidad' : 'unidades'}`}
                </p>
              </section>
              {accountAndCollection}
            </div>
            <OrderCheckoutSummary
              isEdit={false}
              canDiscount={canDiscount}
              submitting={submitting}
              orderDiscountPercent={orderDiscountPercent}
              orderDiscountValue={orderDiscountValue}
              invalidOrderDiscount={invalidOrderDiscount}
              invalidQuantityCount={invalidQuantityLines.length}
              previewSubtotal={previewSubtotal}
              previewDiscount={previewDiscount}
              previewTotal={previewTotal}
              selectedPreviousBalance={selectedPreviousBalance}
              confirmDisabled={confirmDisabled}
              confirmLabel={confirmLabel}
              draftStorageKey={draftStorageKey}
              hasUnsavedDraft={hasUnsavedDraft}
              draftIsSaved={draftIsSaved}
              draftSaveError={draftSaveError}
              onChangeOrderDiscount={changeOrderDiscount}
              onStepOrderDiscount={stepOrderDiscount}
              onSaveDraft={saveDraft}
            />
          </div>}
        </form>}
  </>
}
