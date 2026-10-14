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
  onCancel: () => void
}

/** Mount only while confirmation is required. */
export function ConfirmationDialog({ title, description, confirmLabel, cancelLabel = 'Volver', pendingLabel = 'Procesando...', pending = false, error, onConfirm, onCancel }: Props) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const releaseScroll = lockBodyScroll()
    element.showModal()
    element.querySelector<HTMLButtonElement>('[data-safe-action]')?.focus()
    return () => {
      element.close()
      releaseScroll()
      if (trigger?.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])

  return createPortal(
    <dialog ref={dialog} className="confirmation-dialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`} aria-busy={pending} onCancel={(event) => { event.preventDefault(); if (!pending) onCancel() }} onKeyDown={(event) => {
      if (event.key !== 'Tab') return
      const controls = event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not([disabled])')
      const first = controls[0]
      const last = controls[controls.length - 1]
      if (!first) event.preventDefault()
      else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }}>
      <header className="confirmation-dialog-header"><h2 id={`${id}-title`}>{title}</h2></header>
      <div className="confirmation-dialog-body">
        <p id={`${id}-description`}><CurrencyText>{description}</CurrencyText></p>
        {error && <p className="error-text" role="alert"><CurrencyText>{error}</CurrencyText></p>}
      </div>
      <footer className="confirmation-dialog-footer">
        <Button data-safe-action type="button" variant="secondary" disabled={pending} onClick={onCancel}>{cancelLabel}</Button>
        <Button type="button" variant="danger" disabled={pending} onClick={onConfirm}>{pending ? pendingLabel : confirmLabel}</Button>
      </footer>
    </dialog>,
    document.body,
  )
}
