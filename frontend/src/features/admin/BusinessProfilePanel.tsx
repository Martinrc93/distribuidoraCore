import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { apiGet, apiPut } from '../../shared/api/client'
import { Button } from '../../shared/components/Button'
import { EmptyState } from '../../shared/components/EmptyState'
import { Panel } from '../../shared/components/Panel'

type BusinessProfile = { name: string; address: string; phone: string }
const endpoint = '/api/settings/business-profile'
const key = [endpoint]
const fields = [
  { name: 'name', label: 'Nombre del comercio', maxLength: 120, autoComplete: 'organization' },
  { name: 'address', label: 'Dirección', maxLength: 240, autoComplete: 'street-address' },
  { name: 'phone', label: 'Teléfono', maxLength: 60, autoComplete: 'tel' },
] as const

export function BusinessProfilePanel() {
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey: key, queryFn: () => apiGet<BusinessProfile>(endpoint) })
  const [draft, setDraft] = useState<BusinessProfile>()
  const save = useMutation({
    mutationFn: (profile: BusinessProfile) => apiPut<BusinessProfile>(endpoint, profile),
    onSuccess: (profile) => {
      queryClient.setQueryData(key, profile)
      setDraft(undefined)
    },
  })
  const profile = draft ?? query.data

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (draft && !save.isPending) save.mutate(draft)
  }

  return <Panel title="Datos del comercio" description="Nombre, dirección y teléfono que aparecen en los tickets de venta.">
    {query.isPending ? <EmptyState compact state="loading" title="Cargando datos del comercio" description="Consultando la configuración actual." />
      : query.isError && !profile ? <EmptyState compact state="error" title="No se pudieron cargar los datos del comercio"
          description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
      : profile && <form className="grid gap-[16px]" onSubmit={submit}>
        <div className="form-grid mb-0">
          {fields.map((field) => <label className={field.name === 'name' ? 'field col-span-full' : 'field'} key={field.name}>
            <span>{field.label}</span>
            <input className="input" type={field.name === 'phone' ? 'tel' : 'text'} autoComplete={field.autoComplete}
              maxLength={field.maxLength} value={profile[field.name]} disabled={save.isPending}
              onChange={(event) => {
                setDraft({ ...profile, [field.name]: event.target.value })
                if (!save.isIdle) save.reset()
              }} />
          </label>)}
        </div>
        {save.isError && <p className="error-text" role="alert">No se pudieron guardar los datos del comercio. {save.error.message}</p>}
        {save.isSuccess && <p className="success-text m-0" role="status">Datos del comercio guardados.</p>}
        <div><Button type="submit" disabled={!draft || save.isPending}>{save.isPending ? 'Guardando...' : 'Guardar datos del comercio'}</Button></div>
      </form>}
  </Panel>
}
