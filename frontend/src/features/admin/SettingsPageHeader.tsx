import { useEffect, useRef, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { PageHeader } from '../../shared/components/PageHeader'
import { settingsPath } from './settingsPaths'

export function SettingsPageHeader({ title, description, actions, index = false }: {
  title: string
  description?: string
  actions?: ReactNode
  index?: boolean
}) {
  const header = useRef<HTMLDivElement>(null)

  useEffect(() => {
    header.current?.focus()
  }, [])

  return <div ref={header} role="group" tabIndex={-1} aria-label={title} className={index ? 'outline-none' : 'settings-detail-header outline-none'}>
    <PageHeader title={title} description={description} actions={index ? actions : <>
      {actions}
      <Link to={settingsPath} className="button button-link min-h-[44px] whitespace-nowrap">Volver a Configuración</Link>
    </>} />
  </div>
}
