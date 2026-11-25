import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
    maxWorkers: 4,
    testTimeout: 15_000,
    coverage: {
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/test/**', '**/*.test.{ts,tsx}', '**/*.d.ts'],
    },
  },
})
