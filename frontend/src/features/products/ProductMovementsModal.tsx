import type { UseQueryResult } from '@tanstack/react-query'
import type { ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { FormModal } from '../../shared/components/FormModal'
import { Panel } from '../../shared/components/Panel'
import { formatBusinessDateTime } from '../../shared/dates'
import { formatNumber } from '../../shared/format'

export type Movement = {
  id: string
  movementType: string
  quantity: number
  reason: string
  referenceType?: string
  referenceId?: string
  date: string
}

const movementName: Record<string, string> = {
  SALE: 'Venta',
  SALE_CANCELLATION: 'Cancelación de venta',
  MANUAL_ENTRY: 'Entrada manual',
  MANUAL_ADJUSTMENT: 'Ajuste manual',
  RETURN: 'Devolución',
  TRANSFER_OUT: 'Transferencia saliente',
  TRANSFER_IN: 'Transferencia entrante',
  PURCHASE: 'Compra',
  PURCHASE_ADJUSTMENT: 'Ajuste de compra',
  PURCHASE_CANCELLATION: 'Anulación de compra',
  SHRINKAGE: 'Merma',
}

const referenceTypeLabel: Record<string, string> = {
  ORDER: 'Pedido',
  SALE: 'Venta',
  SALE_RETURN: 'Devolución',
  SUPPLIER_ORDER: 'Compra',
  STOCK_ADJUSTMENT: 'Ajuste',
  STOCK_SHRINKAGE: 'Merma',
  INITIAL_STOCK: 'Stock inicial',
}

const movementColumns: TableColumn[] = [
  { key: 'date', label: 'Día y horario' },
  { key: 'type', label: 'Movimiento' },
  { key: 'quantity', label: 'Cantidad', align: 'right', render: (value) => <span className={Number(value) < 0 ? 'negative-number' : ''}>{formatNumber(value, 2)}</span> },
  { key: 'reason', label: 'Motivo' },
  { key: 'reference', label: 'Referencia' },
]

export function ProductMovementsModal({
  productName,
  movementsQuery,
  onClose,
}: {
  productName: string
  movementsQuery: UseQueryResult<ApiPage<Movement>, Error>
  onClose: () => void
}) {
  const movementRows = (movementsQuery.data?.content ?? []).map((movement) => ({
    id: movement.id,
    type: movementName[movement.movementType] ?? movement.movementType,
    quantity: String(movement.quantity),
    reason: movement.reason,
    reference: movement.referenceType ? `${referenceTypeLabel[movement.referenceType] ?? movement.referenceType} ${movement.referenceId ?? ''}`.trim() : '—',
    date: formatBusinessDateTime(movement.date),
  }))

  return <FormModal label={'Movimientos de ' + productName} className="product-movements-dialog" onClose={onClose}>
    <Panel title="Movimientos del producto" description={productName} action={<Button type="button" variant="link" onClick={onClose}>Cerrar</Button>}>
      {movementsQuery.isLoading ? <EmptyState state="loading" title="Cargando movimientos" description="Consultando el historial de stock." />
        : movementsQuery.isError ? <EmptyState state="error" title="No se pudieron cargar los movimientos" description={movementsQuery.error.message}
          action={<Button type="button" variant="secondary" onClick={() => void movementsQuery.refetch()}>Reintentar</Button>} />
        : movementRows.length === 0 ? <EmptyState title="Sin movimientos" description="No hay movimientos registrados para este producto." />
        : <DataTable className="product-movements-table" columns={movementColumns} rows={movementRows} />}
    </Panel>
  </FormModal>
}
