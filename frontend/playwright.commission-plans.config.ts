import { defineConfig } from '@playwright/test'
import base from './playwright.navigation.config'

export default defineConfig({
  ...base,
  testMatch: 'commission-plans.e2e.ts',
  outputDir: 'test-results/commission-plans',
  use: { ...base.use, baseURL: 'http://127.0.0.1:5199' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5199 --strictPort', url: 'http://127.0.0.1:5199', reuseExistingServer: false },
})
