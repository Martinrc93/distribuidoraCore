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

afterEach(() => { cleanup(); vi.restoreAllMocks(); printPdf.mockReset(); getBlob.mockReset() })

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
})
