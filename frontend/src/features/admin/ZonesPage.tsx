import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiDelete, apiGet, apiPost, apiPut, ApiError } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { DataTable, type TableColumn } from '../../shared/components/DataTable'
import { EmptyState } from '../../shared/components/EmptyState'
import { PageHeader } from '../../shared/components/PageHeader'
import { Panel } from '../../shared/components/Panel'

type Zone = { id: string; name: string }
const ZONES_KEY = ['/api/zones']

function readable(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 403) return 'No tenés permiso para administrar zonas.'
  if (cause instanceof ApiError && cause.status === 409) return cause.detail ?? 'Ya existe una zona con ese nombre.'
  return cause instanceof Error ? cause.message : 'No se pudo completar la operación.'
}

export default function ZonesPage() {
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: ZONES_KEY, queryFn: () => apiGet<Zone[]>('/api/zones') })
  const zones = query.data ?? []
  const [editing, setEditing] = useState<Zone | undefined>()
  const [showForm, setShowForm] = useState(false)
  const [name, setName] = useState('')
  const [deleting, setDeleting] = useState<Zone | undefined>()
  const [error, setError] = useState('')
  const [feedback, setFeedback] = useState('')
  const [saving, setSaving] = useState(false)

  function startCreate() {
    setEditing(undefined)
    setShowForm(true)
    setName('')
    setError('')
    setFeedback('')
  }

  function startEdit(zone: Zone) {
    setEditing(zone)
    setShowForm(true)
    setName(zone.name)
    setError('')
    setFeedback('')
  }

  function cancelForm() {
    setEditing(undefined)
    setShowForm(false)
    setName('')
    setError('')
  }

  async function save(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setSaving(true)
    setError('')
    setFeedback('')
    try {
      if (editing) await apiPut(`/api/zones/${editing.id}`, { name: name.trim() })
      else await apiPost('/api/zones', { name: name.trim() })
      await queryClient.invalidateQueries({ queryKey: ZONES_KEY })
      setFeedback(editing ? 'Zona actualizada.' : 'Zona creada.')
      cancelForm()
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  async function remove() {
    if (!deleting || saving) return
    setSaving(true)
    setError('')
    setFeedback('')
    try {
      await apiDelete(`/api/zones/${deleting.id}`)
      await queryClient.invalidateQueries({ queryKey: ZONES_KEY })
      await queryClient.invalidateQueries({ predicate: ({ queryKey }) => String(queryKey[0]).startsWith('/api/customers?') })
      setFeedback(`Se eliminó «${deleting.name}». Los clientes asignados quedaron sin zona.`)
      setDeleting(undefined)
      if (editing?.id === deleting.id) cancelForm()
    } catch (cause) {
      setError(readable(cause))
    } finally {
      setSaving(false)
    }
  }

  const columns: TableColumn[] = [
    { key: 'name', label: 'Zona', emphasis: true },
    { key: 'actions', label: 'Acciones', render: (_value, row) => {
      const zone = zones.find((item) => item.id === row.id)
      return zone && <div className="page-actions"><Button variant="secondary" type="button" onClick={() => startEdit(zone)}>Editar</Button><Button variant="link" type="button" onClick={() => { setError(''); setDeleting(zone) }}>Eliminar</Button></div>
    } },
  ]
  const rows = zones.map((zone) => ({ id: zone.id, name: zone.name }))

  return <>
    <PageHeader eyebrow="Administración" title="Zonas" description="Administrá las zonas de entrega disponibles para los clientes." actions={!showForm ? <Button type="button" onClick={startCreate}>+ Nueva zona</Button> : undefined} />
    {feedback && <p className="success-text" role="status">{feedback}</p>}
    {showForm ? <Panel title={editing ? 'Editar zona' : 'Nueva zona'}><form className="zone-form" onSubmit={save}>
      <label className="field"><span>Nombre de la zona</span><input className="input" value={name} onChange={(event) => setName(event.target.value)} maxLength={120} required autoFocus disabled={saving} /></label>
      {error && <p className="error-text" role="alert">{error}</p>}
      <div className="page-actions"><Button type="button" variant="secondary" onClick={cancelForm} disabled={saving}>Cancelar</Button><Button type="submit" disabled={saving || !name.trim()}>{saving ? 'Guardando...' : editing ? 'Guardar cambios' : 'Crear zona'}</Button></div>
    </form></Panel> : null}
    <Panel title="Zonas registradas" description={`${zones.length} ${zones.length === 1 ? 'zona' : 'zonas'}`}>
      {query.isLoading ? <EmptyState title="Cargando zonas" description="Consultando el catálogo de zonas." /> : query.isError ? <EmptyState title="No se pudieron cargar las zonas" description={query.error.message} action={<Button variant="secondary" onClick={() => query.refetch()}>Reintentar</Button>} /> : zones.length === 0 ? <EmptyState title="Todavía no hay zonas" description="Creá una zona para poder asignarla a los clientes." action={<Button onClick={startCreate}>+ Nueva zona</Button>} /> : <DataTable columns={columns} rows={rows} />}
    </Panel>
    {deleting && <div role="alertdialog" aria-modal="true" aria-labelledby="delete-zone-title" className="modal-backdrop"><Panel title="Eliminar zona"><p id="delete-zone-title">¿Querés eliminar «{deleting.name}»? Los clientes que la tengan asignada quedarán sin zona.</p>{error && <p className="error-text" role="alert">{error}</p>}<div className="page-actions"><Button variant="secondary" type="button" onClick={() => setDeleting(undefined)} disabled={saving}>Cancelar</Button><Button type="button" onClick={() => void remove()} disabled={saving}>{saving ? 'Eliminando...' : 'Eliminar zona'}</Button></div></Panel></div>}
  </>
}
