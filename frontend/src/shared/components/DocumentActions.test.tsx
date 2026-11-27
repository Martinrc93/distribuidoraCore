import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const printPdf = vi.fn()
const getBlob = vi.fn()
vi.mock('../api/client', async (original) => ({
  ...(await original<typeof import('../api/client')>()),
  apiPrintPdf: (...args: unknown[]) => printPdf(...args),
  apiGetBlob: (...args: unknown[]) => getBlob(...args),
}))

const { DocumentActions } = await import('./DocumentActions')
const { ApiError } = await import('../api/client')

afterEach(() => { cleanup(); localStorage.clear(); vi.restoreAllMocks(); printPdf.mockReset(); getBlob.mockReset() })

describe('DocumentActions', () => {
  it('prints without downloading and downloads with the given filename', async () => {
    printPdf.mockResolvedValue(undefined)
    getBlob.mockResolvedValue(undefined)
    const done = vi.fn()
    const user = userEvent.setup()
    render(<DocumentActions path="/api/documents/x" filename="x.pdf" printLabel="Imprimir lista" downloadLabel="Descargar PDF" onDone={done} />)

    await user.click(screen.getByRole('button', { name: 'Imprimir lista' }))
    await waitFor(() => expect(printPdf).toHaveBeenCalledWith('/api/documents/x', null))
    expect(done).toHaveBeenCalledWith('Documento enviado a imprimir.')
    expect(getBlob).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Descargar PDF' }))
    await waitFor(() => expect(getBlob).toHaveBeenCalledWith('/api/documents/x', 'x.pdf'))
    expect(done).toHaveBeenLastCalledWith('Documento descargado.')
  })

  it('opens a tab synchronously on touch devices and shows API errors next to the buttons', async () => {
    const tab = { close: vi.fn() } as unknown as Window
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true } as MediaQueryList)
    const open = vi.spyOn(window, 'open').mockReturnValue(tab)
    printPdf.mockRejectedValue(new ApiError(404, 'Lista de precios inexistente'))
    const user = userEvent.setup()
    render(<DocumentActions path="/api/documents/y" filename="y.pdf" printLabel="Imprimir" downloadLabel="Descargar" />)

    await user.click(screen.getByRole('button', { name: 'Imprimir' }))
    expect(open).toHaveBeenCalledWith('', '_blank')
    await waitFor(() => expect(printPdf).toHaveBeenCalledWith('/api/documents/y', tab))
    expect(await screen.findByRole('alert')).toHaveTextContent('Lista de precios inexistente')
    expect(screen.getByRole('button', { name: 'Imprimir' })).toBeEnabled()
  })

  it('uses the saved PC printer for the document format and reports direct or fallback printing', async () => {
    localStorage.setItem('distribuidora.printers', JSON.stringify({ a4: 'HP LaserJet', ticket: 'EPSON TM-T20III' }))
    printPdf
      .mockResolvedValueOnce({ mode: 'agent', printer: 'EPSON TM-T20III' })
      .mockResolvedValueOnce({ mode: 'browser-fallback', printer: 'HP LaserJet' })
    const done = vi.fn()
    const user = userEvent.setup()

    render(<>
      <DocumentActions path="/api/orders/1/documents/ticket" filename="0001-ticket.pdf" format="ticket" printLabel="Imprimir ticket 80 mm" onDone={done} />
      <DocumentActions path="/api/orders/1/documents/a4" filename="0001-a4.pdf" format="a4" printLabel="Imprimir remito A4" onDone={done} />
    </>)

    const ticketButton = screen.getByRole('button', { name: 'Imprimir ticket 80 mm' })
    expect(ticketButton).toHaveAttribute('title', 'Imprimir directo en EPSON TM-T20III')
    await user.click(ticketButton)
    await waitFor(() => expect(printPdf).toHaveBeenCalledWith('/api/orders/1/documents/ticket', null, { format: 'ticket', filename: '0001-ticket.pdf' }))
    expect(done).toHaveBeenCalledWith('Documento enviado a EPSON TM-T20III.')

    await user.click(screen.getByRole('button', { name: 'Imprimir remito A4' }))
    await waitFor(() => expect(printPdf).toHaveBeenCalledWith('/api/orders/1/documents/a4', null, { format: 'a4', filename: '0001-a4.pdf' }))
    expect(done).toHaveBeenLastCalledWith('Servicio de impresión local no disponible; se abrió el diálogo del navegador.')
  })
})
