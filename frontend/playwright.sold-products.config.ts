import { defineConfig } from '@playwright/test'
import base from './playwright.navigation.config'

export default defineConfig({
  ...base,
  testMatch: 'sold-products.e2e.ts',
  outputDir: 'test-results/sold-products',
  use: { ...base.use, baseURL: 'http://127.0.0.1:5197' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5197 --strictPort', url: 'http://127.0.0.1:5197', reuseExistingServer: false },
})
