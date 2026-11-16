import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'order-fulfillment.e2e.ts',
  outputDir: 'test-results/order-fulfillment',
  use: { ...navigationConfig.use, baseURL: 'http://127.0.0.1:5188' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5188 --strictPort', url: 'http://127.0.0.1:5188', reuseExistingServer: false },
})
