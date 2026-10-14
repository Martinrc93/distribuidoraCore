import { defineConfig } from '@playwright/test'
import base from './playwright.navigation.config'

export default defineConfig({
  ...base,
  testMatch: 'customer-detail.e2e.ts',
  outputDir: 'test-results/customer-history',
})
