import { defineConfig, devices } from '@playwright/test'

const databaseUrl = process.env.E2E_DB_URL ?? process.env.POSTGRES_TEST_URL
if (!databaseUrl) {
  throw new Error('Set E2E_DB_URL to a disposable PostgreSQL database before running npm run test:e2e.')
}

const win = process.platform === 'win32'
const frontendUrl = 'http://127.0.0.1:5173'
const backendUrl = 'http://127.0.0.1:8080'
const mvn = win ? 'mvn.cmd' : 'mvn'
const npm = win ? 'npm.cmd' : 'npm'
const backendEnv = {
  DB_URL: databaseUrl,
  DB_USERNAME: process.env.E2E_DB_USERNAME ?? process.env.POSTGRES_TEST_USERNAME ?? 'distribuidora',
  DB_PASSWORD: process.env.E2E_DB_PASSWORD ?? process.env.POSTGRES_TEST_PASSWORD ?? 'distribuidora',
  JWT_SECRET: 'playwright-commercial-e2e-secret-2026-09-25',
  ADMIN_EMAIL: 'playwright-admin@example.test',
  ADMIN_PASSWORD: 'Playwright-E2E-Password-2026!',
  SEED_DEMO: 'false',
  OUTBOX_WORKER_ENABLED: 'false',
  NOTIFICATION_RETENTION_ENABLED: 'false',
}

export default defineConfig({
  testDir: './src/test',
  testMatch: '**/*.e2e.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  reporter: 'list',
  use: {
    baseURL: frontendUrl,
    trace: 'retain-on-failure',
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
      ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH }
      : undefined,
  },
  projects: [
    { name: 'desktop-chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile-chromium', use: { ...devices['Pixel 7'] } },
  ],
  webServer: [
    {
      command: `${mvn} -f ../backend/pom.xml --batch-mode --no-transfer-progress -Disolated.build.dir=target-codex-e2e spring-boot:run`,
      url: `${backendUrl}/actuator/health`,
      cwd: process.cwd(),
      env: backendEnv,
      reuseExistingServer: false,
      timeout: 120_000,
    },
    {
      command: `${npm} run dev -- --host 127.0.0.1 --port 5173`,
      url: frontendUrl,
      cwd: process.cwd(),
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
})
