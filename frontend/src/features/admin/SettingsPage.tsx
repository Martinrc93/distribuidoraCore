import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Badge } from '../../shared/components/Badge'
import { usePrinterPreferences } from '../../shared/printers'
import { creditLimitQuery, useSettingsEditorState } from './SettingsEditorState'
import { SettingsPageHeader } from './SettingsPageHeader'
import { businessProfilePath, commissionPlansPagePath, creditLimitPath, operationShortcutsPath, printersPath } from './settingsPaths'

const groups = [
  { title: 'Comercio', entries: [
    { path: businessProfilePath, title: 'Datos del comercio', description: 'Datos de contacto y logo para los comprobantes de venta.' },
  ] },
  { title: 'Reglas comerciales', entries: [
    { path: creditLimitPath, title: 'Límite de crédito', description: 'Advertencia al confirmar un pedido.' },
    { path: commissionPlansPagePath, title: 'Planes de comisiones', description: 'Porcentajes y asignaciones a vendedores y revendedores.' },
  ] },
  { title: 'Este navegador', entries: [
    { path: printersPath, title: 'Impresoras', description: 'Impresora automática para A4 y ticket 80 mm guardada en esta PC.' },
    { path: operationShortcutsPath, title: 'Atajos de teclado', description: 'Combinaciones para abrir las secciones de Operación.' },
  ] },
]

export default function SettingsPage() {
  const credit = useQuery(creditLimitQuery)
  const { profileDraft, creditDraft, logoDraft } = useSettingsEditorState()
  const { preferences } = usePrinterPreferences()
  const printerStatus = preferences.a4 && preferences.ticket
    ? 'A4 y ticket'
    : preferences.a4
      ? 'Solo A4'
      : preferences.ticket
        ? 'Solo ticket'
        : undefined
  const statuses: Record<string, string | undefined> = {
    [businessProfilePath]: logoDraft !== undefined || Object.keys(profileDraft).length ? 'Sin guardar' : undefined,
    [creditLimitPath]: creditDraft ? 'Sin guardar' : credit.data ? (credit.data.enabled ? 'Activo' : 'Desactivado') : undefined,
    [printersPath]: printerStatus,
  }

  return <>
    <SettingsPageHeader index title="Configuración" description="Datos del negocio, reglas comerciales y preferencias del navegador." />
    <nav className="content-grid max-w-[860px]" aria-label="Secciones de Configuración">
      {groups.map(({ title, entries }) => <section className="settings-group" key={title}>
        <h2>{title}</h2>
        <ul className="settings-entries">
          {entries.map((entry) => <li key={entry.path}>
            <Link className="settings-entry" to={entry.path}>
              <span className="grid gap-[3px]">
                <span className="text-[14px] font-bold">{entry.title}</span>
                <span className="text-[13px] text-muted">{entry.description}</span>
              </span>
              <span className="flex items-center gap-[8px]">
                {statuses[entry.path] && <Badge tone={statuses[entry.path] === 'Activo' || entry.path === printersPath ? 'soft' : 'muted'}>{statuses[entry.path]}</Badge>}
                <svg width="16" height="16" viewBox="0 0 20 20" fill="none" className="shrink-0 text-accent-dark" aria-hidden="true"><path d="m8 5 5 5-5 5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </span>
            </Link>
          </li>)}
        </ul>
      </section>)}
    </nav>
  </>
}
