import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ExpensesPage from './ExpensesPage'

function response(body: unknown, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) } as Response)
}

describe('ExpensesPage', () => {
  beforeEach(() => {
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
  })

  afterEach(() => {
    cleanup()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('parses Argentine formatted expense amounts with thousands separator and comma decimals', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      if (String(input) === '/api/expenses' && init?.method === 'POST') return response({ id: 'exp-1' }, 201)
      return response({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 })
    })

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <ExpensesPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    await user.click(await screen.findByRole('button', { name: 'Nuevo gasto' }))
    await user.type(screen.getByLabelText('Importe ($)'), '1.250,50')
    await user.selectOptions(screen.getByLabelText('Tipo'), 'FUEL')
    await user.click(screen.getByRole('button', { name: 'Guardar gasto' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/expenses', expect.objectContaining({
      method: 'POST',
      body: expect.stringContaining('"amount":1250.5'),
    })))
  })
})
