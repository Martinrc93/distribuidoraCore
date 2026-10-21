import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { appPath, instanceStorageKey } from './appBase'
import { Button } from './components/Button'

afterEach(() => { cleanup(); vi.unstubAllEnvs() })

describe('Instance deployment paths', () => {
  it('preserves root deployment routes and existing session keys', () => {
    vi.stubEnv('BASE_URL', '/')
    expect(appPath('/api/orders?page=1')).toBe('/api/orders?page=1')
    expect(instanceStorageKey('distribuidora.accessToken')).toBe('distribuidora.accessToken')
  })

  it.each(['/a/', '/b/'])('scopes API paths, navigation links and credentials to %s', (base) => {
    vi.stubEnv('BASE_URL', base)
    expect(appPath('/api/orders?page=1')).toBe(`${base}api/orders?page=1`)
    expect(instanceStorageKey('distribuidora.accessToken')).toBe(`distribuidora.accessToken:${base}`)
    render(<Button href="/orders/order-1?edit=true">Edit order</Button>)
    expect(screen.getByRole('link', { name: 'Edit order' })).toHaveAttribute('href', `${base}orders/order-1?edit=true`)
  })

  it('preserves external URLs, download URLs and fragment links', () => {
    vi.stubEnv('BASE_URL', '/a/')
    for (const url of ['https://example.test/document', '//example.test', 'blob:document', '#summary', 'mailto:example@example.test']) {
      expect(appPath(url)).toBe(url)
    }
  })
})
