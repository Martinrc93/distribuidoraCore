import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AuditPage from './AuditPage'

function response(body: unknown) {
  return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response)
}

describe('AuditPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

  it('loads audit events using URL search and page state', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    const requestId = '8e15b19f-27a0-4e82-b2ab-ea81bd9334e6'
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation(() => response({
      content: [{ id: 'event-1', actor: 'admin@example.com', operation: 'DELIVERY_ATTEMPT', resourceType: 'ORDER', resourceId: 'order-1', result: 'SUCCESS', correlationId: requestId, createdAt: '2026-09-25T10:00:00Z' }],
      page: 1, size: 20, totalElements: 45, totalPages: 3,
    }))
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={['/admin/audit?page=1&search=DELIVERY_ATTEMPT']}><AuditPage /></MemoryRouter></QueryClientProvider>)

    expect(await screen.findByText('Registrar intento de entrega')).toHaveAttribute('title', 'DELIVERY_ATTEMPT')
    expect(screen.getByText('Correcto')).toBeInTheDocument()
    const identifier = screen.getByLabelText('ID de solicitud: ' + requestId)
    expect(identifier).toHaveAttribute('title', requestId)
    expect(identifier.closest('details')).not.toHaveAttribute('open')
    await user.click(identifier)
    expect(identifier.closest('details')).toHaveAttribute('open')
    expect(fetchMock).toHaveBeenCalledWith('/api/audit?page=1&size=20&search=DELIVERY_ATTEMPT', expect.anything())
    await user.click(screen.getByRole('button', { name: 'Anterior' }))
    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith('/api/audit?page=0&size=20&search=DELIVERY_ATTEMPT', expect.anything()))
    fireEvent.change(screen.getByLabelText('Filtrar eventos de auditoría'), { target: { value: 'Correcto' } })
    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith('/api/audit?page=0&size=20&search=SUCCESS', expect.anything()))
  })
})
