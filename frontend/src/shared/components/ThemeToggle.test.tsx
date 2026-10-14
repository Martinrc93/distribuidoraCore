import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { initializeTheme } from '../theme'
import { ThemeToggle } from './ThemeToggle'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  localStorage.clear()
  delete document.documentElement.dataset.theme
})

describe('Theme selection', () => {
  it('switches with the keyboard and restores the saved preference on startup', async () => {
    initializeTheme()
    const user = userEvent.setup()
    render(<ThemeToggle />)
    const button = screen.getByRole('button', { name: 'Cambiar a modo oscuro' })
    expect(button).toHaveAttribute('aria-pressed', 'false')
    await user.tab()
    expect(button).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: 'Cambiar a modo claro' })).toHaveAttribute('aria-pressed', 'true')
    expect(localStorage.getItem('distribuidora.theme')).toBe('dark')
    delete document.documentElement.dataset.theme
    act(() => initializeTheme())
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    await user.keyboard(' ')
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    expect(localStorage.getItem('distribuidora.theme')).toBe('light')
  })

  it('keeps switching when browser storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Blocked') })
    initializeTheme()
    render(<ThemeToggle />)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Cambiar a modo oscuro' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
  })

  it('synchronizes changes from another tab and defaults invalid saved values to light', () => {
    localStorage.setItem('distribuidora.theme', 'invalid')
    initializeTheme()
    render(<ThemeToggle />)
    expect(screen.getByRole('button', { name: 'Cambiar a modo oscuro' })).toBeInTheDocument()
    act(() => {
      localStorage.setItem('distribuidora.theme', 'dark')
      window.dispatchEvent(new StorageEvent('storage', { key: 'distribuidora.theme', newValue: 'dark' }))
    })
    expect(screen.getByRole('button', { name: 'Cambiar a modo claro' })).toBeInTheDocument()
  })
})
