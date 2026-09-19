import { afterEach, describe, expect, it } from 'vitest'
import { getAuthorities, hasAuthority } from './permissions'

function tokenWithPayload(payload: unknown) {
  const encodedPayload = btoa(JSON.stringify(payload)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '')
  return `header.${encodedPayload}.signature`
}

describe('permission helpers', () => {
  afterEach(() => {
    sessionStorage.clear()
  })

  it('returns authorities from the JWT payload', () => {
    sessionStorage.setItem('distribuidora.accessToken', tokenWithPayload({ authorities: ['ADMIN_ALL', 'CUSTOMER_WRITE'] }))

    expect(getAuthorities()).toEqual(['ADMIN_ALL', 'CUSTOMER_WRITE'])
    expect(hasAuthority('ADMIN_ALL')).toBe(true)
    expect(hasAuthority('PRODUCT_WRITE')).toBe(false)
  })

  it.each([
    ['no token', undefined],
    ['missing authorities', tokenWithPayload({ sub: 'user-1' })],
    ['non-array authorities', tokenWithPayload({ authorities: 'ADMIN_ALL' })],
    ['array with non-strings', tokenWithPayload({ authorities: ['ADMIN_ALL', 42] })],
    ['malformed token', 'not-a-jwt'],
  ])('returns no authorities for %s', (_case, token) => {
    if (token) sessionStorage.setItem('distribuidora.accessToken', token)

    expect(getAuthorities()).toEqual([])
  })
})
