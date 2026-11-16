import { useState } from 'react'
import { apiGetBlob, apiPrintPdf, ApiError } from '../api/client'
import { Button } from './Button'

type Props = {
  /** Authenticated PDF endpoint. */
  path: string
  filename: string
  /** Visible names, e.g. «Imprimir remito A4» and «Descargar remito A4». */
  printLabel: string
  downloadLabel: string
  disabled?: boolean
  onDone?: (message: string) => void
}

const touchDevice = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  && window.matchMedia('(pointer: coarse)').matches

/**
 * Print or download a server-generated PDF. Printing opens the browser print dialog without downloading;
 * on touch devices the PDF opens in a new tab, from which the device prints or shares it.
 */
export function DocumentActions({ path, filename, printLabel, downloadLabel, disabled = false, onDone }: Props) {
  const [pending, setPending] = useState<'print' | 'download' | ''>('')
  const [error, setError] = useState('')

  async function run(kind: 'print' | 'download') {
    if (pending) return
    // The tab must be opened synchronously in the click so the browser does not block it.
    const target = kind === 'print' && touchDevice() ? window.open('', '_blank') : null
    setPending(kind)
    setError('')
    try {
      if (kind === 'print') await apiPrintPdf(path, target)
      else await apiGetBlob(path, filename)
      onDone?.(kind === 'print' ? 'Documento enviado a imprimir.' : 'Documento descargado.')
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.detail || cause.message : cause instanceof Error ? cause.message : 'No se pudo generar el documento.')
    } finally {
      setPending('')
    }
  }

  return <span className="document-actions">
    <Button type="button" variant="secondary" disabled={disabled || Boolean(pending)} onClick={() => void run('print')}>{pending === 'print' ? 'Preparando...' : printLabel}</Button>
    <Button type="button" variant="secondary" disabled={disabled || Boolean(pending)} onClick={() => void run('download')}>{pending === 'download' ? 'Descargando...' : downloadLabel}</Button>
    {error && <span className="error-text document-actions-error" role="alert">{error}</span>}
  </span>
}
