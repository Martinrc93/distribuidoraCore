import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './src/test', testMatch: 'dashboard-analytics.e2e.ts', workers: 1, timeout: 30_000,
  reporter: 'list', outputDir: 'test-results/dashboard-analytics',
  use: { baseURL: 'http://127.0.0.1:5182', trace: 'retain-on-failure', launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : undefined },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5182 --strictPort', url: 'http://127.0.0.1:5182', reuseExistingServer: false },
})
