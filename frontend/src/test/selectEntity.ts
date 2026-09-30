import { screen } from '@testing-library/react'
import type { UserEvent } from '@testing-library/user-event'

export async function selectEntity(user: UserEvent, label: string, optionName: string | RegExp) {
  const input = await screen.findByRole('combobox', { name: label })
  await user.click(input)
  await user.clear(input)
  await user.click(await screen.findByRole('option', { name: optionName }))
}
