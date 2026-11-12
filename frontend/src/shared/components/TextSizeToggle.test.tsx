import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { initializeTextSize } from '../textSize'
import { TextSizeToggle } from './TextSizeToggle'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  localStorage.clear()
  delete document.documentElement.dataset.textSize
})

describe('Text size selection', () => {
  it('switches with the keyboard and restores the saved preference on startup', async () => {
    initializeTextSize()
    const user = userEvent.setup()
    render(<TextSizeToggle />)
    const button = screen.getByRole('button', { name: 'Agrandar el texto' })
    expect(button).toHaveAttribute('aria-pressed', 'false')
    await user.tab()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: 'Usar texto de tamaño normal' })).toHaveAttribute('aria-pressed', 'true')
    expect(document.documentElement).toHaveAttribute('data-text-size', 'large')
    expect(localStorage.getItem('distribuidora.text-size')).toBe('large')
    delete document.documentElement.dataset.textSize
    act(() => initializeTextSize())
    expect(document.documentElement).toHaveAttribute('data-text-size', 'large')
    await user.keyboard(' ')
    expect(document.documentElement).not.toHaveAttribute('data-text-size')
    expect(localStorage.getItem('distribuidora.text-size')).toBe('normal')
  })

  it('keeps switching when browser storage is unavailable and syncs other tabs', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('Blocked') })
    initializeTextSize()
    render(<TextSizeToggle />)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Agrandar el texto' }))
    expect(document.documentElement).toHaveAttribute('data-text-size', 'large')
    vi.restoreAllMocks()
    act(() => {
      localStorage.setItem('distribuidora.text-size', 'normal')
      window.dispatchEvent(new StorageEvent('storage', { key: 'distribuidora.text-size', newValue: 'normal' }))
    })
    expect(screen.getByRole('button', { name: 'Agrandar el texto' })).toBeInTheDocument()
  })
})
