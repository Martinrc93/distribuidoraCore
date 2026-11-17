import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'commerce-permissions.e2e.ts',
  outputDir: 'test-results/commerce-permissions',
  use: { ...navigationConfig.use, baseURL: 'http://127.0.0.1:5192' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5192 --strictPort', url: 'http://127.0.0.1:5192', reuseExistingServer: false },
})
