import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'purchasing.e2e.ts',
  outputDir: 'test-results/purchasing-browser',
  use: { ...navigationConfig.use, baseURL: 'http://127.0.0.1:5184' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5184 --strictPort', url: 'http://127.0.0.1:5184', reuseExistingServer: false },
})
