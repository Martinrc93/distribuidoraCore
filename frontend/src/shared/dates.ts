export const businessTimeZone = 'America/Argentina/Buenos_Aires'

const dateTimeFormatter = new Intl.DateTimeFormat('es-AR', {
  timeZone: businessTimeZone,
  dateStyle: 'medium',
  timeStyle: 'short',
})

const dateTimeShortFormatter = new Intl.DateTimeFormat('es-AR', {
  timeZone: businessTimeZone,
  dateStyle: 'short',
  timeStyle: 'short',
})

const dateTimeNumericFormatter = new Intl.DateTimeFormat('es-AR', {
  timeZone: businessTimeZone,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

const dateFormatter = new Intl.DateTimeFormat('es-AR', {
  timeZone: businessTimeZone,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
})

const dateMediumFormatter = new Intl.DateTimeFormat('es-AR', {
  timeZone: businessTimeZone,
  dateStyle: 'medium',
})

const dateShortFormatter = new Intl.DateTimeFormat('es-AR', {
  timeZone: businessTimeZone,
  dateStyle: 'short',
})

export function formatBusinessDateTime(value?: string | null) {
  return value ? dateTimeFormatter.format(new Date(value)) : '—'
}

export function formatBusinessDateTimeShort(value?: string | null) {
  return value ? dateTimeShortFormatter.format(new Date(value)) : '—'
}

export function formatBusinessDateTimeNumeric(value?: string | null) {
  return value ? dateTimeNumericFormatter.format(new Date(value)) : '—'
}

export function formatBusinessDate(value?: string | null) {
  return value ? dateFormatter.format(new Date(value)) : '—'
}

export function formatBusinessDateMedium(value?: string | null) {
  return value ? dateMediumFormatter.format(new Date(value)) : '—'
}

export function formatBusinessDateShort(value?: string | null) {
  return value ? dateShortFormatter.format(new Date(value)) : '—'
}

export function todayInArgentina(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: businessTimeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const part = (type: string) => parts.find((item) => item.type === type)!.value
  return `${part('year')}-${part('month')}-${part('day')}`
}

export function displayDate(iso: string) {
  const [year, month, day] = iso.split('-')
  return `${day}/${month}/${year}`
}

export function parseDate(value: string) {
  const match = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(value)
  if (!match) return null
  const [, day, month, year] = match
  const iso = `${year}-${month}-${day}`
  const date = new Date(`${iso}T00:00:00Z`)
  return Number(year) >= 1900 && !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === iso ? iso : null
}

export function parseOptionalDate(value: string) {
  if (!value) return ''
  return parseDate(value)
}

export function presetRange(preset: string, today: string) {
  const day = new Date(`${today}T00:00:00Z`)
  if (preset === 'week') day.setUTCDate(day.getUTCDate() - ((day.getUTCDay() + 6) % 7))
  if (preset === 'month') day.setUTCDate(1)
  return { start: day.toISOString().slice(0, 10), end: today }
}

export function rangeError(start: string | null, end: string | null, today: string) {
  if (!start || !end) return 'Ingresá ambas fechas con formato dd/mm/aaaa.'
  if (start > end) return 'La fecha inicial no puede ser posterior a la final.'
  if (end > today) return 'El período no puede incluir fechas futuras.'
  const days = (Date.parse(end) - Date.parse(start)) / 86_400_000 + 1
  return days > 366 ? 'El período no puede superar 366 días.' : ''
}
