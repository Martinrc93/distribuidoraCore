import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'customer-permissions.e2e.ts',
  outputDir: 'test-results/customer-permissions',
  use: { ...navigationConfig.use, baseURL: 'http://127.0.0.1:5194' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5194 --strictPort', url: 'http://127.0.0.1:5194', reuseExistingServer: false },
})
