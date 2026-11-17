import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'roles.e2e.ts',
  outputDir: 'test-results/roles',
  use: { ...navigationConfig.use, baseURL: 'http://127.0.0.1:5190' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5190 --strictPort', url: 'http://127.0.0.1:5190', reuseExistingServer: false },
})
