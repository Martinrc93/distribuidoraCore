import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CreditLimitPage from './CreditLimitPage'

function response(body: unknown, status = 200) { return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response) }

describe('CreditLimitPage', () => {
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

  it('loads and saves the global non-blocking credit limit', async () => {
    const user = userEvent.setup()
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/settings/credit-limit' && init?.method === 'PUT') return response({ creditLimit: 2500, enabled: true, updatedAt: '2026-09-24T10:00:00Z', updatedBy: 'user-1' })
      return response({ creditLimit: 1000, enabled: true, updatedAt: '2026-09-23T10:00:00Z', updatedBy: 'user-1' })
    })
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><CreditLimitPage /></MemoryRouter></QueryClientProvider>)

    await user.clear(await screen.findByLabelText('Límite de crédito'))
    await user.type(screen.getByLabelText('Límite de crédito'), '2500')
    await user.click(screen.getByRole('button', { name: /guardar límite/i }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/settings/credit-limit', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ creditLimit: 2500 }),
    })))
    expect(await screen.findByText('Límite de crédito actualizado.')).toBeInTheDocument()
  })
})
