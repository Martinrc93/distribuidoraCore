import { useRef, useState } from 'react'
import { Button } from '../../shared/components/Button'
import { OrderCalendar } from './OrderCalendar'

type Props = {
  id: string
  label: string
  value: string
  isoValue: string
  invalid: boolean
  describedBy?: string
  onChange: (value: string) => void
}

export function OrderDateFilter({ id, label, value, isoValue, invalid, describedBy, onChange }: Props) {
  const trigger = useRef<HTMLElement | null>(null)
  const [open, setOpen] = useState(false)

  function openCalendar(element: HTMLElement) {
    trigger.current = element
    setOpen(true)
  }

  function closeCalendar() {
    setOpen(false)
  }

  return <div className="field">
    <label htmlFor={id}>{label}</label>
    <div className="orders-date-control">
      <input id={id} className="input" type="text" placeholder="dd/mm/aaaa" maxLength={10}
        value={value} onChange={(event) => onChange(event.target.value)} onClick={(event) => openCalendar(event.currentTarget)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || (event.altKey && event.key === 'ArrowDown')) {
            event.preventDefault()
            openCalendar(event.currentTarget)
          }
        }} aria-invalid={invalid} aria-describedby={describedBy} aria-haspopup="dialog" aria-expanded={open} />
      <span className="orders-date-trigger">
        <Button type="button" variant="link" aria-label={`Abrir calendario: ${label}`} aria-haspopup="dialog" aria-expanded={open} onClick={(event) => openCalendar(event.currentTarget)}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <rect x="3" y="5" width="18" height="16" rx="2" />
            <path d="M16 3v4M8 3v4M3 11h18" />
          </svg>
        </Button>
      </span>
    </div>
    {open && trigger.current && <OrderCalendar id={id} label={label} isoValue={isoValue} anchor={trigger.current} onSelect={onChange} onDismiss={closeCalendar} />}
  </div>
}
