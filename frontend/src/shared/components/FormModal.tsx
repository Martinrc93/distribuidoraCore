import { focusDialogContent, trapDialogFocus } from '../dialogFocus'
import { useEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { lockBodyScroll } from '../lockBodyScroll'

type Props = {
  /** ID of the dialog's visible description, when present. */
  describedBy?: string
  className?: string
  /** `compact` (480px) for short forms; default adapts up to 1040px. */
  size?: 'default' | 'compact'
  /** While true, Escape does not close the dialog. */
  busy?: boolean
  /** Called on Escape. Pass the same handler as the form's cancel action (e.g. a discard confirmation). */
  onClose: () => void
  children: ReactNode
} & ({ label: string; labelledBy?: never } | { label?: never; labelledBy: string })


/**
 * Native modal dialog for forms and detailed views. It is rendered in the top layer, so it always opens
 * inside the viewport regardless of the page scroll, locks the page scroll and restores focus on close.
 * Mount only while the form is open.
 */
export function FormModal({ label, labelledBy, describedBy, className, size = 'default', busy = false, onClose, children }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const dialogClassName = ['form-modal', size === 'compact' && 'form-modal-compact', className].filter(Boolean).join(' ')

  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const releaseScroll = lockBodyScroll()
    element.showModal()
    focusDialogContent(element)
    return () => {
      element.close()
      releaseScroll()
      if (trigger?.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])

  return createPortal(
    <dialog ref={dialog} className={dialogClassName} aria-label={label} aria-labelledby={labelledBy} aria-describedby={describedBy} aria-modal="true" aria-busy={busy}
      onCancel={(event) => { event.preventDefault(); if (!busy) onClose() }}
      onKeyDown={trapDialogFocus}>
      {children}
    </dialog>,
    document.body,
  )
}
