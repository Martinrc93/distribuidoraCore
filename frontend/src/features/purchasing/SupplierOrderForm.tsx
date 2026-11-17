import { CurrencyText } from '../../shared/components/CurrencyText'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGet, apiPost, apiPut } from '../../shared/api/client'
import { apiGetAllPages } from '../../shared/api/pagination'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DataTable } from '../../shared/components/DataTable'
import { SupplierSelect } from '../../shared/components/EntitySelect'
import { Panel } from '../../shared/components/Panel'
import { SearchableSelect } from '../../shared/components/SearchableSelect'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { displayDate, parseDate, todayInArgentina } from '../dashboard/dashboardDates'
import { OrderDateFilter } from '../orders/OrderDateFilter'
import { decimal, isHalfUnit, money, purchaseProductsKey, suggestedRestockQuantity, supplierOrdersKey, suppliersOptionsKey, type PreviousSupplierOrder, type PurchaseProduct, type Supplier, type SupplierOrderDetail, type SupplierOrderResult } from './supplierOrders'

type DraftLine = { productId: string; name: string; quantity: string; unitCost: string }
type RestockProduct = PurchaseProduct & { stock: number; suppliers: Supplier[] }
type Payload = { supplierId: string; orderDate: string; lines: Array<{ productId: string; quantity: number; unitCost: number }>; idempotencyKey: string; confirm: boolean }

/**
 * Creates a purchase (as draft or confirmed) or edits an existing one when `initial` is given.
 * Confirming adds the quantities to stock; editing a confirmed purchase moves stock by the difference.
 */
type Props = { onClose: () => void; onSaved: (result: SupplierOrderResult) => void } & (
  { initial?: SupplierOrderDetail; restockProductId?: never } | { initial?: never; restockProductId: string }
)

