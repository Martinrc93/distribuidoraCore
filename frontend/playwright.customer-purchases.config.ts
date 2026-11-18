import { defineConfig } from '@playwright/test'
import base from './playwright.navigation.config'

export default defineConfig({
  ...base,
  testMatch: 'customer-purchases.e2e.ts',
  outputDir: 'test-results/customer-purchases',
  use: { ...base.use, baseURL: 'http://127.0.0.1:5198' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5198 --strictPort', url: 'http://127.0.0.1:5198', reuseExistingServer: false },
})
