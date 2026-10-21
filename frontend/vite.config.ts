import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'

export default defineConfig(({ mode }) => ({
  plugins: [react(), tailwindcss()],
  ...(mode === 'instance-launcher' ? {
    build: { rollupOptions: { input: fileURLToPath(new URL('./instance-launcher.html', import.meta.url)) } },
  } : {}),
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
}))
