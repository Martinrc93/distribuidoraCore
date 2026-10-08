import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { apiGet, apiPost } from '../../shared/api/client'
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
import { decimal, money, purchaseProductsKey, supplierOrdersKey, suppliersOptionsKey, type PreviousSupplierOrder, type PurchaseProduct, type Supplier } from './supplierOrders'

type DraftLine = { productId: string; name: string; quantity: string; unitCost: string }
type Result = { id: string; number: string; total: number }
type Payload = { supplierId: string; orderDate: string; lines: Array<{ productId: string; quantity: number; unitCost: number }>; idempotencyKey: string }

export function SupplierOrderForm({ onClose, onSaved }: { onClose: () => void; onSaved: (result: Result) => void }) {
  const queryClient = useQueryClient()
  const formRef = useRef<HTMLFormElement>(null)
  const suppliers = useQuery({ queryKey: suppliersOptionsKey, queryFn: () => apiGetAllPages<Supplier>('/api/suppliers?page=0&size=100'), retry: false })
  const products = useQuery({ queryKey: purchaseProductsKey, queryFn: () => apiGetAllPages<PurchaseProduct>('/api/products?page=0&size=100&includeStock=false'), retry: false })
  const [supplierId, setSupplierId] = useState('')
  const [orderDate, setOrderDate] = useState(() => displayDate(todayInArgentina()))
  const [lines, setLines] = useState<DraftLine[]>([])
  const [productId, setProductId] = useState('')
  const [quantity, setQuantity] = useState('1')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [loadingPrevious, setLoadingPrevious] = useState(false)
  const [replacement, setReplacement] = useState<PreviousSupplierOrder>()
  const [attempt, setAttempt] = useState<Payload>()
  const previous = useQuery({ queryKey: ['supplier-orders-last', supplierId], queryFn: ({ signal }) => apiGet<PreviousSupplierOrder>(`/api/supplier-orders/supplier/${supplierId}/last-order`, signal), enabled: Boolean(supplierId), retry: false })
  const busy = saving || loadingPrevious
  const activeProducts = (products.data?.content ?? []).filter((product) => product.status === 'ACTIVE')
  const isoDate = parseDate(orderDate)
  const total = lines.reduce((sum, line) => sum + (decimal(line.quantity) * decimal(line.unitCost) || 0), 0)
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: Boolean(supplierId || lines.length || orderDate !== displayDate(todayInArgentina())), onDiscard: onClose, disabled: busy, protectUnload: true })

  useEffect(() => {
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    formRef.current?.querySelector<HTMLInputElement>('input')?.focus()
    return () => { if (trigger?.isConnected) trigger.focus({ preventScroll: true }) }
  }, [])

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
      if (!data?.available || !data.items?.length) throw new Error('Este proveedor no tiene pedidos anteriores.')
      if (data.items.some((item) => item.status !== 'ACTIVE')) throw new Error('El pedido anterior contiene productos inactivos. Revisa los productos antes de solicitar otro pedido.')
      if (lines.length) setReplacement(data)
      else applyPrevious(data)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'No se pudo cargar el pedido anterior.') }
    finally { setLoadingPrevious(false) }
  }
  function addProduct() {
    const product = activeProducts.find((candidate) => candidate.id === productId)
    if (!product || !Number.isFinite(decimal(quantity)) || decimal(quantity) <= 0) { setError('Selecciona un producto y una cantidad mayor a cero, con hasta cuatro decimales.'); return }
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
    const payload = attempt ?? { supplierId, orderDate: isoDate, lines: lines.map((line) => ({ productId: line.productId, quantity: decimal(line.quantity), unitCost: decimal(line.unitCost) })), idempotencyKey: crypto.randomUUID() }
    setAttempt(payload)
    setSaving(true)
    setError('')
    try {
      const result = await apiPost<Result>('/api/supplier-orders', payload)
      await Promise.all([queryClient.invalidateQueries({ queryKey: supplierOrdersKey }), queryClient.invalidateQueries({ queryKey: ['supplier-orders-last', supplierId] })])
      onSaved(result)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'No se pudo solicitar el pedido.') }
    finally { setSaving(false) }
  }

  return <>
    <Panel title="Nuevo pedido a proveedor" description="Registra productos, cantidades y costos estimados para coordinar la compra.">
      <form ref={formRef} onSubmit={submit} aria-label="Nuevo pedido a proveedor" aria-busy={busy}>
        <fieldset disabled={busy} className="m-0 min-w-0 border-0 p-0">
          <div className="form-grid items-end mb-[18px]">
            <SupplierSelect mode="selection" label="Proveedor" options={suppliers.data?.content ?? []} value={supplierId} onChange={(id) => { setSupplierId(id); setProductId(''); changed() }} loading={suppliers.isLoading} disabled={suppliers.isError} required />
            <OrderDateFilter id="supplier-order-date" label="Fecha del pedido" value={orderDate} isoValue={isoDate ?? ''} invalid={Boolean(orderDate && !isoDate)} required disabled={busy} onChange={(value) => { setOrderDate(value); changed() }} />
          </div>
          {suppliers.isError && <p className="error-text" role="alert">No se pudieron cargar los proveedores. <Button type="button" variant="link" onClick={() => void suppliers.refetch()}>Reintentar proveedores</Button></p>}
          <div className="page-actions mb-[18px]"><Button type="button" variant="secondary" disabled={!supplierId || previous.isFetching || previous.isError || !previous.data?.available} onClick={() => void loadPrevious()}>{loadingPrevious ? 'Cargando pedido...' : 'Cargar pedido anterior'}</Button></div>
          {supplierId && previous.isFetching && <p className="helper-text" role="status">Consultando el último pedido de este proveedor...</p>}
          {supplierId && previous.isError && <p className="error-text" role="alert">No se pudo consultar el pedido anterior. <Button type="button" variant="link" onClick={() => void previous.refetch()}>Reintentar pedido anterior</Button></p>}
          {supplierId && previous.isSuccess && !previous.data?.available && <p className="helper-text">Este proveedor no tiene pedidos anteriores.</p>}
          <div className="product-picker">
            <SearchableSelect label="Producto" options={activeProducts.filter((product) => !lines.some((line) => line.productId === product.id))} value={productId} onChange={(id) => { setProductId(id); setError('') }} allLabel="Seleccionar producto..." unavailableLabel="Producto no disponible" loadingLabel="Cargando productos…" loading={products.isLoading} disabled={!supplierId || products.isError} fullWidth />
            <label className="field"><span>Cantidad</span><input className="input" inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} /></label>
            <Button type="button" variant="secondary" disabled={!productId} onClick={addProduct}>Agregar producto</Button>
          </div>
          {products.isError && <p className="error-text" role="alert">No se pudieron cargar los productos. <Button type="button" variant="link" onClick={() => void products.refetch()}>Reintentar productos</Button></p>}
          <DataTable columns={[
            { key: 'name', label: 'Producto', emphasis: true },
            { key: 'quantity', label: 'Cantidad', render: (value, row) => <input className="input w-[120px] max-w-full" inputMode="decimal" aria-label={`Cantidad de ${row.name}`} value={value} onChange={(event) => { setLines((current) => current.map((line) => line.productId === row.id ? { ...line, quantity: event.target.value } : line)); changed() }} /> },
            { key: 'unitCost', label: 'Costo unitario', render: (value, row) => <input className="input w-[140px] max-w-full" inputMode="decimal" aria-label={`Costo de ${row.name}`} value={value} onChange={(event) => { setLines((current) => current.map((line) => line.productId === row.id ? { ...line, unitCost: event.target.value } : line)); changed() }} /> },
            { key: 'total', label: 'Subtotal', align: 'right' },
            { key: 'actions', label: 'Acciones', render: (_, row) => <Button type="button" variant="danger" aria-label={`Quitar ${row.name}`} onClick={() => { setLines((current) => current.filter((line) => line.productId !== row.id)); changed() }}>Quitar</Button> },
          ]} rows={lines.map((line) => ({ id: line.productId, name: line.name, quantity: line.quantity, unitCost: line.unitCost, total: money(decimal(line.quantity) * decimal(line.unitCost) || 0) }))} emptyContent={<p className="table-empty-message">Agrega productos para solicitar el pedido.</p>} />
          <p className="mt-[18px] text-[15px] font-bold">Total estimado: {money(total)}</p>
        </fieldset>
        {error && <p className="error-text" role="alert">{error}</p>}
        <div className="page-actions mt-[18px]"><Button type="button" variant="secondary" disabled={busy} onClick={requestDiscard}>Cancelar</Button><Button type="submit" disabled={busy || suppliers.isLoading || suppliers.isError || !supplierId || !lines.length || !isoDate}>{saving ? 'Guardando...' : 'Solicitar pedido'}</Button></div>
      </form>
    </Panel>
    {replacement && <ConfirmationDialog title="¿Reemplazar los productos?" description="Se copiarán los productos y cantidades del último pedido de este proveedor, con sus costos actuales. Los productos del borrador se reemplazarán." confirmLabel="Reemplazar productos" onConfirm={() => applyPrevious(replacement)} onCancel={() => setReplacement(undefined)} />}
    {discardDialog}
  </>
}
