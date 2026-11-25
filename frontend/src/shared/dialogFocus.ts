import type { KeyboardEvent } from 'react'

const focusable =
  'button, input:not([type="hidden"]), select, textarea, a[href], summary, [tabindex], [contenteditable="true"]'

export function trapDialogFocus(event: KeyboardEvent<HTMLDialogElement>) {
  if (event.key !== 'Tab' || event.defaultPrevented) return
  const candidates = Array.from(event.currentTarget.querySelectorAll<HTMLElement>(focusable)).filter(control => {
    if (control.matches(':disabled') || control.closest('[hidden], [inert], [aria-hidden="true"]')) return false
    const collapsed = control.closest('details:not([open])')
    if (collapsed && control.closest('summary') !== collapsed.querySelector('summary')) return false
    const { display, visibility } = getComputedStyle(control)
    return control.tabIndex >= 0 && display !== 'none' && visibility !== 'hidden'
  })
  const controls = candidates.filter(control => {
    if (!(control instanceof HTMLInputElement) || control.type !== 'radio' || !control.name) return true
    const group = candidates.filter((item): item is HTMLInputElement =>
      item instanceof HTMLInputElement && item.type === 'radio' && item.name === control.name && item.form === control.form)
    return (group.find(item => item.checked) ?? group[0]) === control
  })

  const focusedIndex = controls.findIndex(control => control === document.activeElement)
  if (focusedIndex < 0 || (event.shiftKey ? focusedIndex === 0 : focusedIndex === controls.length - 1)) {
    event.preventDefault()
    const next = event.shiftKey ? controls.at(-1) : controls[0]
    next?.focus()
  }
}

export function focusDialogContent(dialog: HTMLDialogElement) {
  const field = dialog.querySelector<HTMLElement>('input:not([type="hidden"]):not(:disabled), select:not(:disabled), textarea:not(:disabled)')
  if (field) {
    field.focus()
    return
  }
  if (document.activeElement !== dialog && dialog.contains(document.activeElement)) return
  const heading = dialog.querySelector<HTMLElement>('h1, h2, h3')
  if (heading) {
    heading.tabIndex = -1
    heading.focus()
  }
}
