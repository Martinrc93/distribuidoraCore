import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { printAgentBaseUrl } from '../../shared/printers'
import PrintersPage from './PrintersPage'

function renderPage() {
  render(<MemoryRouter><PrintersPage /></MemoryRouter>)
}

afterEach(() => {
  cleanup()
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('PrintersPage', () => {
  it('loads PC printers, saves A4 and ticket selections in localStorage and runs a test print', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = String(input)
      if (url === `${printAgentBaseUrl}/printers`) {
        return new Response(JSON.stringify({
          defaultPrinter: 'HP LaserJet',
          printers: [
            { name: 'HP LaserJet', isDefault: true },
            { name: 'EPSON TM-T20III', isDefault: false },
          ],
        }), { status: 200 })
      }
      if (url === `${printAgentBaseUrl}/test?printer=EPSON+TM-T20III&format=ticket`) {
        return new Response(JSON.stringify({ ok: true }), { status: 200 })
      }
      return new Response('{}', { status: 404 })
    })

    const user = userEvent.setup()
    renderPage()

    expect(screen.getByRole('link', { name: 'Volver a Configuración' })).toHaveAttribute('href', '/admin/settings')
    expect(await screen.findByText('Conectado')).toBeInTheDocument()

    const a4Select = screen.getByLabelText('Impresora para A4')
    const ticketSelect = screen.getByLabelText('Impresora para ticket 80 mm')

    await user.selectOptions(a4Select, 'HP LaserJet')
    await user.selectOptions(ticketSelect, 'EPSON TM-T20III')

    expect(JSON.parse(localStorage.getItem('distribuidora.printers') ?? '{}')).toEqual({
      a4: 'HP LaserJet',
      ticket: 'EPSON TM-T20III',
    })
    expect(screen.getByText('Impresora para ticket 80 mm guardada en esta PC: EPSON TM-T20III.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Probar ticket' }))
    expect(await screen.findByText('Prueba enviada a EPSON TM-T20III.')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      `${printAgentBaseUrl}/test?printer=EPSON+TM-T20III&format=ticket`,
      expect.objectContaining({ method: 'POST' }),
    )

    await user.click(screen.getByRole('button', { name: 'Usar diálogo del navegador para A4' }))
    expect(a4Select).toHaveValue('')
    expect(JSON.parse(localStorage.getItem('distribuidora.printers') ?? '{}')).toEqual({
      a4: '',
      ticket: 'EPSON TM-T20III',
    })
  })

  it('explains how to start the local agent when unavailable and preserves saved printers', async () => {
    localStorage.setItem('distribuidora.printers', JSON.stringify({ a4: '', ticket: 'EPSON TM-T20III' }))
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const user = userEvent.setup()

    renderPage()

    expect(await screen.findByText('No detectado')).toBeInTheDocument()
    expect(screen.getByText(/instalar-inicio-windows\.cmd/i)).toBeInTheDocument()
    expect(screen.getByLabelText('Impresora para ticket 80 mm')).toHaveValue('EPSON TM-T20III')
    expect(screen.getByRole('button', { name: 'Probar ticket' })).toBeDisabled()

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      defaultPrinter: 'EPSON TM-T20III',
      printers: [{ name: 'EPSON TM-T20III', isDefault: true }],
    }), { status: 200 }))

    await user.click(screen.getByRole('button', { name: 'Actualizar impresoras' }))
    await waitFor(() => expect(screen.getByText('Conectado')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Probar ticket' })).toBeEnabled()
  })
})
