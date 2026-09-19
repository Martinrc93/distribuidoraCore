import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { DataTable } from './DataTable'

describe('DataTable', () => {
  it('exposes column labels on cells for the mobile card layout', () => {
    render(
      <DataTable
        columns={[{ key: 'name', label: 'Cliente' }]}
        rows={[{ name: 'Almacén La Esquina' }]}
      />,
    )

    expect(screen.getByText('Almacén La Esquina')).toHaveAttribute('data-label', 'Cliente')
  })
})
