import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { apiGet, apiPost, apiPut, type ApiPage } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'
import { useDiscardChanges } from '../../shared/useDiscardChanges'
import { useUrlListState } from '../../shared/useUrlListState'

type Supplier = { id: string; name: string; phone: string | null; email: string | null; address: string | null }
const suppliersKey = ['/api/suppliers']

function SupplierForm({ initial, onClose, onSuccess }: { initial?: Supplier; onClose: () => void; onSuccess: (message: string) => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(initial?.name ?? '')
  const [phone, setPhone] = useState(initial?.phone ?? '')
  const [email, setEmail] = useState(initial?.email ?? '')
  const [address, setAddress] = useState(initial?.address ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const nameInput = useRef<HTMLInputElement>(null)
  const changed = name !== (initial?.name ?? '') || phone !== (initial?.phone ?? '')
    || email !== (initial?.email ?? '') || address !== (initial?.address ?? '')
  const { requestDiscard, discardDialog } = useDiscardChanges({ hasChanges: changed, onDiscard: onClose, disabled: saving })

  useEffect(() => {
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    nameInput.current?.focus()
    return () => { if (trigger?.isConnected) trigger.focus({ preventScroll: true }) }
  }, [])

  async function save(event: FormEvent) {
    event.preventDefault()
    if (saving || !name.trim()) return
    setSaving(true)
    setError('')
    try {
      const body = { name: name.trim(), phone: phone.trim() || null, email: email.trim() || null, address: address.trim() || null }
      if (initial) await apiPut(`/api/suppliers/${initial.id}`, body)
      else await apiPost('/api/suppliers', body)
      await queryClient.invalidateQueries({ queryKey: suppliersKey })
      onSuccess(initial ? 'Proveedor actualizado correctamente.' : 'Proveedor creado correctamente.')
      onClose()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'No se pudo guardar el proveedor.')
    } finally {
      setSaving(false)
    }
  }

  return <>
    <Panel title={initial ? 'Editar proveedor' : 'Nuevo proveedor'}>
      <form onSubmit={save} aria-label={initial ? 'Editar proveedor' : 'Nuevo proveedor'} aria-busy={saving}>
        <fieldset className="form-grid m-0 mb-[18px] border-0 p-0 min-w-0" disabled={saving}>
          <label className="field"><span>Nombre</span><input ref={nameInput} className="input" value={name} onChange={(event) => setName(event.target.value)} maxLength={200} required /></label>
          <label className="field"><span>Teléfono (opcional)</span><input className="input" type="tel" value={phone} onChange={(event) => setPhone(event.target.value)} maxLength={80} /></label>
          <label className="field"><span>Mail (opcional)</span><input className="input" type="email" value={email} onChange={(event) => setEmail(event.target.value)} maxLength={200} /></label>
          <label className="field"><span>Dirección (opcional)</span><input className="input" value={address} onChange={(event) => setAddress(event.target.value)} maxLength={500} /></label>
        </fieldset>
        {error && <p className="error-text" role="alert">{error}</p>}
        <div className="page-actions"><Button variant="secondary" type="button" disabled={saving} onClick={requestDiscard}>Cancelar</Button><Button type="submit" disabled={saving || !name.trim()}>{saving ? 'Guardando...' : initial ? 'Guardar cambios' : 'Guardar proveedor'}</Button></div>
      </form>
    </Panel>
    {discardDialog}
  </>
}

export default function SuppliersPage() {
  const { page, pageSize, getFilter, setFilter, setPage } = useUrlListState()
  const search = getFilter('search')
  const path = `/api/suppliers?page=${page}&size=${pageSize}&search=${encodeURIComponent(search.trim())}`
  const query = useQuery({ queryKey: [...suppliersKey, path], queryFn: ({ signal }) => apiGet<ApiPage<Supplier>>(path, signal) })
  const suppliers = query.data?.content ?? []
  const [form, setForm] = useState<{ initial?: Supplier } | null>(null)
  const [feedback, setFeedback] = useState('')

  function startForm(initial?: Supplier) {
    setFeedback('')
    setForm({ initial })
  }

  const columns: TableColumn[] = [
    { key: 'name', label: 'Nombre', emphasis: true },
    { key: 'phone', label: 'Teléfono' },
    { key: 'email', label: 'Mail' },
    { key: 'address', label: 'Dirección' },
    { key: 'actions', label: 'Acciones', render: (_value, row) => <Button variant="secondary" type="button" disabled={form !== null} aria-label={`Editar proveedor ${row.name}`} onClick={() => startForm(suppliers.find((supplier) => supplier.id === row.id))}>Editar</Button> },
  ]
  const rows = suppliers.map((supplier) => ({ id: supplier.id, name: supplier.name, phone: supplier.phone || '—', email: supplier.email || '—', address: supplier.address || '—' }))
  const total = query.data?.totalElements ?? 0

  return <>
    <PageHeader eyebrow="Catálogo" title="Proveedores" description="Consulta y administra los datos de contacto de los proveedores." actions={<Button type="button" disabled={form !== null} onClick={() => startForm()}>+ Nuevo proveedor</Button>} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {form && <SupplierForm initial={form.initial} onClose={() => setForm(null)} onSuccess={setFeedback} />}
    <Panel title="Proveedores registrados" description={`${total} ${total === 1 ? 'proveedor' : 'proveedores'}`}>
      <div className="toolbar"><input className="input" type="search" aria-label="Buscar proveedor por nombre" placeholder="Buscar por nombre" value={search} maxLength={200} onChange={(event) => setFilter('search', event.target.value)} /></div>
      {query.isLoading ? <EmptyState title="Cargando proveedores" description="Consultando los proveedores registrados." />
        : query.isError ? <EmptyState title="No se pudieron cargar los proveedores" description={query.error.message} action={<Button variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
          : suppliers.length === 0 ? <EmptyState title={search ? 'No hay coincidencias' : 'No hay proveedores en esta página'} description={search ? 'Prueba con otro nombre o limpia la búsqueda.' : 'Crea un proveedor para registrar sus datos de contacto.'} action={search ? <Button variant="secondary" onClick={() => setFilter('search', '')}>Limpiar búsqueda</Button> : page > 0 ? <Button variant="secondary" onClick={() => setPage(0)}>Volver a la primera página</Button> : <Button disabled={form !== null} onClick={() => startForm()}>+ Nuevo proveedor</Button>} />
            : <DataTable className="[&_td]:whitespace-normal [&_td]:[overflow-wrap:anywhere] [&_td]:max-w-[320px] max-[640px]:[&_td]:max-w-none" columns={columns} rows={rows} />}
      {query.data && !query.isError && <div className="pagination"><span>Página {page + 1} de {Math.max(1, query.data.totalPages)} · {total} proveedores</span><div><Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Anterior</Button><Button variant="secondary" disabled={page + 1 >= query.data.totalPages} onClick={() => setPage(page + 1)}>Siguiente</Button></div></div>}
    </Panel>
  </>
}
