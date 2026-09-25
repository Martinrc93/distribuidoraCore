import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { Badge } from '../../shared/components/Badge'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import DepotInventorySection from './DepotInventorySection'
import { useUrlListState } from '../../shared/useUrlListState'

type InventoryItem = { id: string; product: string; stock: number; lastMovement?: string | null; updated?: string }
type Movement = { id: string; movementType: string; quantity: number; reason: string; referenceType?: string; referenceId?: string; depotCode?: string; date: string }
type Row = Record<string, string>

function date(value?: string) {
  return value ? new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—'
}

export default function InventoryPage() {
  const queryClient = useQueryClient()
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const { page: movementPage, setPage: setMovementPage } = useUrlListState([], 20, 'movementPage')
  const search = getFilter('search')
  const searchParam = search.trim() ? `&search=${encodeURIComponent(search.trim())}` : ''
  const inventoryPath = `/api/inventory?page=${page}&size=${pageSize}${searchParam}`
  const inventoryKey = [inventoryPath]
  const query = useQuery({ queryKey: inventoryKey, queryFn: () => apiGet<ApiPage<InventoryItem>>(inventoryPath) })
  const [selectedProduct, setSelectedProduct] = useState<InventoryItem | undefined>()
  const [selectedDepotId, setSelectedDepotId] = useState('')
  const [selectedDepotCode, setSelectedDepotCode] = useState('')
  const movementsPath = selectedProduct ? `/api/inventory/${selectedProduct.id}/movements?page=${movementPage}&size=${pageSize}` : ''
  const movementsKey = [movementsPath]
  const movementsQuery = useQuery({ queryKey: movementsKey, queryFn: () => apiGet<ApiPage<Movement>>(movementsPath), enabled: Boolean(selectedProduct) })
  const [adjustmentProduct, setAdjustmentProduct] = useState<InventoryItem | undefined>()
  const [adjustmentDepotId, setAdjustmentDepotId] = useState('')
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
      await apiPost(`/api/inventory/${adjustmentProduct.id}/adjustments`, { quantity: amount, reason: reason.trim(), ...(adjustmentDepotId ? { depotId: adjustmentDepotId } : {}) })
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/inventory?') })
      if (adjustmentDepotId) await queryClient.invalidateQueries({
        predicate: (cachedQuery) => typeof cachedQuery.queryKey[0] === 'string'
          && cachedQuery.queryKey[0].startsWith(`/api/inventory/depots/${adjustmentDepotId}/balances?`),
      })
      if (selectedProduct?.id === adjustmentProduct.id) await queryClient.invalidateQueries({ queryKey: movementsKey })
      setFeedback('Ajuste de inventario registrado.')
      setAdjustmentProduct(undefined)
      setAdjustmentDepotId('')
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
      return <div className="page-actions"><Button variant="link" onClick={() => { setSelectedProduct(item); setMovementPage(0) }}>Ver movimientos de {item.product}</Button></div>
    } },
  ]

  const movementRows: Row[] = (movementsQuery.data?.content ?? []).map((movement) => ({
    id: movement.id,
    type: movement.movementType,
    quantity: String(movement.quantity),
    reason: movement.reason,
    reference: movement.referenceType ? `${movement.referenceType} ${movement.referenceId ?? ''}`.trim() : '—',
    depot: movement.depotCode ?? 'CENTRAL',
    date: date(movement.date),
  }))
  const movementColumns: TableColumn[] = [
    { key: 'date', label: 'Fecha' },
    { key: 'type', label: 'Movimiento', render: (value) => <Badge tone={value.includes('CANCEL') || value === 'RETURN' ? 'muted' : 'soft'}>{value}</Badge> },
    { key: 'quantity', label: 'Cantidad', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{value}</span> },
    { key: 'reason', label: 'Motivo' },
    { key: 'depot', label: 'Depósito' },
    { key: 'reference', label: 'Referencia' },
  ]

  const projectedStock = adjustmentProduct ? Number(adjustmentProduct.stock) + Number(quantity || 0) : 0

  return <>
    <PageHeader eyebrow="Catálogo" title="Inventario" description="Saldos actuales y movimientos de stock." />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {pageError && <p className="error-text" role="alert">{pageError}</p>}
    <Panel title="Saldos por producto">
      <div className="toolbar"><label className="field"><span>Buscar productos</span><input className="input search-input" aria-label="Buscar inventario" placeholder="Nombre o SKU" value={search} onChange={(event) => setFilter('search', event.target.value)} /></label></div>
      {query.isLoading ? <EmptyState title="Cargando inventario" description="Consultando saldos actuales." /> : query.isError ? <EmptyState title="No se pudo cargar el inventario" description={query.error.message} /> : rows.length === 0 ? <EmptyState title={search ? 'No hay saldos para mostrar' : 'Todavía no hay saldos'} description={search ? 'Probá otra búsqueda.' : 'Los saldos aparecen cuando se crean productos.'} /> : <><DataTable columns={columns} rows={rows} /><div className="pagination"><span>Página {page + 1} · {query.data?.totalElements ?? 0} productos</span><div><Button variant="secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>Anterior</Button><Button variant="secondary" onClick={() => setPage(page + 1)} disabled={page + 1 >= (query.data?.totalPages ?? 0)}>Siguiente</Button></div></div></>}
    </Panel>
    <DepotInventorySection
      selectedDepotId={selectedDepotId}
      onSelectedDepotChange={(depotId, depotCode) => { setSelectedDepotId(depotId); setSelectedDepotCode(depotCode ?? '') }}
      onAdjust={(item, depotId) => {
        setAdjustmentProduct({ id: item.productId, product: item.product, stock: item.stock })
        setAdjustmentDepotId(depotId)
        setQuantity('')
        setReason('')
        setAdjustmentError('')
      }}
    />
    {selectedProduct && <Panel title={`Movimientos · ${selectedProduct.product}`} action={<Button variant="link" onClick={() => { setSelectedProduct(undefined); setMovementPage(0) }}>Cerrar detalle</Button>}>
      {movementsQuery.isLoading ? <EmptyState title="Cargando movimientos" description="Consultando el historial de stock." /> : movementsQuery.isError ? <EmptyState title="No se pudieron cargar los movimientos" description={movementsQuery.error.message} /> : movementRows.length === 0 ? <EmptyState title="Sin movimientos" description="Este producto todavía no registra movimientos de stock." /> : <><DataTable columns={movementColumns} rows={movementRows} />{movementsQuery.data && movementsQuery.data.totalPages > 1 && <div className="pagination"><span>Página {movementPage + 1} de {movementsQuery.data.totalPages} · {movementsQuery.data.totalElements} movimientos</span><div><Button variant="secondary" onClick={() => setMovementPage(movementPage - 1)} disabled={movementPage === 0}>Anterior</Button><Button variant="secondary" onClick={() => setMovementPage(movementPage + 1)} disabled={movementPage + 1 >= movementsQuery.data.totalPages}>Siguiente</Button></div></div>}</>}
    </Panel>}
    {adjustmentProduct && <div role="dialog" aria-modal="true" aria-labelledby="adjustment-title" className="modal-backdrop"><Panel title="Ajustar inventario"><form className="form-grid" onSubmit={submitAdjustment}>
      <h2 id="adjustment-title">{adjustmentProduct.product} · saldo en depósito {adjustmentProduct.stock}</h2>
      <p className="helper-text">Depósito seleccionado: {selectedDepotCode || 'CENTRAL'}.</p>
      <label className="field"><span>Cantidad de ajuste</span><input className="input" aria-label="Cantidad de ajuste" type="text" inputMode="decimal" value={quantity} onChange={(event) => { setQuantity(event.target.value); setAdjustmentError('') }} required disabled={saving} /><small>Usá un valor positivo para sumar o negativo para descontar; múltiplos de 0,5.</small></label>
      <label className="field"><span>Motivo</span><input className="input" aria-label="Motivo" value={reason} onChange={(event) => setReason(event.target.value)} required maxLength={500} disabled={saving} /></label>
      {Number.isFinite(projectedStock) && quantity && projectedStock < 0 && <p className="warning-text" role="status">Advertencia: el saldo quedará negativo ({projectedStock}).</p>}
      {adjustmentError && <p className="error-text" role="alert">{adjustmentError}</p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => { setAdjustmentProduct(undefined); setAdjustmentDepotId('') }} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving}>{saving ? 'Guardando...' : 'Confirmar ajuste'}</Button></div>
    </form></Panel></div>}
  </>
}
