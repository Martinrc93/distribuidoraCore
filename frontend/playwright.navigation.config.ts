import { defineConfig } from '@playwright/test'

// Navigation tests mock the API and do not require a database or backend.
export default defineConfig({
  testDir: './src/test',
  testMatch: 'navigation.e2e.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 30_000,
  reporter: 'list',
  outputDir: 'test-results/navigation',
  use: {
    baseURL: 'http://127.0.0.1:5180',
    trace: 'retain-on-failure',
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : undefined,
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5180 --strictPort',
    url: 'http://127.0.0.1:5180',
    reuseExistingServer: false,
  },
})
