import { defineConfig } from '@playwright/test'
import base from './playwright.navigation.config'

export default defineConfig({
  ...base,
  testMatch: 'stock-adjustment.e2e.ts',
  outputDir: 'test-results/stock-adjustment',
})
