import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type FormEvent } from 'react'
import { apiGet, apiPatch, apiPost, ApiError, type ApiPage } from '../../shared/api/client'
import { hasAuthority } from '../../shared/auth/permissions'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { Panel } from '../../shared/components/Panel'

type Depot = { id: string; code: string; name: string; status: 'ACTIVE' | 'INACTIVE'; isDefault: boolean }
type DepotBalance = { productId: string; sku: string; product: string; stock: number; updated?: string | null }
type TransferDraft = { fromDepotId: string; toDepotId: string; productId: string; quantity: string; reason: string }
type Row = Record<string, string>
type DepotInventorySectionProps = {
  selectedDepotId: string
  onSelectedDepotChange: (depotId: string, depotCode?: string) => void
  onAdjust: (item: { productId: string; product: string; stock: number }, depotId: string) => void
}

const DEPOTS_PATH = '/api/inventory/depots'
const DEPOTS_KEY = [DEPOTS_PATH]
const INVENTORY_KEY = ['/api/inventory?page=0&size=20']

function readableError(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 403) return 'No tenés permiso para esta acción de inventario.'
  return cause instanceof Error ? cause.message : 'No se pudo completar la operación.'
}

export default function DepotInventorySection({ selectedDepotId, onSelectedDepotChange, onAdjust }: DepotInventorySectionProps) {
  const isAdmin = hasAuthority('ADMIN_ALL')
  const canAdjust = hasAuthority('STOCK_ADJUST') || isAdmin
  const queryClient = useQueryClient()
  function invalidateDepotBalances() {
    return queryClient.invalidateQueries({
      predicate: (query) => typeof query.queryKey[0] === 'string'
        && query.queryKey[0].startsWith('/api/inventory/depots/')
        && query.queryKey[0].includes('/balances?'),
    })
  }
  const depotsQuery = useQuery({ queryKey: DEPOTS_KEY, queryFn: () => apiGet<Depot[]>(DEPOTS_PATH) })
  const depots = Array.isArray(depotsQuery.data) ? depotsQuery.data : []
  const activeDepots = depots.filter((depot) => depot.status === 'ACTIVE')
  const selectedDepot = depots.find((depot) => depot.id === selectedDepotId)
  const [search, setSearch] = useState('')
  const [balancePage, setBalancePage] = useState(0)
  const balancePath = selectedDepotId
    ? `/api/inventory/depots/${selectedDepotId}/balances?page=${balancePage}&size=20&search=${encodeURIComponent(search.trim())}`
    : ''
  const balanceKey = [balancePath]
  const balancesQuery = useQuery({ queryKey: balanceKey, queryFn: () => apiGet<ApiPage<DepotBalance>>(balancePath), enabled: Boolean(balancePath) })
  const [showCreate, setShowCreate] = useState(false)
  const [depotCode, setDepotCode] = useState('')
  const [depotName, setDepotName] = useState('')
  const [statusDepot, setStatusDepot] = useState<Depot | undefined>()
  const [transfer, setTransfer] = useState<TransferDraft | undefined>()
  const [transferBalance, setTransferBalance] = useState(0)
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')

  useEffect(() => {
    if (!depots.length) return
    if (selectedDepotId && depots.some((depot) => depot.id === selectedDepotId)) return
    const initial = depots.find((depot) => depot.isDefault) ?? activeDepots[0]
    if (initial) onSelectedDepotChange(initial.id, initial.code)
  }, [activeDepots, depots, onSelectedDepotChange, selectedDepotId])

  const createMutation = useMutation({
    mutationFn: () => apiPost<Depot>(DEPOTS_PATH, { code: depotCode.trim().toUpperCase(), name: depotName.trim() }),
    onSuccess: async (depot) => {
      await queryClient.invalidateQueries({ queryKey: DEPOTS_KEY })
      setDepotCode('')
      setDepotName('')
      setShowCreate(false)
      setError('')
      setFeedback(`Depósito ${depot.code} creado correctamente.`)
    },
    onError: (cause) => setError(readableError(cause)),
  })

  const statusMutation = useMutation({
    mutationFn: ({ depot, active }: { depot: Depot; active: boolean }) => apiPatch<Depot>(`${DEPOTS_PATH}/${depot.id}/status`, { active }),
    onSuccess: async (_result, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: DEPOTS_KEY }),
        invalidateDepotBalances(),
      ])
      if (!variables.active && variables.depot.id === selectedDepotId) {
        const defaultDepot = depots.find((depot) => depot.isDefault)
        onSelectedDepotChange(defaultDepot?.id ?? '', defaultDepot?.code ?? '')
      }
      setStatusDepot(undefined)
      setFeedback(`Depósito ${variables.depot.code} ${variables.active ? 'activado' : 'desactivado'}.`)
    },
    onError: (cause) => setError(readableError(cause)),
  })

  const transferMutation = useMutation({
    mutationFn: (draft: TransferDraft) => apiPost<{ transferId: string }>('/api/inventory/transfers', {
      fromDepotId: draft.fromDepotId,
      toDepotId: draft.toDepotId,
      productId: draft.productId,
      quantity: Number(draft.quantity),
      reason: draft.reason.trim(),
    }),
    onSuccess: async (_result, draft) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: INVENTORY_KEY }),
        invalidateDepotBalances(),
        queryClient.invalidateQueries({ queryKey: [`/api/inventory/${draft.productId}/movements?page=0&size=20`] }),
      ])
      setFeedback(`Transferencia de ${transfer?.quantity ?? draft.quantity} unidades registrada.`)
      setTransfer(undefined)
    },
    onError: (cause) => setError(readableError(cause)),
  })

  function submitCreate(event: FormEvent) {
    event.preventDefault()
    setError('')
    const code = depotCode.trim().toUpperCase()
    if (!/^[A-Z0-9_-]{2,40}$/.test(code)) { setError('El código debe tener de 2 a 40 caracteres alfanuméricos, guion o guion bajo.'); return }
    if (!depotName.trim() || depotName.trim().length > 120) { setError('Ingresá un nombre de hasta 120 caracteres.'); return }
    createMutation.mutate()
  }

  function submitTransfer(event: FormEvent) {
    event.preventDefault()
    if (!transfer) return
    setError('')
    const quantity = Number(transfer.quantity)
    if (transfer.fromDepotId === transfer.toDepotId) { setError('Elegí un depósito de destino distinto al origen.'); return }
    if (!Number.isFinite(quantity) || quantity <= 0 || !Number.isInteger(quantity * 2)) { setError('La cantidad debe ser positiva y múltiplo de 0,5.'); return }
    if (quantity > transferBalance) { setError(`La cantidad supera el saldo visible del depósito de origen (${transferBalance}).`); return }
    if (!transfer.reason.trim()) { setError('Ingresá el motivo de la transferencia.'); return }
    transferMutation.mutate(transfer)
  }

  const rows: Row[] = (balancesQuery.data?.content ?? []).map((balance) => ({
    id: balance.productId,
    sku: balance.sku,
    product: balance.product,
    stock: String(balance.stock),
    updated: balance.updated ? new Intl.DateTimeFormat('es-AR', { dateStyle: 'medium' }).format(new Date(balance.updated)) : '—',
  }))
  const columns: TableColumn[] = [
    { key: 'product', label: 'Producto', emphasis: true },
    { key: 'sku', label: 'SKU' },
    { key: 'stock', label: 'Saldo en depósito', align: 'right' },
    { key: 'updated', label: 'Actualizado' },
    { key: 'actions', label: '', render: (_value: string, row: Row) => {
      const balance = balancesQuery.data?.content.find((candidate) => candidate.productId === row.id)
      if (!balance) return null
      return <div className="page-actions">
        {canAdjust && selectedDepot?.status === 'ACTIVE' && <Button variant="link" onClick={() => onAdjust({ productId: balance.productId, product: balance.product, stock: balance.stock }, selectedDepotId)}>Ajustar stock de {balance.product}</Button>}
        {canAdjust && selectedDepot?.status === 'ACTIVE' && activeDepots.length > 1 && <Button variant="link" onClick={() => {
          setError('')
          setTransferBalance(balance.stock)
          setTransfer({ fromDepotId: selectedDepotId, toDepotId: '', productId: balance.productId, quantity: '', reason: '' })
        }}>Transferir {balance.product}</Button>}
      </div>
    } },
  ]

  return <>
    <Panel title="Inventario por depósito" description="El inventario general suma todos los depósitos. Estos saldos corresponden únicamente al depósito seleccionado." action={isAdmin ? <Button onClick={() => { setShowCreate((value) => !value); setError('') }}>{showCreate ? 'Cerrar alta' : '+ Nuevo depósito'}</Button> : undefined}>
      {feedback && <p className="success-text" role="status">{feedback}</p>}
      {error && !showCreate && !transfer && <p className="error-text" role="alert">{error}</p>}
      {depotsQuery.isLoading ? <EmptyState title="Cargando depósitos" description="Consultando depósitos habilitados." /> : depotsQuery.isError ? <EmptyState title="No se pudieron cargar los depósitos" description={depotsQuery.error.message} action={<Button variant="secondary" onClick={() => depotsQuery.refetch()}>Reintentar</Button>} /> : depots.length === 0 ? <EmptyState title="No hay depósitos disponibles" description="La lista de depósitos está vacía." /> : <>
        <div className="form-grid">
          <label className="field"><span>Depósito seleccionado</span><select className="select" aria-label="Depósito seleccionado" value={selectedDepotId} onChange={(event) => { onSelectedDepotChange(event.target.value, depots.find((depot) => depot.id === event.target.value)?.code); setBalancePage(0); setError('') }}>{depots.map((depot) => <option key={depot.id} value={depot.id}>{depot.code} · {depot.name}{depot.isDefault ? ' · Predeterminado' : ''}{depot.status === 'INACTIVE' ? ' · Inactivo' : ''}</option>)}</select></label>
          {isAdmin && <div className="field"><span>Administración de depósitos</span><div className="page-actions">{depots.filter((depot) => !depot.isDefault).map((depot) => <Button key={depot.id} variant="link" onClick={() => setStatusDepot(depot)}>{depot.status === 'ACTIVE' ? 'Desactivar' : 'Activar'} depósito {depot.code}</Button>)}</div></div>}
        </div>
        {selectedDepot && <p className="helper-text">Los ajustes y transferencias se registran sobre {selectedDepot.code} · {selectedDepot.name}.</p>}
        <div className="toolbar"><input className="input search-input" aria-label="Buscar productos del depósito" placeholder="Buscar producto o SKU..." value={search} onChange={(event) => { setSearch(event.target.value); setBalancePage(0) }} /></div>
        {balancesQuery.isLoading ? <EmptyState title="Cargando saldos" description={`Consultando ${selectedDepot?.name ?? 'el depósito seleccionado'}.`} /> : balancesQuery.isError ? <EmptyState title="No se pudieron cargar los saldos" description={balancesQuery.error.message} action={<Button variant="secondary" onClick={() => balancesQuery.refetch()}>Reintentar</Button>} /> : rows.length === 0 ? <EmptyState title="Sin productos para mostrar" description="No hay productos que coincidan con esta búsqueda en el depósito." /> : <DataTable columns={columns} rows={rows} />}
        {balancesQuery.data && balancesQuery.data.totalPages > 1 && <div className="pagination"><span>Página {balancePage + 1} de {balancesQuery.data.totalPages} · {balancesQuery.data.totalElements} productos</span><div><Button variant="secondary" onClick={() => setBalancePage((page) => Math.max(0, page - 1))} disabled={balancePage === 0}>Anterior</Button><Button variant="secondary" onClick={() => setBalancePage((page) => Math.min((balancesQuery.data?.totalPages ?? 1) - 1, page + 1))} disabled={balancePage + 1 >= balancesQuery.data.totalPages}>Siguiente</Button></div></div>}
      </>}
    </Panel>
    {showCreate && isAdmin && <Panel title="Nuevo depósito"><form className="form-grid" onSubmit={submitCreate}>
      <label className="field"><span>Código</span><input className="input" aria-label="Código del depósito" value={depotCode} onChange={(event) => setDepotCode(event.target.value)} required maxLength={40} disabled={createMutation.isPending} /></label>
      <label className="field"><span>Nombre</span><input className="input" aria-label="Nombre del depósito" value={depotName} onChange={(event) => setDepotName(event.target.value)} required maxLength={120} disabled={createMutation.isPending} /></label>
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setShowCreate(false)} disabled={createMutation.isPending}>Cancelar</Button><Button type="submit" disabled={createMutation.isPending}>{createMutation.isPending ? 'Guardando...' : 'Guardar depósito'}</Button></div>
    </form></Panel>}
    {statusDepot && <div role="dialog" aria-modal="true" aria-labelledby="depot-status-title" className="modal-backdrop"><Panel title="Confirmar depósito"><h2 id="depot-status-title">¿{statusDepot.status === 'ACTIVE' ? 'Desactivar' : 'Activar'} el depósito {statusDepot.code}?</h2><p>Los saldos históricos permanecen consultables.</p><div className="page-actions"><Button variant="secondary" onClick={() => setStatusDepot(undefined)} disabled={statusMutation.isPending}>Cancelar</Button><Button onClick={() => statusMutation.mutate({ depot: statusDepot, active: statusDepot.status !== 'ACTIVE' })} disabled={statusMutation.isPending}>Confirmar</Button></div></Panel></div>}
    {transfer && <div role="dialog" aria-modal="true" aria-labelledby="depot-transfer-title" className="modal-backdrop"><Panel title="Transferir inventario"><form className="form-grid" onSubmit={submitTransfer}>
      <h2 id="depot-transfer-title">{transfer.productId && balancesQuery.data?.content.find((item) => item.productId === transfer.productId)?.product} · origen {selectedDepot?.code} ({transferBalance} disponibles)</h2>
      <label className="field"><span>Depósito de destino</span><select className="select" aria-label="Depósito de destino" value={transfer.toDepotId} onChange={(event) => setTransfer((current) => current ? { ...current, toDepotId: event.target.value } : current)} required><option value="">Seleccionar depósito...</option>{activeDepots.filter((depot) => depot.id !== transfer.fromDepotId).map((depot) => <option key={depot.id} value={depot.id}>{depot.code} · {depot.name}</option>)}</select></label>
      <label className="field"><span>Cantidad</span><input className="input" aria-label="Cantidad a transferir" type="text" inputMode="decimal" value={transfer.quantity} onChange={(event) => setTransfer((current) => current ? { ...current, quantity: event.target.value } : current)} required /><small>Debe ser múltiplo positivo de 0,5 y no superar el saldo de origen.</small></label>
      <label className="field"><span>Motivo</span><input className="input" aria-label="Motivo de transferencia" value={transfer.reason} onChange={(event) => setTransfer((current) => current ? { ...current, reason: event.target.value } : current)} required maxLength={500} /></label>
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions"><Button variant="secondary" type="button" onClick={() => setTransfer(undefined)} disabled={transferMutation.isPending}>Cancelar</Button><Button type="submit" disabled={transferMutation.isPending}>{transferMutation.isPending ? 'Transfiriendo...' : 'Confirmar transferencia'}</Button></div>
    </form></Panel></div>}
  </>
}
