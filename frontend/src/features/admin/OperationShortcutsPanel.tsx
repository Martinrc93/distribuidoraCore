import { useId, useState, type KeyboardEvent } from 'react'
import { formatShortcut, operationLinks, shortcutFromKey, useOperationShortcuts } from '../../app/operationShortcuts'
import { Button } from '../../shared/components/Button'
import { Panel } from '../../shared/components/Panel'

export function OperationShortcutsPanel() {
  const id = useId()
  const { shortcuts, updateShortcut } = useOperationShortcuts()
  const [error, setError] = useState<{ path: string; message: string }>()
  const [feedback, setFeedback] = useState('')

  function applyShortcut([label, path]: typeof operationLinks[number], shortcut?: string) {
    const message = updateShortcut(path, shortcut)
    setError(message ? { path, message } : undefined)
    setFeedback(message ? '' : shortcut ? `Atajo de ${label} guardado.` : `Atajo de ${label} eliminado.`)
  }

  function capture(event: KeyboardEvent<HTMLInputElement>, operation: typeof operationLinks[number]) {
    const shortcut = shortcutFromKey(event.nativeEvent)
    if (!shortcut) return
    event.preventDefault()
    applyShortcut(operation, shortcut)
  }

  return <Panel title="Atajos de teclado" description="Abra las secciones de Operación con el teclado. Los atajos se guardan en este navegador.">
    <p id={`${id}-help`} className="helper-text mt-0 mb-[14px]">
      Enfoque un campo y presione Ctrl, Alt o Meta con una letra o un número. También puede agregar Mayús.
    </p>
    <div className="form-grid mb-0">
      {operationLinks.map((operation, index) => {
        const [label, path] = operation
        const inputId = `${id}-${index}`
        const shortcut = shortcuts[path]
        const fieldError = error?.path === path ? error.message : undefined
        return <div className="grid min-w-0 gap-[7px]" key={path}>
          <label className="field" htmlFor={inputId}>{label}</label>
          <div className="flex items-center gap-[8px]">
            <input className="input min-w-0 min-h-[44px]" id={inputId} readOnly placeholder="Sin asignar"
              value={shortcut ? formatShortcut(shortcut) : ''} onKeyDown={(event) => capture(event, operation)}
              aria-describedby={`${id}-help${fieldError ? ` ${inputId}-error` : ''}`} aria-invalid={fieldError ? true : undefined} />
            <Button type="button" variant="secondary" aria-label={`Quitar atajo de ${label}`}
              disabled={!shortcut} onClick={() => { document.getElementById(inputId)?.focus(); applyShortcut(operation) }}>Quitar</Button>
          </div>
          {fieldError && <p id={`${inputId}-error`} className="error-text" role="alert">{fieldError}</p>}
        </div>
      })}
    </div>
    <p className={feedback ? 'success-text mt-[14px] mb-0' : 'sr-only'} role="status">{feedback}</p>
  </Panel>
}
