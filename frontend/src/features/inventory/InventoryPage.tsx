import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type InventoryItem = { id: string; product: string; stock: number; lastMovement?: string | null; updated?: string }
type Movement = { id: string; movementType: string; quantity: number; reason: string; referenceType?: string; referenceId?: string; date: string }
type Row = Record<string, string>

const INVENTORY_PATH = '/api/inventory?page=0&size=20'
const INVENTORY_KEY = [INVENTORY_PATH]

function date(value?: string) {
  return value ? new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

export default function InventoryPage() {
  const canAdjust = hasAuthority('STOCK_ADJUST') || hasAuthority('ADMIN_ALL')
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: INVENTORY_KEY, queryFn: () => apiGet<ApiPage<InventoryItem>>(INVENTORY_PATH) })
  const [selectedProduct, setSelectedProduct] = useState<InventoryItem | undefined>()
  const movementsPath = selectedProduct ? `/api/inventory/${selectedProduct.id}/movements?page=0&size=20` : ''
  const movementsKey = [movementsPath]
  const movementsQuery = useQuery({ queryKey: movementsKey, queryFn: () => apiGet<ApiPage<Movement>>(movementsPath), enabled: Boolean(selectedProduct) })
  const [adjustmentProduct, setAdjustmentProduct] = useState<InventoryItem | undefined>()
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [adjustmentError, setAdjustmentError] = useState('')
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState('')
  const [pageError, setPageError] = useState('')

  async function submitAdjustment(event: FormEvent) {
    event.preventDefault()
    if (!adjustmentProduct || saving) return
    const amount = Number(quantity)
    if (!quantity.trim() || !Number.isFinite(amount) || amount === 0) {
      setAdjustmentError('Ingresá una cantidad distinta de cero.')
      return
    }
    if (!Number.isInteger(amount * 2)) {
      setAdjustmentError('La cantidad debe ser múltiplo de 0,5.')
      return
    }
    if (!reason.trim()) {
      setAdjustmentError('Ingresá el motivo del ajuste.')
      return
    }
    setSaving(true)
    setAdjustmentError('')
    setPageError('')
    try {
      await apiPost(`/api/inventory/${adjustmentProduct.id}/adjustments`, { quantity: amount, reason: reason.trim() })
      await queryClient.invalidateQueries({ queryKey: INVENTORY_KEY })
      if (selectedProduct?.id === adjustmentProduct.id) await queryClient.invalidateQueries({ queryKey: movementsKey })
      setFeedback('Ajuste de inventario registrado.')
      setAdjustmentProduct(undefined)
      setQuantity('')
      setReason('')
    } catch (cause) {
      setAdjustmentError(cause instanceof ApiError && cause.status === 403 ? 'No tenés permiso para ajustar stock.' : cause instanceof Error ? cause.message : 'No se pudo registrar el ajuste.')
    } finally {
      setSaving(false)
    }
  }

  const rows: Row[] = (query.data?.content ?? []).map((item) => ({
    id: item.id,
    product: item.product,
    stock: String(item.stock),
    lastMovement: item.lastMovement ?? '—',
    updated: date(item.updated),
  }))
  const columns: TableColumn[] = [
    { key: 'product', label: 'Producto', emphasis: true },
    { key: 'stock', label: 'Saldo actual', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{value}</span> },
    { key: 'lastMovement', label: 'Último movimiento' },
    { key: 'updated', label: 'Actualizado' },
    { key: 'actions', label: '', render: (_value, row) => {
      const item = query.data?.content.find((candidate) => candidate.id === row.id)
      if (!item) return null
      return <div className="page-actions"><Button variant="link" onClick={() => setSelectedProduct(item)}>Ver movimientos de {item.product}</Button>{canAdjust && <Button variant="link" onClick={() => { setAdjustmentProduct(item); setQuantity(''); setReason(''); setAdjustmentError('') }}>Ajustar stock de {item.product}</Button>}</div>
    } },
  ]

  const movementRows: Row[] = (movementsQuery.data?.content ?? []).map((movement) => ({
    id: movement.id,
    type: movement.movementType,
    quantity: String(movement.quantity),
    reason: movement.reason,
    reference: movement.referenceType ? `${movement.referenceType} ${movement.referenceId ?? ''}`.trim() : '—',
    date: date(movement.date),
  }))
  const movementColumns: TableColumn[] = [
    { key: 'date', label: 'Fecha' },
    { key: 'type', label: 'Movimiento', render: (value) => <Badge tone={value.includes('CANCEL') || value === 'RETURN' ? 'muted' : 'soft'}>{value}</Badge> },
    { key: 'quantity', label: 'Cantidad', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{value}</span> },
    { key: 'reason', label: 'Motivo' },
    { key: 'reference', label: 'Referencia' },
  ]

  const projectedStock = adjustmentProduct ? Number(adjustmentProduct.stock) + Number(quantity || 0) : 0

  return <>
    <PageHeader eyebrow="Catálogo" title="Inventario" description="Saldos actuales y movimientos de stock." />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {pageError && <p className="error-text" role="alert">{pageError}</p>}
    <Panel title="Saldos por producto">
      {query.isLoading ? <EmptyState title="Cargando inventario" description="Consultando saldos actuales." /> : query.isError ? <EmptyState title="No se pudo cargar el inventario" description={query.error.message} /> : rows.length === 0 ? <EmptyState title="Todavía no hay saldos" description="Los saldos aparecen cuando se crean productos." /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Mostrando hasta 20 de {query.data?.totalElements ?? 0} productos</span><div><Button variant="secondary" disabled>Anterior</Button><Button variant="secondary" disabled={(query.data?.totalElements ?? 0) <= 20}>Siguiente</Button></div></div></>}
    </Panel>
    {selectedProduct && <Panel title={`Movimientos · ${selectedProduct.product}`} action={<Button variant="link" onClick={() => setSelectedProduct(undefined)}>Cerrar detalle</Button>}>
      {movementsQuery.isLoading ? <EmptyState title="Cargando movimientos" description="Consultando el historial de stock." /> : movementsQuery.isError ? <EmptyState title="No se pudieron cargar los movimientos" description={movementsQuery.error.message} /> : movementRows.length === 0 ? <EmptyState title="Sin movimientos" description="Este producto todavía no registra movimientos de stock." /> : <DataTable columns={movementColumns} rows={movementRows} />}
    </Panel>}
    {adjustmentProduct && <div role="dialog" aria-modal="true" aria-labelledby="adjustment-title" className="modal-backdrop"><Panel title="Ajustar inventario"><form className="form-grid" onSubmit={submitAdjustment}>
      <h2 id="adjustment-title">{adjustmentProduct.product} · saldo actual {adjustmentProduct.stock}</h2>
      <label className="field"><span>Cantidad de ajuste</span><input className="input" aria-label="Cantidad de ajuste" type="text" inputMode="decimal" value={quantity} onChange={(event) => { setQuantity(event.target.value); setAdjustmentError('') }} required disabled={saving} /><small>Usá un valor positivo para sumar o negativo para descontar; múltiplos de 0,5.</small></label>
      <label className="field"><span>Motivo</span><input className="input" aria-label="Motivo" value={reason} onChange={(event) => setReason(event.target.value)} required maxLength={500} disabled={saving} /></label>
      {Number.isFinite(projectedStock) && quantity && projectedStock < 0 && <p className="warning-text" role="status">Advertencia: el saldo quedará negativo ({projectedStock}).</p>}
      {adjustmentError && <p className="error-text" role="alert">{adjustmentError}</p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setAdjustmentProduct(undefined)} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Confirmar ajuste'}</Button></div>
    </form></Panel></div>}
  </>
}
