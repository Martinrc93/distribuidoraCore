import { defineConfig } from '@playwright/test'
import base from './playwright.navigation.config'

export default defineConfig({
  ...base,
  testMatch: 'layout-preferences.e2e.ts',
  outputDir: 'test-results/layout-preferences',
})
