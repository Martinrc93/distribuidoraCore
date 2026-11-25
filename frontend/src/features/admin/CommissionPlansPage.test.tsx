import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CommissionPlansPage from './CommissionPlansPage'

function response(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status }))
}

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter><CommissionPlansPage /></MemoryRouter></QueryClientProvider>)
}

describe('CommissionPlansPage', () => {
  beforeEach(() => sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`))
  afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks() })

  it('lists, creates and edits commission plans with rules', async () => {
    const user = userEvent.setup()
    let plans: Array<{ id: string; name: string; ruleCount: number; sellerCount: number; resellerCount: number }> = []
    let storedPlan = { id: 'plan-1', name: 'Mayorista', version: 1, rules: [{ scope: 'DEFAULT', targetId: null, kind: 'PERCENT', value: '3.5' }] }

    const fetchMock = vi.spyOn(global, 'fetch').mockImplementation((input, init) => {
      const path = String(input)
      if (path === '/api/settings/commission-plans' && init?.method === 'POST') {
        const body = JSON.parse(String(init.body))
        plans = [{ id: 'plan-1', name: body.name, ruleCount: body.rules.length, sellerCount: 0, resellerCount: 0 }]
        storedPlan = { id: 'plan-1', version: 1, ...body }
        return response(storedPlan, 201)
      }
      if (path === '/api/settings/commission-plans/plan-1' && init?.method === 'PUT') {
        const body = JSON.parse(String(init.body))
        plans = [{ id: 'plan-1', name: body.name, ruleCount: body.rules.length, sellerCount: 0, resellerCount: 0 }]
        storedPlan = { id: 'plan-1', version: 2, ...body }
        return response(storedPlan)
      }
      if (path === '/api/settings/commission-plans') return response(plans)
      if (path === '/api/settings/commission-plans/plan-1') return response(storedPlan)
      if (path === '/api/brands') return response([{ id: 'brand-1', name: 'Molino Norte' }])
      return response(emptyPage)
    })

    renderPage()
    await user.click(await screen.findByRole('button', { name: '+ Nuevo plan' }))
    const dialog = screen.getByRole('dialog', { name: 'Nuevo plan de comisión' })
    await user.type(within(dialog).getByLabelText('Nombre del plan'), 'Mayorista')
    await user.type(within(dialog).getByLabelText('Porcentaje (%)'), '3,5')
    await user.click(within(dialog).getByRole('button', { name: 'Guardar plan' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/settings/commission-plans', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ name: 'Mayorista', rules: [{ scope: 'DEFAULT', targetId: null, kind: 'PERCENT', value: '3.5' }] }),
    })))
    expect(await screen.findByText('Plan guardado.')).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: 'Editar plan Mayorista' }))
    const editDialog = await screen.findByRole('dialog', { name: 'Editar plan de comisión' })
    const nameInput = within(editDialog).getByLabelText('Nombre del plan')
    await user.clear(nameInput)
    await user.type(nameInput, 'Mayorista actualizado')
    await user.click(within(editDialog).getByRole('button', { name: 'Guardar plan' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/settings/commission-plans/plan-1', expect.objectContaining({ method: 'PUT' })))
    expect(await screen.findByRole('button', { name: 'Editar plan Mayorista actualizado' })).toBeInTheDocument()
  })

  it('retries failed initial plans fetch', async () => {
    vi.spyOn(global, 'fetch')
      .mockImplementationOnce(() => response({ detail: 'Error' }, 500))
      .mockImplementation((input) => String(input) === '/api/settings/commission-plans' ? response([]) : response(emptyPage))
    renderPage()

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Reintentar' }))
    expect(await screen.findByText('Planes de comisiones')).toBeInTheDocument()
  })
})
