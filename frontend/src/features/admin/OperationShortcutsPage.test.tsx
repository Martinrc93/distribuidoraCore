import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'
import OperationShortcutsPage from './OperationShortcutsPage'

function renderPage() {
  sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ sub: 'admin-1', authorities: ['ADMIN_ALL'] }))}.signature`)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter><OperationShortcutsPage /></MemoryRouter></QueryClientProvider>)
}

afterEach(() => { cleanup(); localStorage.clear(); sessionStorage.clear() })

describe('OperationShortcutsPage', () => {
  it('assigns shortcuts, rejects duplicates and clears an assigned shortcut', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(screen.getByRole('link', { name: 'Volver a Configuración' })).toHaveAttribute('href', '/admin/settings')
    const sales = screen.getByRole('textbox', { name: 'Ventas' })
    const customers = screen.getByRole('textbox', { name: 'Clientes' })

    sales.focus()
    await user.keyboard('{Alt>}{Shift>}v{/Alt}{/Shift}')
    expect(sales).toHaveValue('Alt+Mayús+V')

    customers.focus()
    await user.keyboard('{Alt>}{Shift>}v{/Alt}{/Shift}')
    expect(customers).toHaveValue('')
    expect(screen.getByRole('alert')).toHaveTextContent('Este atajo ya está asignado a Ventas.')

    await user.click(screen.getByRole('button', { name: 'Quitar atajo de Ventas' }))
    await waitFor(() => expect(sales).toHaveValue(''))
  })
})
