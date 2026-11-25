import { useQuery } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { businessLogoQuery, businessLogoSrc } from '../../shared/businessLogo'
import { Button } from '../../shared/components/Button'
import { EmptyState } from '../../shared/components/EmptyState'
import { SettingsDisclosure } from './SettingsDisclosure'
import { useSettingsEditorState } from './SettingsEditorState'

const MAX_FILE_SIZE = 512 * 1024
const imageTypes = ['image/png', 'image/jpeg']

export function BusinessLogoPanel({ group }: { group: string }) {
  const query = useQuery(businessLogoQuery)
  const { logoDraft, setLogoDraft, saveLogo } = useSettingsEditorState()
  const inputId = useId()
  const reader = useRef<FileReader>(null)
  const [reading, setReading] = useState(false)
  const [fileError, setFileError] = useState('')
  const savedSource = businessLogoSrc(query.data?.content)
  const hasChanges = logoDraft !== undefined
  const source = hasChanges ? (logoDraft ? `data:${logoDraft.type};base64,${logoDraft.content}` : undefined) : savedSource
  const busy = reading || saveLogo.isPending
  const status = saveLogo.isPending ? 'Guardando…' : saveLogo.isError ? 'Error al guardar'
    : hasChanges ? (logoDraft ? 'Sin guardar' : 'Pendiente de quitar') : undefined

  useEffect(() => () => reader.current?.abort(), [])

  function selectFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    reader.current?.abort()
    reader.current = null
    setReading(false)
    saveLogo.reset()
    if (!imageTypes.includes(file.type) || !file.size || file.size > MAX_FILE_SIZE) {
      setFileError('Seleccioná una imagen PNG o JPG de hasta 512 KB.')
      return
    }

    setFileError('')
    setReading(true)
    const fileReader = new FileReader()
    reader.current = fileReader
    fileReader.onload = () => {
      if (reader.current !== fileReader) return
      const content = typeof fileReader.result === 'string' ? fileReader.result.split(',')[1] : undefined
      if (content) setLogoDraft({ content, type: file.type })
      else setFileError('No se pudo leer la imagen. Volvé a seleccionarla.')
    }
    fileReader.onerror = () => { if (reader.current === fileReader) setFileError('No se pudo leer la imagen. Volvé a seleccionarla.') }
    fileReader.onloadend = () => { if (reader.current === fileReader) setReading(false) }
    fileReader.readAsDataURL(file)
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!busy && !fileError && hasChanges) saveLogo.mutate(logoDraft)
  }

  return <SettingsDisclosure group={group} title="Logo del comercio" status={status}
    description="Se usa en la cabecera del sistema y al principio del ticket y del comprobante A4.">
    {query.isPending ? <EmptyState compact state="loading" title="Cargando logo" description="Consultando la imagen guardada." />
      : query.isError && !query.data ? <EmptyState compact state="error" title="No se pudo cargar el logo" description={query.error.message}
        action={<Button type="button" variant="secondary" onClick={() => void query.refetch()}>Reintentar</Button>} />
      : <form className="grid gap-[16px]" onSubmit={submit}>
        <div className="flex items-start gap-[14px]">
          <div className="grid size-[64px] shrink-0 place-items-center rounded-[5px] [border:1px_solid_var(--line)] bg-canvas">
            {source ? <img className="size-full rounded-[4px] bg-white p-[4px] object-contain" alt="Vista previa del logo" src={source} />
              : <span className="px-[6px] text-center text-[11px] text-muted">Sin logo</span>}
          </div>
          <div className="min-w-0">
            <input className="peer sr-only" id={inputId} type="file" accept={imageTypes.join(',')} disabled={busy} onChange={selectFile}
              aria-describedby={`${inputId}-help${fileError ? ` ${inputId}-error` : ''}`} aria-invalid={!!fileError} />
            <label htmlFor={inputId} className="button button-secondary min-h-[44px] peer-focus-visible:outline-[3px] peer-focus-visible:outline-[#bb8754] peer-focus-visible:outline-offset-2 peer-disabled:cursor-not-allowed peer-disabled:opacity-[.45]">Seleccionar imagen</label>
            <p className="helper-text mb-0" id={`${inputId}-help`}>PNG o JPG de hasta 512 KB y 2048 × 2048 píxeles.</p>
          </div>
        </div>
        {fileError && <p id={`${inputId}-error`} className="error-text" role="alert">{fileError}</p>}
        {saveLogo.isError && <p className="error-text" role="alert">{saveLogo.error.message}</p>}
        <div className="flex flex-wrap items-center gap-[8px] [&>.button]:min-h-[44px]">
          <Button type="submit" disabled={!hasChanges || busy || !!fileError}>{saveLogo.isPending ? 'Guardando...' : 'Guardar logo'}</Button>
          {hasChanges ? <Button type="button" variant="secondary" disabled={busy} onClick={() => {
            setLogoDraft(undefined)
            setFileError('')
            saveLogo.reset()
          }}>Deshacer cambio</Button> : savedSource && <Button type="button" variant="secondary" disabled={busy} onClick={() => {
            setLogoDraft(null)
            setFileError('')
            saveLogo.reset()
          }}>Quitar logo</Button>}
          <div role="status">
            {reading ? 'Leyendo imagen...' : saveLogo.isSuccess && <span className="success-text m-0">{savedSource ? 'Logo guardado.' : 'Logo eliminado.'}</span>}
          </div>
        </div>
      </form>}
  </SettingsDisclosure>
}
