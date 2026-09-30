import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import { Button } from '../../shared/components/Button'

const weekdays = ['lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado', 'domingo']
const dayFormatter = new Intl.DateTimeFormat('es-AR', { dateStyle: 'full', timeZone: 'UTC' })
const monthFormatter = new Intl.DateTimeFormat('es-AR', { month: 'long', year: 'numeric', timeZone: 'UTC' })

function todayDate() {
  const parts = new Intl.DateTimeFormat('es-AR', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'America/Argentina/Buenos_Aires' }).formatToParts(new Date())
  const part = (type: string) => parts.find((value) => value.type === type)?.value
  return new Date(`${part('year')}-${part('month')}-${part('day')}T12:00:00Z`)
}

function numericDate(date: Date) {
  const [year, month, day] = date.toISOString().slice(0, 10).split('-')
  return `${day}/${month}/${year}`
}

function moveMonth(date: Date, offset: number) {
  const target = new Date(date)
  target.setUTCDate(1)
  target.setUTCMonth(target.getUTCMonth() + offset)
  const last = new Date(target)
  last.setUTCMonth(last.getUTCMonth() + 1)
  last.setUTCDate(0)
  target.setUTCDate(Math.min(date.getUTCDate(), last.getUTCDate()))
  return target
}

type Props = {
  id: string
  label: string
  isoValue: string
  anchor: HTMLElement
  onSelect: (value: string) => void
  onDismiss: () => void
}

function positionCalendar(element: HTMLDialogElement, anchor: HTMLElement) {
  const rect = anchor.getBoundingClientRect()
  const width = Math.min(292, window.innerWidth - 24)
  element.style.width = `${width}px`
  const height = element.getBoundingClientRect().height
  element.style.left = `${Math.max(12, Math.min(rect.left, window.innerWidth - width - 12))}px`
  const top = rect.bottom + 6 + height <= window.innerHeight - 12 ? rect.bottom + 6 : rect.top - height - 6
  element.style.top = `${Math.max(12, Math.min(top, window.innerHeight - height - 12))}px`
}

export function OrderCalendar({ id, label, isoValue, anchor, onSelect, onDismiss }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const focusedDay = useRef<HTMLButtonElement>(null)
  const focusRequested = useRef(false)
  const [today] = useState(todayDate)
  const [cursor, setCursor] = useState(() => isoValue ? new Date(`${isoValue}T12:00:00Z`) : today)
  const month = monthFormatter.format(cursor)
  const first = new Date(cursor)
  first.setUTCDate(1)
  const last = new Date(first)
  last.setUTCMonth(last.getUTCMonth() + 1)
  last.setUTCDate(0)
  const offset = (first.getUTCDay() + 6) % 7
  const cells = Array.from({ length: Math.ceil((offset + last.getUTCDate()) / 7) * 7 }, (_, index) => {
    const day = index - offset + 1
    if (day < 1 || day > last.getUTCDate()) return null
    const date = new Date(first)
    date.setUTCDate(day)
    return date
  })

  useEffect(() => {
    const element = dialog.current!
    const position = () => positionCalendar(element, anchor)
    element.showModal()
    position()
    focusedDay.current?.focus()
    window.addEventListener('resize', position)
    window.addEventListener('scroll', position, true)
    return () => {
      window.removeEventListener('resize', position)
      window.removeEventListener('scroll', position, true)
      element.close()
      anchor.focus({ preventScroll: true })
    }
  }, [anchor])

  useEffect(() => {
    if (dialog.current) positionCalendar(dialog.current, anchor)
    if (focusRequested.current) {
      focusedDay.current?.focus()
      focusRequested.current = false
    }
  }, [cursor, anchor])

  function navigate(event: KeyboardEvent<HTMLButtonElement>) {
    const target = new Date(cursor)
    const weekday = (cursor.getUTCDay() + 6) % 7
    const offsets: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7, Home: -weekday, End: 6 - weekday }
    if (event.key in offsets) target.setUTCDate(target.getUTCDate() + offsets[event.key])
    else if (event.key === 'PageUp' || event.key === 'PageDown') {
      target.setTime(moveMonth(cursor, (event.key === 'PageUp' ? -1 : 1) * (event.shiftKey ? 12 : 1)).getTime())
    } else return
    event.preventDefault()
    if (target.getUTCFullYear() < 1 || target.getUTCFullYear() > 9999) return
    focusRequested.current = true
    setCursor(target)
  }

  function select(value: string) {
    onSelect(value)
    onDismiss()
  }

  return <dialog ref={dialog} className="orders-calendar-dialog" lang="es-AR" aria-modal="true"
    aria-labelledby={`${id}-calendar-label ${id}-calendar-month`}
    onCancel={(event) => { event.preventDefault(); onDismiss() }}
    onClick={(event) => {
      if (event.target !== event.currentTarget) return
      const rect = event.currentTarget.getBoundingClientRect()
      if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) onDismiss()
    }}>
    <span id={`${id}-calendar-label`} className="orders-calendar-label">Calendario: {label}</span>
    <div className="orders-calendar-header">
      <Button type="button" variant="link" aria-label="Mes anterior" disabled={cursor.getUTCFullYear() === 1 && cursor.getUTCMonth() === 0} onClick={() => setCursor(moveMonth(cursor, -1))}>‹</Button>
      <h2 id={`${id}-calendar-month`} aria-live="polite">{month[0].toUpperCase() + month.slice(1)}</h2>
      <Button type="button" variant="link" aria-label="Mes siguiente" disabled={cursor.getUTCFullYear() === 9999 && cursor.getUTCMonth() === 11} onClick={() => setCursor(moveMonth(cursor, 1))}>›</Button>
    </div>
    <table className="orders-calendar-grid" aria-labelledby={`${id}-calendar-month`}>
      <thead><tr>{weekdays.map((day) => <th scope="col" key={day}><abbr title={day}>{day.slice(0, 2)}</abbr></th>)}</tr></thead>
      <tbody>{Array.from({ length: cells.length / 7 }, (_, week) => <tr key={week}>
        {cells.slice(week * 7, week * 7 + 7).map((date, index) => <td key={index}>{date && <button type="button"
          ref={date.getUTCDate() === cursor.getUTCDate() ? focusedDay : undefined}
          tabIndex={date.getUTCDate() === cursor.getUTCDate() ? 0 : -1}
          aria-label={dayFormatter.format(date)} aria-pressed={date.toISOString().slice(0, 10) === isoValue}
          aria-current={numericDate(date) === numericDate(today) ? 'date' : undefined}
          onKeyDown={navigate} onClick={() => select(numericDate(date))}>{date.getUTCDate()}</button>}</td>)}
      </tr>)}</tbody>
    </table>
    <div className="orders-calendar-footer">
      <Button type="button" variant="link" onClick={() => select(numericDate(today))}>Hoy</Button>
      <Button type="button" variant="link" onClick={() => select('')}>Limpiar</Button>
      <Button type="button" variant="secondary" onClick={onDismiss}>Cerrar</Button>
    </div>
  </dialog>
}
