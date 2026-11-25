import { useEffect, useId, useRef } from 'react'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import type { ApiPage } from '../../shared/api/client'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { ConfirmationDialog } from '../../shared/components/ConfirmationDialog'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { FormModal } from '../../shared/components/FormModal'
import { Pagination } from '../../shared/components/Pagination'
import { Panel } from '../../shared/components/Panel'
import { formatBusinessDateTimeNumeric } from '../../shared/dates'
import { formatMoney } from '../../shared/format'
import { formatEffectiveDate, readableError, type HistorySelection, type PriceHistory } from './pricingTypes'

type Props = {
  historySelection: HistorySelection
  isAdmin: boolean
  historyPage: number
  onChangePage: (page: number) => void
  historyQuery: UseQueryResult<ApiPage<PriceHistory>, Error>
  cancelHistoryPrice: UseMutationResult<string, Error, string>
  historyDateToCancel?: string
  onRequestCancelDate: (date: string | undefined) => void
  onClose: () => void
}

export function PriceHistoryModal({
  historySelection,
  isAdmin,
  historyPage,
  onChangePage,
  historyQuery,
  cancelHistoryPrice,
  historyDateToCancel,
  onRequestCancelDate,
  onClose,
}: Props) {
  const historyDialogId = useId()
  const historyHeader = useRef<HTMLElement>(null)

  useEffect(() => {
    if (cancelHistoryPrice.isSuccess) historyHeader.current?.querySelector('button')?.focus({ preventScroll: true })
  }, [cancelHistoryPrice.isSuccess])

  return <>
    <FormModal className="price-history-dialog" labelledBy={`${historyDialogId}-title`} describedBy={`${historyDialogId}-context`} busy={cancelHistoryPrice.isPending} onClose={onClose}>
      <Panel>
        <header className="price-history-header" ref={historyHeader}>
          <div className="min-w-0">
            <h2 id={`${historyDialogId}-title`}>Historial de precios</h2>
            <p id={`${historyDialogId}-context`}>{historySelection.productName} · {historySelection.listName}</p>
          </div>
          <Button type="button" variant="secondary" aria-label="Cerrar historial" onClick={onClose} disabled={cancelHistoryPrice.isPending}>Cerrar</Button>
        </header>
        <div className="price-history-body">
          {cancelHistoryPrice.data && <p className="success-text mt-0" role="status">Precio programado para {formatEffectiveDate(cancelHistoryPrice.data)} cancelado.</p>}
          {historyQuery.isLoading ? <EmptyState state="loading" title="Cargando historial" description="Consultando vigencias registradas." />
            : historyQuery.isError ? <EmptyState state="error" title="No se pudo cargar el historial" description={historyQuery.error.message}
              action={<Button type="button" variant="secondary" onClick={() => void historyQuery.refetch()}>Reintentar</Button>} />
            : historyQuery.data?.content.length ? <DataTable className="price-history-table" columns={[
              { key: 'effectiveOn', label: 'Vigente desde', emphasis: true },
              { key: 'price', label: 'Precio', align: 'right' },
              { key: 'recordedAt', label: 'Registrado el' },
              { key: 'updatedAt', label: 'Actualizado el' },
              { key: 'status', label: 'Estado', render: (value) => <Badge tone={value === 'Programado' ? 'strong' : 'muted'}>{value}</Badge> },
              ...(isAdmin ? [{ key: 'actions', label: 'Acciones', align: 'right', render: (date) => date &&
                <Button type="button" variant="link" aria-haspopup="dialog" aria-label={`Cancelar precio del ${formatEffectiveDate(date)}`} onClick={() => {
                  cancelHistoryPrice.reset()
                  onRequestCancelDate(date)
                }}>Cancelar precio</Button>,
              } satisfies TableColumn] : []),
            ]} rows={historyQuery.data.content.map((entry) => ({
              id: entry.effectiveOn,
              effectiveOn: formatEffectiveDate(entry.effectiveOn),
              price: formatMoney(entry.price, 4),
              recordedAt: formatBusinessDateTimeNumeric(entry.recordedAt),
              updatedAt: formatBusinessDateTimeNumeric(entry.updatedAt),
              status: entry.scheduled ? 'Programado' : 'Vigente o histórico',
              actions: entry.scheduled ? entry.effectiveOn : '',
            }))} /> : <EmptyState title="Sin historial" description="Este producto no registra vigencias de precio." />}
          {historyQuery.data && historyQuery.data.totalPages > 1 && <Pagination label="Paginación del historial de precios" previousLabel="Historial anterior" nextLabel="Siguiente historial" summary={<>Página {historyPage + 1} de {historyQuery.data.totalPages} · {historyQuery.data.totalElements} vigencias</>} onPrevious={() => onChangePage(historyPage - 1)} onNext={() => onChangePage(historyPage + 1)} previousDisabled={historyPage === 0} nextDisabled={historyPage + 1 >= historyQuery.data.totalPages} />}
        </div>
      </Panel>
    </FormModal>
    {historyDateToCancel && <ConfirmationDialog
      title={`¿Cancelar el precio programado para ${formatEffectiveDate(historyDateToCancel)}?`}
      description={`Se quitará esta vigencia programada de ${historySelection.productName} en ${historySelection.listName}. Las demás fechas de vigencia se conservan.`}
      confirmLabel="Confirmar cancelación" pendingLabel="Cancelando…" pending={cancelHistoryPrice.isPending}
      error={cancelHistoryPrice.error ? readableError(cancelHistoryPrice.error, 'No se pudo cancelar el precio programado.') : undefined}
      onCancel={() => onRequestCancelDate(undefined)}
      onConfirm={() => cancelHistoryPrice.mutate(historyDateToCancel)}
    />}
  </>
}
