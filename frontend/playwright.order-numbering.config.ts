import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'order-numbering.e2e.ts',
  outputDir: 'test-results/order-numbering-browser',
  use: { ...navigationConfig.use, baseURL: 'http://127.0.0.1:5185' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5185 --strictPort', url: 'http://127.0.0.1:5185', reuseExistingServer: false },
})
