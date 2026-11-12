import { useEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { lockBodyScroll } from '../lockBodyScroll'

type Props = {
  /** Accessible name of the dialog; usually the form title. */
  label: string
  /** `compact` (480px) for short forms; default adapts up to 1040px. */
  size?: 'default' | 'compact'
  /** While true, Escape does not close the dialog. */
  busy?: boolean
  /** Called on Escape. Pass the same handler as the form's cancel action (e.g. a discard confirmation). */
  onClose: () => void
  children: ReactNode
}

const focusable = 'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href]'

/**
 * Native modal dialog for create/edit forms. It is rendered in the top layer, so it always opens
 * inside the viewport regardless of the page scroll, locks the page scroll and restores focus on close.
 * Mount only while the form is open.
 */
export function FormModal({ label, size = 'default', busy = false, onClose, children }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const releaseScroll = lockBodyScroll()
    element.showModal()
    element.querySelector<HTMLElement>('input:not([disabled]), select:not([disabled]), textarea:not([disabled])')?.focus()
    return () => {
      element.close()
      releaseScroll()
      if (trigger?.isConnected) trigger.focus({ preventScroll: true })
    }
  }, [])

  return createPortal(
    <dialog ref={dialog} className={size === 'compact' ? 'form-modal form-modal-compact' : 'form-modal'} aria-label={label} aria-modal="true" aria-busy={busy}
      onCancel={(event) => { event.preventDefault(); if (!busy) onClose() }}
      onKeyDown={(event) => {
        if (event.key !== 'Tab') return
        const controls = event.currentTarget.querySelectorAll<HTMLElement>(focusable)
        const first = controls[0]
        const last = controls[controls.length - 1]
        if (!first) event.preventDefault()
        else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
      }}>
      {children}
    </dialog>,
    document.body,
  )
}
