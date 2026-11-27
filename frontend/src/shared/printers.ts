import { useMemo, useSyncExternalStore } from 'react'

export type PrintFormat = 'a4' | 'ticket'

export type PrinterPreferences = {
  a4: string
  ticket: string
}

export type LocalPrinter = {
  name: string
  isDefault: boolean
}

export type LocalPrintersResponse = {
  defaultPrinter: string
  printers: LocalPrinter[]
}

export const printAgentBaseUrl = 'http://127.0.0.1:47891'

const storageKey = 'distribuidora.printers'
const changeEvent = 'distribuidora-printers-change'
const discoveryTimeoutMs = 2_500
const printTimeoutMs = 30_000

export class PrintAgentError extends Error {
  readonly unreachable: boolean

  constructor(message: string, unreachable = false) {
    super(message)
    this.name = 'PrintAgentError'
    this.unreachable = unreachable
  }
}

export function inferPrintFormat(path: string): PrintFormat {
  if (/\/ticket(?:$|[/?#])/i.test(path) || /[?&]format=ticket(?:&|$)/i.test(path)) return 'ticket'
  return 'a4'
}

function readRawPreferences(): string | null {
  try {
    return localStorage.getItem(storageKey)
  } catch {
    return null
  }
}

function parsePreferences(raw: string | null): PrinterPreferences {
  if (!raw) return { a4: '', ticket: '' }
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return { a4: '', ticket: '' }
    const record = parsed as Record<string, unknown>
    return {
      a4: typeof record.a4 === 'string' ? record.a4.trim() : '',
      ticket: typeof record.ticket === 'string' ? record.ticket.trim() : '',
    }
  } catch {
    return { a4: '', ticket: '' }
  }
}

export function getPrinterPreferences(): PrinterPreferences {
  return parsePreferences(readRawPreferences())
}

export function getConfiguredPrinter(format: PrintFormat): string {
  return getPrinterPreferences()[format]
}

export function setPrinterPreference(format: PrintFormat, printerName: string): string | undefined {
  const next = getPrinterPreferences()
  next[format] = printerName.trim()
  try {
    if (!next.a4 && !next.ticket) localStorage.removeItem(storageKey)
    else localStorage.setItem(storageKey, JSON.stringify(next))
  } catch {
    return 'No se pudo guardar la impresora en este navegador.'
  }
  window.dispatchEvent(new Event(changeEvent))
  return undefined
}

export function subscribeToPrinterPreferences(onChange: () => void) {
  function onStorage(event: StorageEvent) {
    if (event.key !== storageKey && event.key !== null) return
    onChange()
  }
  window.addEventListener(changeEvent, onChange)
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(changeEvent, onChange)
    window.removeEventListener('storage', onStorage)
  }
}

export function usePrinterPreferences() {
  const raw = useSyncExternalStore(subscribeToPrinterPreferences, readRawPreferences)
  const preferences = useMemo(() => parsePreferences(raw), [raw])
  return { preferences, setPrinterPreference }
}

async function fetchWithTimeout(url: string, init: RequestInit, timeoutMs: number, externalSignal?: AbortSignal): Promise<Response> {
  const controller = new AbortController()
  const onExternalAbort = () => controller.abort()
  if (externalSignal) {
    if (externalSignal.aborted) controller.abort()
    else externalSignal.addEventListener('abort', onExternalAbort, { once: true })
  }
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  try {
    return await fetch(url, { ...init, signal: controller.signal })
  } catch (cause) {
    if (externalSignal?.aborted) throw cause
    throw new PrintAgentError('No se pudo conectar con el servicio de impresión local en esta PC.', true)
  } finally {
    window.clearTimeout(timer)
    externalSignal?.removeEventListener('abort', onExternalAbort)
  }
}

async function readAgentError(response: Response, fallback: string): Promise<string> {
  const body = await response.json().catch(() => null) as { error?: unknown } | null
  return typeof body?.error === 'string' && body.error.trim() ? body.error.trim() : fallback
}

export async function listLocalPrinters(signal?: AbortSignal): Promise<LocalPrintersResponse> {
  const response = await fetchWithTimeout(`${printAgentBaseUrl}/printers`, { method: 'GET' }, discoveryTimeoutMs, signal)
  if (!response.ok) {
    throw new PrintAgentError(await readAgentError(response, 'No se pudieron consultar las impresoras de esta PC.'), false)
  }
  const body = await response.json().catch(() => null) as { defaultPrinter?: unknown; printers?: unknown } | null
  const rawList = Array.isArray(body?.printers) ? body.printers : []
  const printers: LocalPrinter[] = rawList
    .filter((item): item is { name: string; isDefault?: unknown } => typeof item === 'object' && item !== null && typeof (item as { name?: unknown }).name === 'string' && (item as { name: string }).name.trim().length > 0)
    .map((item) => ({ name: item.name, isDefault: item.isDefault === true }))
  return {
    defaultPrinter: typeof body?.defaultPrinter === 'string' ? body.defaultPrinter : '',
    printers,
  }
}

export async function printPdfWithLocalAgent(pdf: Blob, printer: string, format: PrintFormat, jobName?: string): Promise<void> {
  const params = new URLSearchParams({ printer, format })
  if (jobName) params.set('jobName', jobName)
  const response = await fetchWithTimeout(`${printAgentBaseUrl}/print?${params.toString()}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/pdf' },
    body: pdf,
  }, printTimeoutMs)
  if (!response.ok) {
    throw new PrintAgentError(await readAgentError(response, `No se pudo imprimir en ${printer}.`), false)
  }
}

export async function printTestWithLocalAgent(printer: string, format: PrintFormat): Promise<void> {
  const params = new URLSearchParams({ printer, format })
  const response = await fetchWithTimeout(`${printAgentBaseUrl}/test?${params.toString()}`, {
    method: 'POST',
  }, printTimeoutMs)
  if (!response.ok) {
    throw new PrintAgentError(await readAgentError(response, `No se pudo enviar la prueba a ${printer}.`), false)
  }
}
