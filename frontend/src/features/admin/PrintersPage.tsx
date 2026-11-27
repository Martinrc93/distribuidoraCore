import { useCallback, useEffect, useId, useState } from 'react'
import { Badge } from '../../shared/components/Badge'
import { Button } from '../../shared/components/Button'
import { Panel } from '../../shared/components/Panel'
import {
  listLocalPrinters,
  printTestWithLocalAgent,
  usePrinterPreferences,
  type LocalPrinter,
  type PrintFormat,
} from '../../shared/printers'
import { SettingsPageHeader } from './SettingsPageHeader'

const formatMeta: { format: PrintFormat; label: string; testLabel: string; description: string }[] = [
  {
    format: 'a4',
    label: 'Impresora para A4',
    testLabel: 'Probar A4',
    description: 'Remitos A4, estados de deuda A4, listas de precios, compras y reportes.',
  },
  {
    format: 'ticket',
    label: 'Impresora para ticket 80 mm',
    testLabel: 'Probar ticket',
    description: 'Comprobantes de venta en ticket de 80 mm y estados de deuda en ticket.',
  },
]

export default function PrintersPage() {
  const id = useId()
  const { preferences, setPrinterPreference } = usePrinterPreferences()
  const [status, setStatus] = useState<'loading' | 'connected' | 'disconnected'>('loading')
  const [printers, setPrinters] = useState<LocalPrinter[]>([])
  const [testing, setTesting] = useState<PrintFormat | ''>('')
  const [feedback, setFeedback] = useState('')
  const [error, setError] = useState('')

  const refreshPrinters = useCallback(async (signal?: AbortSignal) => {
    setStatus('loading')
    try {
      const data = await listLocalPrinters(signal)
      if (signal?.aborted) return
      setPrinters(data.printers)
      setStatus('connected')
    } catch {
      if (signal?.aborted) return
      setPrinters([])
      setStatus('disconnected')
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void refreshPrinters(controller.signal)
    return () => controller.abort()
  }, [refreshPrinters])

  function handleSelect(format: PrintFormat, value: string) {
    const saveError = setPrinterPreference(format, value)
    if (saveError) {
      setError(saveError)
      setFeedback('')
      return
    }
    setError('')
    const formatName = format === 'ticket' ? 'ticket 80 mm' : 'A4'
    setFeedback(value
      ? `Impresora para ${formatName} guardada en esta PC: ${value}.`
      : `Los documentos ${formatName} volverán a abrir el diálogo del navegador.`)
  }

  async function handleTest(format: PrintFormat, printerName: string) {
    if (testing || !printerName) return
    setTesting(format)
    setError('')
    setFeedback('')
    try {
      await printTestWithLocalAgent(printerName, format)
      setFeedback(`Prueba enviada a ${printerName}.`)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : `No se pudo enviar la prueba a ${printerName}.`)
    } finally {
      setTesting('')
    }
  }

  const installedNames = new Set(printers.map((item) => item.name))

  return <div className="max-w-[860px]">
    <SettingsPageHeader
      title="Impresoras"
      description="Elegí qué impresora usa esta PC para documentos A4 y para tickets de 80 mm. La configuración se guarda al instante en este navegador."
    />
    <div className="content-grid">
      <Panel
        title="Servicio de impresión en esta PC"
        description="Conecta el navegador con las impresoras instaladas en Windows para imprimir directo sin cambiar de impresora cada vez."
        action={<div className="page-actions items-center">
          <Badge tone={status === 'connected' ? 'strong' : 'muted'}>
            {status === 'connected' ? 'Conectado' : status === 'loading' ? 'Buscando...' : 'No detectado'}
          </Badge>
          <Button type="button" variant="secondary" disabled={status === 'loading'} onClick={() => void refreshPrinters()}>
            {status === 'loading' ? 'Actualizando...' : 'Actualizar impresoras'}
          </Button>
        </div>}
      >
        {status === 'loading' && <p className="helper-text m-0" role="status">Consultando las impresoras conectadas a esta PC…</p>}
        {status === 'connected' && <p className="helper-text m-0" role="status">
          {printers.length === 0
            ? 'El servicio está activo, pero Windows no informó impresoras instaladas.'
            : `Se detectaron ${printers.length} ${printers.length === 1 ? 'impresora instalada' : 'impresoras instaladas'} en esta PC. Al tocar «Imprimir», cada formato saldrá directo por la impresora elegida.`}
        </p>}
        {status === 'disconnected' && <div className="warning-text" role="status">
          No se detectó el servicio de impresión local en esta PC (<code>127.0.0.1:47891</code>). Para imprimir automáticamente sin el diálogo del navegador, ejecutá <code>scripts\print-agent\instalar-inicio-windows.cmd</code> (arranca con Windows) o <code>scripts\print-agent\iniciar-impresion.cmd</code> y luego tocá «Actualizar impresoras». Mientras no esté activo, los botones de imprimir siguen abriendo el diálogo normal del navegador.
        </div>}
      </Panel>

      <Panel title="Impresora por formato" description="Cada cambio se guarda en esta PC y aplica a todos los usuarios que impriman desde este navegador.">
        <div className="form-grid mb-0">
          {formatMeta.map(({ format, label, testLabel, description }) => {
            const selectId = `${id}-${format}`
            const helpId = `${selectId}-help`
            const selected = preferences[format]
            const isDetected = Boolean(selected && installedNames.has(selected))
            const showSavedMissing = Boolean(selected && !isDetected)
            return <div className="grid min-w-0 content-start gap-[8px]" key={format}>
              <label className="field" htmlFor={selectId}>{label}</label>
              <select
                id={selectId}
                className="select w-full min-w-0 min-h-[44px]"
                value={selected}
                aria-describedby={helpId}
                disabled={status !== 'connected' && !selected}
                onChange={(event) => handleSelect(format, event.target.value)}
              >
                <option value="">Preguntar siempre (diálogo del navegador)</option>
                {showSavedMissing && <option value={selected}>{selected} (guardada · no detectada ahora)</option>}
                {printers.map((printer) => <option key={printer.name} value={printer.name}>
                  {printer.isDefault ? `${printer.name} (predeterminada de Windows)` : printer.name}
                </option>)}
              </select>
              <p id={helpId} className="helper-text m-0">{description}</p>
              <div className="page-actions items-center">
                <Button
                  type="button"
                  variant="secondary"
                  disabled={status !== 'connected' || !isDetected || testing !== ''}
                  onClick={() => void handleTest(format, selected)}
                >
                  {testing === format ? 'Enviando prueba...' : testLabel}
                </Button>
                {selected && <Button
                  type="button"
                  variant="link"
                  aria-label={`Usar diálogo del navegador para ${format === 'ticket' ? 'ticket 80 mm' : 'A4'}`}
                  onClick={() => handleSelect(format, '')}
                >
                  Usar diálogo
                </Button>}
              </div>
            </div>
          })}
        </div>
        <p className={feedback ? 'success-text mt-[14px] mb-0' : 'sr-only'} role="status">{feedback}</p>
        {error && <p className="error-text mt-[14px] mb-0" role="alert">{error}</p>}
      </Panel>
    </div>
  </div>
}
