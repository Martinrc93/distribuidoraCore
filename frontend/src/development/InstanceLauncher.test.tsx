import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { InstanceLauncher } from './InstanceLauncher'

afterEach(cleanup)

it('offers both systems through relative links without depending on a domain', () => {
  render(<InstanceLauncher />)
  expect(screen.getByRole('heading', { name: 'Seleccionar sistema' })).toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'Abrir sistema A' })).toHaveAttribute('href', '/a/')
  expect(screen.getByRole('link', { name: 'Abrir sistema B' })).toHaveAttribute('href', '/b/')
})
