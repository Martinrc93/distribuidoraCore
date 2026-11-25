import { useId, useLayoutEffect, useRef, useState, type ReactNode } from 'react'

const viewportInset = 10
const anchorGap = 6

export function SecondaryRowActions({ label, triggerId, children }: { label: string; triggerId?: string; children: ReactNode }) {
  const panelId = useId()
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)

  function closeAndFocus() {
    setOpen(false)
    trigger.current?.focus({ preventScroll: true })
  }

  useLayoutEffect(() => {
    if (!open || !trigger.current || !panel.current) return
    const anchor = trigger.current
    const surface = panel.current

    function positionPanel() {
      const { clientWidth, clientHeight } = document.documentElement
      const anchorRect = anchor.getBoundingClientRect()
      if (anchorRect.height && (anchorRect.bottom <= 0 || anchorRect.top >= clientHeight)) {
        setOpen(false)
        return
      }
      const { width } = surface.getBoundingClientRect()
      // The page's large-text preference scales CSS coordinates inside the table.
      const scale = surface.offsetWidth ? width / surface.offsetWidth : 1
      const height = (surface.scrollHeight + surface.offsetHeight - surface.clientHeight) * scale
      const above = anchorRect.top - anchorGap - viewportInset
      const below = clientHeight - anchorRect.bottom - anchorGap - viewportInset
      const opensAbove = below < height && above > below
      const left = Math.max(viewportInset, Math.min(anchorRect.right - width, clientWidth - width - viewportInset))
      surface.style.left = `${left / scale}px`
      surface.style.top = opensAbove ? '' : `${(anchorRect.bottom + anchorGap) / scale}px`
      surface.style.bottom = opensAbove ? `${(clientHeight - anchorRect.top + anchorGap) / scale}px` : ''
      surface.style.maxHeight = `${Math.max(0, opensAbove ? above : below) / scale}px`
    }

    function closeOnOutsidePress(event: PointerEvent) {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false)
    }

    positionPanel()
    document.addEventListener('pointerdown', closeOnOutsidePress, true)
    window.addEventListener('scroll', positionPanel, true)
    window.addEventListener('resize', positionPanel)
    return () => {
      document.removeEventListener('pointerdown', closeOnOutsidePress, true)
      window.removeEventListener('scroll', positionPanel, true)
      window.removeEventListener('resize', positionPanel)
    }
  }, [open])

  return <div ref={root} className="secondary-row-actions" onBlur={(event) => {
    const next = event.relatedTarget
    if (next instanceof Node && !event.currentTarget.contains(next)) setOpen(false)
  }} onKeyDown={(event) => {
    if (event.key !== 'Escape' || !open || event.defaultPrevented) return
    event.preventDefault()
    event.stopPropagation()
    closeAndFocus()
  }}>
    <button ref={trigger} id={triggerId} type="button" className="button button-secondary secondary-row-actions-trigger"
      aria-label={label} aria-expanded={open} aria-controls={panelId} onClick={(event) => {
        event.stopPropagation()
        setOpen((previous) => !previous)
      }}>
      Más acciones <span className="secondary-row-actions-chevron" aria-hidden="true" />
    </button>
    <div ref={panel} id={panelId} className="secondary-row-actions-content" role="group" aria-label="Acciones disponibles" hidden={!open} onClick={(event) => {
      event.stopPropagation()
      const action = event.target instanceof Element && event.target.closest('button, a[href]')
      // Document actions show progress and errors inline while their requests are pending.
      if (action && !action.closest('.document-actions')) closeAndFocus()
    }}>{children}</div>
  </div>
}
