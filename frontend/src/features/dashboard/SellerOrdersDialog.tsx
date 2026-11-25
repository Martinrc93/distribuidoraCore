import { Pagination } from '../../shared/components/Pagination'
import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useQuery } from '@tanstack/react-query'
import { queryKeys } from '../../shared/api/queryKeys'
import { apiGet, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { formatBusinessDate } from '../../shared/dates'
import { trapDialogFocus } from '../../shared/dialogFocus'
import { formatMoney } from '../../shared/format'
import { lockBodyScroll } from '../../shared/lockBodyScroll'
import { parseSellerOrders } from './dashboardResponses'

export type SellerOrder = {
  id: string
  number: string
  customer: string
  date: string
  deliveredAt: string | null
  total: number
  paid: number
  accountBalance: number
  payments: Array<{ method: string; amount: number }>
}

export type SellerOrderSelection = { sellerId: string | null; seller: string; dateMin: string; dateMax: string }
const money = (value: number) => formatMoney(value, 2)
const date = (value: string | null) => formatBusinessDate(value)
const periodDate = (value: string) => value.split('-').reverse().join('/')

const columns: TableColumn[] = [
  { key: 'number', label: 'Pedido', emphasis: true },
  { key: 'customer', label: 'Cliente' },
  { key: 'deliveredAt', label: 'Entregado el' },
  { key: 'total', label: 'Total', align: 'right' },
  { key: 'paid', label: 'Pagado', align: 'right' },
  { key: 'cashPaid', label: 'Pagado en efectivo', align: 'right' },
  { key: 'transferPaid', label: 'Pagado en transferencia', align: 'right' },
  { key: 'accountBalance', label: 'Por cobrar', align: 'right' },
]

export function SellerOrdersDialog({ selection, onClose }: { selection: SellerOrderSelection; onClose: () => void }) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const [page, setPage] = useState(0)
  const params = new URLSearchParams({ dateMin: selection.dateMin, dateMax: selection.dateMax, page: String(page), size: '20' })
  if (selection.sellerId) params.set('sellerId', selection.sellerId)
  else params.set('unassigned', 'true')
  const path = `/api/dashboard/seller-orders?${params}`
  const query = useQuery<ApiPage<SellerOrder>>({ queryKey: queryKeys.dashboard.sellerOrders(path), queryFn: async ({ signal }) => parseSellerOrders(await apiGet<unknown>(path, signal)), retry: false })

  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const releaseScroll = lockBodyScroll()
    element.showModal()
    element.querySelector<HTMLButtonElement>('[data-dialog-close]')?.focus()
    return () => {
      element.close()
      releaseScroll()
      if (trigger?.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])

  const rows = (query.data?.content ?? []).map((order) => ({
    id: order.id, number: order.number, customer: order.customer, deliveredAt: date(order.deliveredAt),
    total: money(order.total), paid: money(order.paid), accountBalance: money(order.accountBalance),
    cashPaid: money(order.payments.filter((payment) => payment.method === 'CASH').reduce((sum, payment) => sum + payment.amount, 0)),
    transferPaid: money(order.payments.filter((payment) => payment.method === 'BANK_TRANSFER').reduce((sum, payment) => sum + payment.amount, 0)),
  }))

  return createPortal(<dialog ref={dialog} className="confirmation-dialog w-[min(1100px,_calc(100vw_-_40px))] [@media(max-width:760px)]:w-[calc(100vw_-_28px)]"
    aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`}
    onCancel={(event) => { event.preventDefault(); onClose() }} onKeyDown={trapDialogFocus}>
    <header className="confirmation-dialog-header sticky top-0 z-10 bg-surface flex items-center justify-between gap-[14px]">
      <h2 id={`${id}-title`}>Pedidos entregados · {selection.seller}</h2>
      <Button type="button" variant="secondary" data-dialog-close onClick={onClose} aria-label="Cerrar detalle">Cerrar</Button>
    </header>
    <div className="confirmation-dialog-body">
      <p id={`${id}-description`}>Pedidos creados del {periodDate(selection.dateMin)} al {periodDate(selection.dateMax)} que están entregados. Los cobros y la deuda corresponden al estado actual de cada pedido.</p>
      <div className="mt-[18px]" aria-busy={query.isFetching}>
        {query.isLoading ? <EmptyState state="loading" title="Cargando pedidos entregados" description="Consultando cobros y cuenta corriente." />
          : query.isError ? <EmptyState state="error" title="No se pudieron cargar los pedidos" description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
          : <>
            <DataTable columns={columns} rows={rows} emptyContent={<p className="table-empty-message">No hay pedidos entregados para este vendedor en el período seleccionado.</p>} />
            <Pagination summary={<>{query.data?.totalElements ?? 0} pedidos · Página {page + 1} de {Math.max(query.data?.totalPages ?? 0, 1)}</>} onPrevious={() => setPage(page - 1)} onNext={() => setPage(page + 1)} previousDisabled={page === 0} nextDisabled={page + 1 >= (query.data?.totalPages ?? 0)} />
          </>}
      </div>
    </div>
  </dialog>, document.body)
}
