import { trapDialogFocus } from '../dialogFocus'
import { useEffect, useId, useRef } from 'react'
import { createPortal } from 'react-dom'
import { Button } from './Button'
import { CurrencyText } from './CurrencyText'
import { lockBodyScroll } from '../lockBodyScroll'

type Props = {
  title: string
  description: string
  confirmLabel: string
  cancelLabel?: string
  pendingLabel?: string
  pending?: boolean
  error?: string
  onConfirm: () => void
  /** `danger` (default) for destructive actions; `primary` to confirm creating or saving. */
  confirmVariant?: 'danger' | 'primary'
  initialFocus?: 'cancel' | 'confirm'
  onCancel: () => void
}

/** Mount only while confirmation is required. */
export function ConfirmationDialog({ title, description, confirmLabel, cancelLabel = 'Volver', pendingLabel = 'Procesando...', pending = false, error, onConfirm, onCancel, confirmVariant = 'danger', initialFocus = 'cancel' }: Props) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const releaseScroll = lockBodyScroll()
    element.showModal()
    element.querySelector<HTMLButtonElement>('[data-initial-focus]')?.focus()
    return () => {
      element.close()
      releaseScroll()
      if (trigger?.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])

  return createPortal(
    <dialog ref={dialog} className="confirmation-dialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`} aria-busy={pending} onCancel={(event) => { event.preventDefault(); if (!pending) onCancel() }} onKeyDown={trapDialogFocus}>
      <header className="confirmation-dialog-header"><h2 id={`${id}-title`}>{title}</h2></header>
      <div className="confirmation-dialog-body">
        <p id={`${id}-description`}><CurrencyText>{description}</CurrencyText></p>
        {error && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
      </div>
      <footer className="confirmation-dialog-footer">
        <Button data-initial-focus={initialFocus === 'cancel' ? '' : undefined} type="button" variant="secondary" disabled={pending} onClick={onCancel}>{cancelLabel}</Button>
        <Button data-initial-focus={initialFocus === 'confirm' ? '' : undefined} type="button" variant={confirmVariant} disabled={pending} onClick={onConfirm}>{pending ? pendingLabel : confirmLabel}</Button>
      </footer>
    </dialog>,
    document.body,
  )
}
