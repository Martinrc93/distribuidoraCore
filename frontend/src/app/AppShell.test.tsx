import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AppShell from './AppShell'

function mockViewport(compact: boolean) {
  const listeners = new Set<(event: { matches: boolean }) => void>()
  const media = {
    matches: compact,
    addEventListener: vi.fn((_type: string, listener: (event: { matches: boolean }) => void) => listeners.add(listener)),
    removeEventListener: vi.fn((_type: string, listener: (event: { matches: boolean }) => void) => listeners.delete(listener)),
  }
  vi.stubGlobal('matchMedia', vi.fn(() => media))
  return (matches: boolean) => {
    media.matches = matches
    listeners.forEach((listener) => listener({ matches }))
  }
}

function renderShell(initialEntry = '/orders/new') {
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={[initialEntry]}><Routes><Route element={<AppShell />}><Route path="*" element={<><h1>Page content</h1><Link to="/orders">Open orders</Link><Link to="/products">Open products</Link></>} /></Route></Routes></MemoryRouter></QueryClientProvider>)
}

describe('Responsive navigation', () => {
  afterEach(() => {
    cleanup()
    sessionStorage.clear()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('keeps the desktop navigation and identifies nested routes', () => {
    mockViewport(false)
    renderShell()
    expect(screen.getByRole('navigation', { name: 'Navegación principal' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Pagos y deuda' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Clientes' })).toBeInTheDocument()
    for (const name of ['Productos', 'Marcas y categorías', 'Listas de precios']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByText('Catálogo')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Pedidos' })).toHaveAttribute('aria-current', 'page')
    expect(document.querySelector('.breadcrumbs')).toHaveTextContent('Operación/Pedidos')
    expect(screen.queryByRole('button', { name: 'Abrir menú' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salir' })).toBeInTheDocument()
  })

  it('opens a compact menu with session controls and restores focus and scrolling on cancel', async () => {
    mockViewport(true)
    renderShell()
    const user = userEvent.setup()
    const trigger = screen.getByRole('button', { name: 'Abrir menú' })
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    await user.click(trigger)
    const dialog = screen.getByRole('dialog', { name: 'Menú principal' })
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'Cerrar menú' })).toHaveFocus()
    expect(screen.getByRole('button', { name: 'Salir' })).toBeInTheDocument()
    expect(document.body.style.overflow).toBe('hidden')
    fireEvent(dialog, new Event('cancel', { bubbles: true, cancelable: true }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(document.body.style.overflow).toBe('')
  })

  it('closes when selecting a route and updates the current section', async () => {
    mockViewport(true)
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    renderShell()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Abrir menú' }))
    expect(screen.queryByRole('link', { name: 'Pagos y deuda' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Catálogo' }))
    await user.click(screen.getByRole('link', { name: 'Productos' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(document.querySelector('.compact-brand')).toHaveTextContent('Productos')
    await user.click(screen.getByRole('button', { name: 'Abrir menú' }))
    expect(screen.getByRole('link', { name: 'Productos' })).toHaveAttribute('aria-current', 'page')
    await user.click(screen.getByRole('link', { name: 'Productos' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('keeps admin links restricted in the compact menu', async () => {
    mockViewport(true)
    sessionStorage.setItem('distribuidora.accessToken', 'unreadable-token')
    renderShell()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Abrir menú' }))
    expect(screen.queryByRole('link', { name: 'Usuarios' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Resumen' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Clientes' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Pagos y deuda' })).not.toBeInTheDocument()
    for (const name of ['Productos', 'Marcas y categorías', 'Listas de precios']) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByText('Catálogo')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Pedidos' })).toBeInTheDocument()
  })

  it('expands groups independently using pointer, Enter and Space', async () => {
    mockViewport(false)
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    renderShell()
    const user = userEvent.setup()
    const operation = screen.getByRole('button', { name: 'Operación' })
    const catalog = screen.getByRole('button', { name: 'Catálogo' })
    const administration = screen.getByRole('button', { name: 'Administración' })
    expect(operation).toHaveAttribute('aria-expanded', 'true')
    expect(catalog).toHaveAttribute('aria-expanded', 'false')
    expect(administration).toHaveAttribute('aria-expanded', 'false')
    expect(document.getElementById(catalog.getAttribute('aria-controls')!)).toHaveAttribute('hidden')

    await user.click(catalog)
    expect(screen.getByRole('link', { name: 'Productos' })).toBeVisible()
    expect(operation).toHaveAttribute('aria-expanded', 'true')
    expect(administration).toHaveAttribute('aria-expanded', 'false')
    operation.focus()
    await user.keyboard('{Enter}')
    expect(screen.queryByRole('link', { name: 'Pedidos' })).not.toBeInTheDocument()
    expect(catalog).toHaveAttribute('aria-expanded', 'true')
    await user.keyboard(' ')
    expect(screen.getByRole('link', { name: 'Pedidos' })).toBeVisible()
  })

  it('opens the destination group for direct URLs and subsequent route changes', async () => {
    mockViewport(false)
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    renderShell('/products')
    const user = userEvent.setup()
    expect(screen.getByRole('button', { name: 'Catálogo' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByRole('button', { name: 'Operación' })).toHaveAttribute('aria-expanded', 'false')
    await user.click(screen.getByRole('link', { name: 'Open orders' }))
    const operation = screen.getByRole('button', { name: 'Operación' })
    expect(operation).toHaveAttribute('aria-expanded', 'true')
    await user.click(operation)
    await user.click(screen.getByRole('link', { name: 'Open products' }))
    expect(operation).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('link', { name: 'Productos' })).toHaveAttribute('aria-current', 'page')
  })

  it('preserves collapsed groups between drawer openings and desktop, restoring focus to a visible control', async () => {
    const changeViewport = mockViewport(true)
    sessionStorage.setItem('distribuidora.accessToken', `header.${btoa(JSON.stringify({ authorities: ['ADMIN_ALL'] }))}.signature`)
    renderShell()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Abrir menú' }))
    await user.click(screen.getByRole('button', { name: 'Catálogo' }))
    await user.click(screen.getByRole('button', { name: 'Operación' }))
    await user.click(screen.getByRole('button', { name: 'Cerrar menú' }))
    await user.click(screen.getByRole('button', { name: 'Abrir menú' }))
    expect(screen.getByRole('button', { name: 'Operación' })).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByRole('button', { name: 'Catálogo' })).toHaveAttribute('aria-expanded', 'true')
    act(() => changeViewport(false))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Operación' })).toHaveFocus()
    expect(screen.getByRole('link', { name: 'Productos' })).toBeVisible()
    expect(document.body.style.overflow).toBe('')
  })
})
