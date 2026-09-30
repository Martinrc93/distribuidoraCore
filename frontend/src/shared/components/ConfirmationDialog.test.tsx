import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { ConfirmationDialog } from './ConfirmationDialog'

afterEach(cleanup)

it('focuses the safe action, dismisses on Escape and restores focus and scroll on unmount', () => {
  const trigger = document.createElement('button')
  document.body.append(trigger)
  trigger.focus()
  const originalOverflow = document.body.style.overflow
  const onCancel = vi.fn()
  const { unmount } = render(<ConfirmationDialog title="Cancelar operación" description="Se perderán los datos." confirmLabel="Descartar" cancelLabel="Seguir editando" onCancel={onCancel} onConfirm={vi.fn()} />)
  const dialog = screen.getByRole('dialog', { name: 'Cancelar operación' })
  expect(dialog).toHaveAccessibleDescription('Se perderán los datos.')
  expect(screen.getByRole('button', { name: 'Seguir editando' })).toHaveFocus()
  expect(document.body.style.overflow).toBe('hidden')
  fireEvent(dialog, new Event('cancel', { cancelable: true }))
  expect(onCancel).toHaveBeenCalledOnce()
  unmount()
  expect(trigger).toHaveFocus()
  expect(document.body.style.overflow).toBe(originalOverflow)
  trigger.remove()
})

it('blocks actions and Escape while pending and keeps recovery errors accessible', async () => {
  const user = userEvent.setup()
  const onCancel = vi.fn()
  const onConfirm = vi.fn()
  const props = { title: 'Cancelar pedido', description: 'Se devolverán los productos al stock.', confirmLabel: 'Confirmar cancelación', onCancel, onConfirm }
  const { rerender } = render(<ConfirmationDialog {...props} pending pendingLabel="Cancelando..." />)
  await user.click(screen.getByRole('button', { name: 'Cancelando...' }))
  await user.click(screen.getByRole('button', { name: 'Volver' }))
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
  expect(onConfirm).not.toHaveBeenCalled()
  expect(onCancel).not.toHaveBeenCalled()
  rerender(<ConfirmationDialog {...props} error="No se pudo cancelar el pedido." />)
  expect(screen.getByRole('alert')).toHaveTextContent('No se pudo cancelar')
  await user.click(screen.getByRole('button', { name: 'Confirmar cancelación' }))
  expect(onConfirm).toHaveBeenCalledOnce()
})
