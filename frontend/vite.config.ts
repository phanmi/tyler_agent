import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Vite development server and build configuration.
// The frontend runs on port 5173 during development.
// Forward /api requests to Spring Boot on port 8080.
// The browser sees same-origin requests, so the frontend can use relative /api paths.
export default defineConfig({
  base: './',
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
