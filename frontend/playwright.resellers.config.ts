import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'resellers.e2e.ts',
  outputDir: 'test-results/resellers',
  use: { ...navigationConfig.use, baseURL: 'http://127.0.0.1:5201' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5201 --strictPort', url: 'http://127.0.0.1:5201', reuseExistingServer: false },
})
