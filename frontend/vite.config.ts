import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'

export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  ...(mode === 'instance-launcher' ? {
    build: { rollupOptions: { input: fileURLToPath(new URL('./instance-launcher.html', import.meta.url)) } },
  } : {}),
  server: process.env.PLAYWRIGHT_MOCK_API === 'true' ? {} : {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
}))
