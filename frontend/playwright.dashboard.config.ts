import { defineConfig } from '@playwright/test'
import navigationConfig from './playwright.navigation.config'

export default defineConfig({
  ...navigationConfig,
  testMatch: 'dashboard.e2e.ts',
  outputDir: 'test-results/dashboard',
})
