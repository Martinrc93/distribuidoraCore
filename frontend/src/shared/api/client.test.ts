import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiPatch, apiPost, apiPut } from './client'

describe('HTTP mutation helpers', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it.each([
    ['POST', apiPost],
    ['PUT', apiPut],
    ['PATCH', apiPatch],
  ])('sends a JSON %s request with the bearer token', async (method, request) => {
    sessionStorage.setItem('distribuidora.accessToken', 'access-token')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ id: 'saved' }), { status: 200 }),
    )

    await request<{ id: string }>('/api/resource', { name: 'Example' })

    expect(fetchMock).toHaveBeenCalledWith('/api/resource', {
      method,
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer access-token',
      },
      body: JSON.stringify({ name: 'Example' }),
    })
  })

  it('returns undefined for a 204 response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(null, { status: 204 }))

    await expect(apiPost('/api/resource', {})).resolves.toBeUndefined()
  })

  it('exposes the API detail for a conflict response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ detail: 'El registro ya existe' }), { status: 409 }),
    )

    await expect(apiPut('/api/resource/1', {})).rejects.toThrow('El registro ya existe')
  })
})