export function SupplierOrderForm({ onClose, onSaved, initial, restockProductId }: Props) {
  const queryClient = useQueryClient()
  const formRef = useRef<HTMLFormElement>(null)
  const confirmIntent = useRef(false)
  const editing = Boolean(initial)
  const initialStatus = initial?.order.status
  const suppliers = useQuery({ queryKey: suppliersOptionsKey, queryFn: () => apiGetAllPages<Supplier>('/api/suppliers?page=0&size=100'), retry: false })
  const products = useQuery({ queryKey: purchaseProductsKey, queryFn: () => apiGetAllPages<PurchaseProduct>('/api/products?page=0&size=100&includeStock=true'), retry: false })
  const [supplierId, setSupplierId] = useState(initial?.order.supplierId ?? '')
  const [orderDate, setOrderDate] = useState(() => displayDate(initial?.order.date ?? todayInArgentina()))
  const [lines, setLines] = useState<DraftLine[]>(() => (initial?.items ?? []).map((item) => ({
    productId: item.productId, name: item.productName, quantity: String(item.quantity), unitCost: String(item.unitCost),
  })))
  const [restockProduct, setRestockProduct] = useState<RestockProduct | null>()
  const [productId, setProductId] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [loadingPrevious, setLoadingPrevious] = useState(false)
  const [replacement, setReplacement] = useState<PreviousSupplierOrder>()
  const [attempt, setAttempt] = useState<Payload>()
  const previous = useQuery({ queryKey: ['supplier-orders-last', supplierId], queryFn: ({ signal }) => apiGet<PreviousSupplierOrder>(`/api/supplier-orders/supplier/${supplierId}/last-order`, signal), enabled: Boolean(supplierId), retry: false })
  const busy = saving || loadingPrevious
  const waitingForRestock = Boolean(restockProductId && restockProduct === undefined)
  const activeProducts = (products.data?.content ?? []).filter((product) => product.status === 'ACTIVE')
  const isoDate = parseDate(orderDate)
  const total = lines.reduce((sum, line) => sum + (decimal(line.quantity) * decimal(line.unitCost) || 0), 0)
  const pristine = useRef(JSON.stringify({ supplierId, orderDate, lines }))
  const hasChanges = editing || restockProductId ? JSON.stringify({ supplierId, orderDate, lines }) !== pristine.current
    : Boolean(supplierId || lines.length || orderDate !== displayDate(todayInArgentina()))
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges, onDiscard: onClose, disabled: busy, protectUnload: true })

  useEffect(() => {
    if (!waitingForRestock || !products.isSuccess || products.isFetching || !suppliers.isSuccess || suppliers.isFetching) return
    const product = products.data.content.find((item) => item.id === restockProductId)
    if (!product || product.status !== 'ACTIVE' || product.stock === undefined
      || !Number.isFinite(product.stock) || !Number.isFinite(product.cost)) {
      setRestockProduct(null)
      return
    }
    const associated = suppliers.data.content.filter((supplier) => product.suppliers?.some((candidate) => candidate.id === supplier.id))
    const suggestedSupplier = associated.length === 1 ? associated[0].id : ''
    const suggestedLines = [{
      productId: product.id, name: product.name, quantity: suggestedRestockQuantity(product.stock), unitCost: String(product.cost),
    }]
    pristine.current = JSON.stringify({ supplierId: suggestedSupplier, orderDate, lines: suggestedLines })
    setSupplierId(suggestedSupplier)
    setLines(suggestedLines)
    setRestockProduct({ ...product, stock: product.stock, suppliers: associated })
  }, [waitingForRestock, restockProductId, orderDate, products.data, products.isSuccess, products.isFetching,
    suppliers.data, suppliers.isSuccess, suppliers.isFetching])

  useEffect(() => {
    if (waitingForRestock) return
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    formRef.current?.querySelector<HTMLInputElement>('input')?.focus()
    return () => { if (trigger?.isConnected) trigger.focus({ preventScroll: true }) }
  }, [waitingForRestock])

  function changed() { setAttempt(undefined); setError('') }
  function applyPrevious(data: PreviousSupplierOrder) {
    if (data.order?.supplierId !== supplierId || !data.items?.length) return
    setLines(data.items.map((item) => ({ productId: item.productId, name: item.productName, quantity: String(item.quantity), unitCost: String(item.currentCost) })))
    setProductId('')
    setReplacement(undefined)
    changed()
  }
  async function loadPrevious() {
    if (busy) return
    setLoadingPrevious(true)
    setError('')
    try {
      const result = await previous.refetch()
      if (result.error) throw result.error
      const data = result.data
      if (!data?.available || !data.items?.length) throw new Error('Este proveedor no tiene compras anteriores.')
      if (data.items.some((item) => item.status !== 'ACTIVE')) throw new Error('La compra anterior contiene productos inactivos. Revisa los productos antes de registrar otra compra.')
      if (lines.length) setReplacement(data)
      else applyPrevious(data)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'No se pudo cargar la compra anterior.') }
    finally { setLoadingPrevious(false) }
  }
  /** Products to restock: active ones with stock <= 0, limited to the supplier's products when it has associations. */
  const supplierProducts = (products.data?.content ?? []).filter((product) => product.suppliers?.some((candidate) => candidate.id === supplierId))
  const restockCandidates = (supplierProducts.length ? supplierProducts : activeProducts)
    .filter((product) => product.status === 'ACTIVE' && product.stock !== undefined && product.stock <= 0
      && !lines.some((line) => line.productId === product.id))
  function addRestock() {
    if (!restockCandidates.length) return
    // Suggest enough to cover the negative balance plus one unit, in half units; the user can adjust it.
    setLines((current) => [...current, ...restockCandidates.map((product) => ({
      productId: product.id, name: product.name, unitCost: String(product.cost),
      quantity: suggestedRestockQuantity(Number(product.stock)),
    }))])
    changed()
  }
  function addProduct() {
    const product = activeProducts.find((candidate) => candidate.id === productId)
    if (!product || !Number.isFinite(decimal(quantity)) || decimal(quantity) <= 0) { setError('Selecciona un producto y una cantidad mayor a cero, con hasta cuatro decimales.'); return }
    if (initialStatus !== 'LEGACY' && !isHalfUnit(decimal(quantity))) { setError('La cantidad debe ser múltiplo de 0,5.'); return }
    setLines((current) => [...current, { productId: product.id, name: product.name, quantity, unitCost: String(product.cost) }])
    setProductId('')
    setQuantity('1')
    changed()
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    if (!supplierId || !isoDate || !lines.length) { setError('Selecciona un proveedor, una fecha válida y al menos un producto.'); return }
    if (lines.some((line) => !Number.isFinite(decimal(line.quantity)) || decimal(line.quantity) <= 0 || !Number.isFinite(decimal(line.unitCost)) || decimal(line.unitCost) < 0)) {
      setError('Revisa las cantidades y costos: deben ser válidos y tener hasta cuatro decimales.'); return
    }
    if (initialStatus !== 'LEGACY' && lines.some((line) => !isHalfUnit(decimal(line.quantity)))) {
      setError('Las cantidades deben ser múltiplos de 0,5 porque la compra mueve el stock.'); return
    }
    const confirm = !editing && confirmIntent.current
    const sameAttempt = attempt && attempt.confirm === confirm
    const payload = sameAttempt ? attempt : { supplierId, orderDate: isoDate, lines: lines.map((line) => ({ productId: line.productId, quantity: decimal(line.quantity), unitCost: decimal(line.unitCost) })), idempotencyKey: crypto.randomUUID(), confirm }
    setAttempt(payload)
    setSaving(true)
    setError('')
    try {
      const result = initial
        ? await apiPut<SupplierOrderDetail>(`/api/supplier-orders/${initial.order.id}`, { supplierId: payload.supplierId, orderDate: payload.orderDate, lines: payload.lines })
          .then((detail) => ({ id: detail.order.id, number: detail.order.number, total: detail.order.total, status: detail.order.status }))
        : await apiPost<SupplierOrderResult>('/api/supplier-orders', payload)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: supplierOrdersKey }),
        queryClient.invalidateQueries({ queryKey: ['supplier-orders-last', supplierId] }),
        queryClient.invalidateQueries({ predicate: ({ queryKey }) => {
          const key = String(queryKey[0])
          return key.startsWith('/api/products') || key.startsWith('/api/inventory')
            || key === 'dashboard' || key === 'order-create-products'
        } }),
      ])
      onSaved(result)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'No se pudo registrar la compra.') }
    finally { setSaving(false) }
  }

  if (waitingForRestock || restockProduct === null) {
    return <Panel title="Nueva compra">
      {restockProduct === null
        ? <p role="alert" className="error-text">El producto ya no está disponible para reposición.</p>
        : <>
          {products.isError && <p role="alert" className="error-text">No se pudo consultar el producto. <Button type="button" variant="link" onClick={() => void products.refetch()}>Reintentar productos</Button></p>}
          {suppliers.isError && <p role="alert" className="error-text">No se pudieron cargar los proveedores. <Button type="button" variant="link" onClick={() => void suppliers.refetch()}>Reintentar proveedores</Button></p>}
          {(!products.isError && !suppliers.isError || products.isFetching || suppliers.isFetching)
            && <p role="status" className="helper-text">Cargando el stock y los proveedores actuales.</p>}
        </>}
      <div className="page-actions"><Button type="button" variant="secondary" onClick={onClose}>Volver</Button></div>
    </Panel>
  }

  return <>
    <Panel title={initial ? `Editar compra ${initial.order.number}` : 'Nueva compra'} description={!initial ? 'Guardala como borrador o confirmala: al confirmar, las cantidades se suman al stock.' : initialStatus === 'CONFIRMED' ? 'La compra está confirmada: al guardar, el stock se ajusta por la diferencia de cantidades.' : initialStatus === 'LEGACY' ? 'Compra registrada antes de que las compras movieran stock: editarla no cambia el stock.' : 'Borrador: los cambios no mueven stock hasta confirmar la compra.'}>
      {restockProduct && <p className="helper-text mb-[18px] [overflow-wrap:anywhere]">
        Stock de {restockProduct.name} al abrir: {new Intl.NumberFormat('es-AR').format(restockProduct.stock)}.
      </p>}
      <form ref={formRef} onSubmit={submit} aria-label={initial ? `Editar compra ${initial.order.number}` : 'Nueva compra'} aria-busy={busy}>
        <fieldset disabled={busy} className="m-0 min-w-0 border-0 p-0">
          <div className="form-grid items-end mb-[18px]">
            <SupplierSelect mode="selection" label="Proveedor" options={suppliers.data?.content ?? []} value={supplierId} onChange={(id) => { setSupplierId(id); setProductId(''); changed() }} loading={suppliers.isLoading} disabled={suppliers.isError} required />
            <OrderDateFilter id="supplier-order-date" label="Fecha de la compra" value={orderDate} isoValue={isoDate ?? ''} invalid={Boolean(orderDate && !isoDate)} required disabled={busy} onChange={(value) => { setOrderDate(value); changed() }} />
          </div>
          {suppliers.isError && <p className="error-text" role="alert">No se pudieron cargar los proveedores. <Button type="button" variant="link" onClick={() => void suppliers.refetch()}>Reintentar proveedores</Button></p>}
          {restockProduct && restockProduct.suppliers.length > 1 && <p className="helper-text [overflow-wrap:anywhere]">
            Proveedores asociados a {restockProduct.name}: {restockProduct.suppliers.map((supplier) => supplier.name).join(', ')}.
          </p>}
          <div className="page-actions mb-[18px]"><Button type="button" variant="secondary" disabled={!supplierId || previous.isFetching || previous.isError || !previous.data?.available} onClick={() => void loadPrevious()}>{loadingPrevious ? 'Cargando compra...' : 'Cargar compra anterior'}</Button><Button type="button" variant="secondary" disabled={!supplierId || products.isLoading || !restockCandidates.length} title={supplierId && !products.isLoading && !restockCandidates.length ? 'No hay productos sin stock para agregar.' : undefined} onClick={addRestock}>{`Agregar productos sin stock${supplierId && restockCandidates.length ? ` (${restockCandidates.length})` : ''}`}</Button></div>
          {supplierId && previous.isFetching && <p className="helper-text" role="status">Consultando el última compra a este proveedor...</p>}
          {supplierId && previous.isError && <p className="error-text" role="alert">No se pudo consultar la compra anterior. <Button type="button" variant="link" onClick={() => void previous.refetch()}>Reintentar compra anterior</Button></p>}
          {supplierId && previous.isSuccess && !previous.data?.available && <p className="helper-text">Este proveedor no tiene compras anteriores.</p>}
          <div className="product-picker">
            <SearchableSelect label="Producto" options={activeProducts.filter((product) => !lines.some((line) => line.productId === product.id))} value={productId} onChange={(id) => { setProductId(id); setError('') }} allLabel="Seleccionar producto..." unavailableLabel="Producto no disponible" loadingLabel="Cargando productos…" loading={products.isLoading} disabled={!supplierId || products.isError} fullWidth />
            <label className="field"><span>Cantidad</span><input className="input" inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} /></label>
            <Button type="button" variant="secondary" disabled={!productId} onClick={addProduct}>Agregar producto</Button>
          </div>
          {products.isError && <p className="error-text" role="alert">No se pudieron cargar los productos. <Button type="button" variant="link" onClick={() => void products.refetch()}>Reintentar productos</Button></p>}
          <DataTable columns={[
            { key: 'name', label: 'Producto', emphasis: true },
            { key: 'quantity', label: 'Cantidad', render: (value, row) => <input className="input w-[120px] max-w-full" inputMode="decimal" aria-label={`Cantidad de ${row.name}`} value={value} onChange={(event) => { setLines((current) => current.map((line) => line.productId === row.id ? { ...line, quantity: event.target.value } : line)); changed() }} /> },
            { key: 'unitCost', label: 'Costo unitario', render: (value, row) => <input className="input w-[140px] max-w-full currency-input" inputMode="decimal" aria-label={`Costo de ${row.name}`} value={value} onChange={(event) => { setLines((current) => current.map((line) => line.productId === row.id ? { ...line, unitCost: event.target.value } : line)); changed() }} /> },
            { key: 'total', label: 'Subtotal', align: 'right' },
            { key: 'actions', label: 'Acciones', render: (_, row) => <Button type="button" variant="danger" aria-label={`Quitar ${row.name}`} onClick={() => { setLines((current) => current.filter((line) => line.productId !== row.id)); changed() }}>Quitar</Button> },
          ]} rows={lines.map((line) => ({ id: line.productId, name: line.name, quantity: line.quantity, unitCost: line.unitCost, total: money(decimal(line.quantity) * decimal(line.unitCost) || 0) }))} emptyContent={<p className="table-empty-message">Agrega productos para registrar la compra.</p>} />
          <p className="mt-[18px] text-[15px] font-bold">Total estimado: <CurrencyText>{money(total)}</CurrencyText></p>
        </fieldset>
        {error && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
        <div className="page-actions mt-[18px]">
          <Button type="button" variant="secondary" disabled={busy} onClick={requestDiscard}>Cancelar</Button>
          {editing
            ? <Button type="submit" disabled={busy || suppliers.isLoading || suppliers.isError || !supplierId || !lines.length || !isoDate}>{saving ? 'Guardando...' : 'Guardar cambios'}</Button>
            : <>
              <Button type="submit" variant="secondary" onClick={() => { confirmIntent.current = false }} disabled={busy || suppliers.isLoading || suppliers.isError || !supplierId || !lines.length || !isoDate}>{saving && !confirmIntent.current ? 'Guardando...' : 'Guardar borrador'}</Button>
              <Button type="submit" onClick={() => { confirmIntent.current = true }} disabled={busy || suppliers.isLoading || suppliers.isError || !supplierId || !lines.length || !isoDate}>{saving && confirmIntent.current ? 'Confirmando...' : 'Confirmar compra'}</Button>
            </>}
        </div>
      </form>
    </Panel>
    {replacement && <ConfirmationDialog title="¿Reemplazar los productos?" description="Se copiarán los productos y cantidades de la última compra a este proveedor, con sus costos actuales. Los productos del borrador se reemplazarán." confirmLabel="Reemplazar productos" onConfirm={() => applyPrevious(replacement)} onCancel={() => setReplacement(undefined)} />}
    {discardDialog}
  </>
}
