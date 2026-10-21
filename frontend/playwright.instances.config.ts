import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './src/test',
  testMatch: 'instances.e2e.ts',
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: 'list',
  outputDir: 'test-results/instances',
  use: {
    baseURL: process.env.DEV_INSTANCE_URL ?? 'http://127.0.0.1:3100',
    trace: 'retain-on-failure',
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : undefined,
  },
})
