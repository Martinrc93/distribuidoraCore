import { expect, test } from '@playwright/test'

test('duplicated tabs share a single refresh rotation', async ({ context }) => {
  const claims = Buffer.from(JSON.stringify({ sub: 'seller-1', authorities: ['ORDER_CREATE'] })).toString('base64url')
  const expiredAccess = `expired.${claims}.signature`
  const renewedAccess = `renewed.${claims}.signature`
  await context.addInitScript(access => {
    sessionStorage.setItem('distribuidora.accessToken', access)
    sessionStorage.setItem('distribuidora.refreshToken', 'shared-refresh')
  }, expiredAccess)

  let rotations = 0
  await context.route(url => url.pathname.startsWith('/api/'), async route => {
    if (new URL(route.request().url()).pathname === '/api/auth/refresh') {
      rotations++
      await route.fulfill({ json: { accessToken: renewedAccess, refreshToken: 'rotated-refresh' } })
    } else if (route.request().headers().authorization === `Bearer ${expiredAccess}`) {
      await route.fulfill({ status: 401, json: { detail: 'Session expired' } })
    } else {
      await route.fulfill({ json: { content: [], totalPages: 0, totalElements: 0 } })
    }
  })

  const tabs = await Promise.all([context.newPage(), context.newPage()])
  await Promise.all(tabs.map(tab => tab.goto('/orders')))
  for (const tab of tabs) {
    await expect.poll(() => tab.evaluate(() => sessionStorage.getItem('distribuidora.refreshToken')))
      .toBe('rotated-refresh')
    await expect(tab).toHaveURL(/\/orders$/)
  }
  expect(rotations).toBe(1)
})
