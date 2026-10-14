import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'account-settlement.e2e.ts',
  outputDir: 'test-results/account-settlement',
  use: { ...navigationConfig.use, baseURL: 'http://127.0.0.1:5182' },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5182 --strictPort',
    url: 'http://127.0.0.1:5182',
    reuseExistingServer: false,
  },
})
