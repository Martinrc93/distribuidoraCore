import { useQuery } from '@tanstack/react-query'
import { useId, type FormEvent } from 'react'
import { Button } from '../../shared/components/Button'
import { EmptyState } from '../../shared/components/EmptyState'
import { BusinessLogoPanel } from './BusinessLogoPanel'
import { SettingsDisclosure } from './SettingsDisclosure'
import { businessProfileQuery, useSettingsEditorState, type BusinessProfile } from './SettingsEditorState'
import { SettingsPageHeader } from './SettingsPageHeader'

const fields = [
  { name: 'name', label: 'Nombre del comercio', maxLength: 120, autoComplete: 'organization' },
  { name: 'address', label: 'Dirección', maxLength: 240, autoComplete: 'street-address' },
  { name: 'phone', label: 'Teléfono', maxLength: 60, autoComplete: 'tel' },
] as const

export default function BusinessProfilePage() {
  const group = useId()
  const query = useQuery(businessProfileQuery)
  const { profileDraft, setProfileDraft, saveProfile } = useSettingsEditorState()
  const profile = query.data && { ...query.data, ...profileDraft }
  const hasChanges = fields.some(({ name }) => profile?.[name] !== query.data?.[name])
  const status = saveProfile.isPending ? 'Guardando…' : saveProfile.isError ? 'Error al guardar' : hasChanges ? 'Sin guardar' : undefined

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (profile && hasChanges && !saveProfile.isPending) saveProfile.mutate(profile)
  }

  function update(field: keyof BusinessProfile, value: string) {
    const draft = { ...profileDraft, [field]: value }
    if (value === query.data?.[field]) delete draft[field]
    setProfileDraft(draft)
    if (!saveProfile.isIdle) saveProfile.reset()
  }

  return <div className="max-w-[680px]">
    <SettingsPageHeader title="Datos del comercio" />
    <div className="grid gap-[8px]">
      <SettingsDisclosure group={group} title="Datos de contacto" description="Aparecen en el ticket y en el comprobante A4." status={status}>
        {query.isPending ? <EmptyState compact state="loading" title="Cargando datos del comercio" description="Consultando la configuración actual." />
          : query.isError && !profile ? <EmptyState compact state="error" title="No se pudieron cargar los datos del comercio"
              description={query.error.message} action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
          : profile && <form className="grid gap-[16px]" onSubmit={submit}>
            <div className="form-grid mb-0">
              {fields.map((field) => <label className={field.name === 'name' ? 'field col-span-full' : 'field'} key={field.name}>
                <span>{field.label}</span>
                <input className="input" type={field.name === 'phone' ? 'tel' : 'text'} autoComplete={field.autoComplete}
                  maxLength={field.maxLength} value={profile[field.name]} disabled={saveProfile.isPending}
                  onChange={(event) => update(field.name, event.target.value)} />
              </label>)}
            </div>
            {saveProfile.isError && <p className="error-text" role="alert">No se pudieron guardar los datos del comercio. {saveProfile.error.message}</p>}
            <div className="flex flex-wrap items-center gap-[12px]">
              <Button type="submit" disabled={!hasChanges || saveProfile.isPending}>{saveProfile.isPending ? 'Guardando...' : 'Guardar datos del comercio'}</Button>
              {saveProfile.isSuccess && <span className="success-text m-0" role="status">Datos del comercio guardados.</span>}
            </div>
          </form>}
      </SettingsDisclosure>
      <BusinessLogoPanel group={group} />
    </div>
  </div>
}
