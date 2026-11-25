import { defineConfig } from '@playwright/test'

const baseURL = 'http://127.0.0.1:5180'
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH

export default defineConfig({
  testDir: './src/test',
  testMatch: '**/*.e2e.ts',
  testIgnore: ['**/commercial.e2e.ts', '**/instances.e2e.ts'],
  workers: process.env.CI ? 2 : 1,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: 'list',
  outputDir: 'test-results/mocked',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    launchOptions: { executablePath },
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5180 --strictPort',
    url: baseURL,
    reuseExistingServer: false,
    env: { PLAYWRIGHT_MOCK_API: 'true' },
  },
})
