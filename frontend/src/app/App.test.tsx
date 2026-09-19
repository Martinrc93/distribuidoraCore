import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App shell', () => {
  it('shows the primary navigation and dashboard content', () => {
    sessionStorage.setItem('distribuidora.accessToken', 'test-token')
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/dashboard']}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.getByRole('navigation', { name: /navegación principal/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /resumen operativo/i })).toBeInTheDocument()
    sessionStorage.removeItem('distribuidora.accessToken')
  })
})
