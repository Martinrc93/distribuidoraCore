import { useState, type ReactNode } from 'react'
import { apiGetBlob, apiPrintPdf, ApiError } from '../api/client'
import { inferPrintFormat, usePrinterPreferences, type PrintFormat } from '../printers'
import { Button } from './Button'
import { DocumentActionIcon } from './DocumentActionIcon'

type Props = {
  /** Authenticated PDF endpoint. */
  path: string
  filename: string
  /** Document paper format used to resolve the configured local printer on this PC. */
  format?: PrintFormat
  /** Full action names, e.g. «Imprimir remito A4» and «Descargar remito A4». */
  printLabel?: string
  downloadLabel?: string
  /** Display icon buttons with accessible names when the document is identified by a group heading. */
  iconOnly?: boolean
  renderActions?: (actions: { print: ReactNode; download: ReactNode }) => ReactNode
  disabled?: boolean
  onDone?: (message: string) => void
}

const touchDevice = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  && window.matchMedia('(pointer: coarse)').matches

/**
 * Print or download a server-generated PDF. On desktop, sends the PDF directly to the local printer
 * configured on this PC for its format (A4 or 80 mm ticket) when the local print agent is active;
 * otherwise opens the browser print dialog without downloading. On touch devices the PDF opens in a new tab.
 */
export function DocumentActions({ path, filename, format, printLabel, downloadLabel, iconOnly = false, renderActions, disabled = false, onDone }: Props) {
  const [pending, setPending] = useState<'print' | 'download' | ''>('')
  const [error, setError] = useState('')
  const { preferences } = usePrinterPreferences()
  const resolvedFormat = format ?? inferPrintFormat(path)
  const configuredPrinter = preferences[resolvedFormat]

  async function run(kind: 'print' | 'download') {
    if (pending) return
    // The tab must be opened synchronously in the click so the browser does not block it.
    const target = kind === 'print' && touchDevice() ? window.open('', '_blank') : null
    setPending(kind)
    setError('')
    try {
      if (kind === 'print') {
        const result = configuredPrinter && !target
          ? await apiPrintPdf(path, target, { format: resolvedFormat, filename })
          : await apiPrintPdf(path, target)
        if (result?.mode === 'agent' && result.printer) {
          onDone?.(`Documento enviado a ${result.printer}.`)
        } else if (result?.mode === 'browser-fallback') {
          onDone?.('Servicio de impresión local no disponible; se abrió el diálogo del navegador.')
        } else {
          onDone?.('Documento enviado a imprimir.')
        }
      } else {
        await apiGetBlob(path, filename)
        onDone?.('Documento descargado.')
      }
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.detail || cause.message : cause instanceof Error ? cause.message : 'No se pudo generar el documento.')
    } finally {
      setPending('')
    }
  }

  const print = printLabel && <Button type="button" variant="secondary" disabled={disabled || Boolean(pending)}
    aria-label={iconOnly ? printLabel : undefined} aria-busy={pending === 'print' || undefined}
    title={configuredPrinter ? `Imprimir directo en ${configuredPrinter}` : iconOnly ? printLabel : undefined}
    onClick={() => void run('print')}>
    {iconOnly ? <DocumentActionIcon action="print" /> : pending === 'print' ? 'Preparando...' : printLabel}
  </Button>
  const download = downloadLabel && <Button type="button" variant="secondary" disabled={disabled || Boolean(pending)}
    aria-label={iconOnly ? downloadLabel : undefined} aria-busy={pending === 'download' || undefined}
    title={iconOnly ? downloadLabel : undefined}
    onClick={() => void run('download')}>
    {iconOnly ? <DocumentActionIcon action="download" /> : pending === 'download' ? 'Descargando...' : downloadLabel}
  </Button>

  return <span className="document-actions">
    {renderActions ? renderActions({ print, download }) : <>{print}{download}</>}
    {error && <span className="error-text document-actions-error" role="alert">{error}</span>}
  </span>
}
