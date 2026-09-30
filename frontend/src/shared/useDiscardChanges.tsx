import { useEffect, useState } from 'react'
import { ConfirmationDialog } from './components/ConfirmationDialog'

type Options = {
  hasChanges: boolean
  onDiscard: () => void
  disabled?: boolean
  protectUnload?: boolean
}

export function useDiscardChanges({ hasChanges, onDiscard, disabled = false, protectUnload = false }: Options) {
  const [confirming, setConfirming] = useState(false)

  useEffect(() => {
    if (!hasChanges || !protectUnload) return
    function warnBeforeLeave(event: BeforeUnloadEvent) {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warnBeforeLeave)
    return () => window.removeEventListener('beforeunload', warnBeforeLeave)
  }, [hasChanges, protectUnload])

  function requestDiscard() {
    if (disabled) return
    if (hasChanges) setConfirming(true)
    else onDiscard()
  }

  const discardDialog = confirming ? <ConfirmationDialog
    title="¿Descartar los cambios?"
    description="Hay cambios sin guardar. Al salir, se perderán los datos ingresados."
    cancelLabel="Seguir editando"
    confirmLabel="Descartar cambios"
    pending={disabled}
    onCancel={() => setConfirming(false)}
    onConfirm={() => { setConfirming(false); onDiscard() }}
  /> : null

  return { requestDiscard, discardDialog }
}
