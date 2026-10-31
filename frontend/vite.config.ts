import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Vite development server and build configuration.
// Vite prefers port 5173 but uses another port if it is occupied.
// The launcher provides the actual backend address for same-origin API requests.
export default defineConfig(({ command }) => {
  const backendUrl = process.env.TYLER_BACKEND_URL
  // The launcher's per-launch token; the proxy adds it so the page never holds it.
  const accessToken = process.env.TYLER_ACCESS_TOKEN
  if (command === 'serve' && !backendUrl) {
    throw new Error('TYLER_BACKEND_URL is required. Run npm run dev to start both servers.')
  }
  const proxy = backendUrl ? {
    '/api': {
      target: backendUrl,
      changeOrigin: true,
      headers: accessToken ? { 'X-Tyler-Token': accessToken } : {},
    },
  } : {}

  return {
    base: './',
    plugins: [react()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: false,
      proxy,
    },
    preview: {
      host: '127.0.0.1',
      strictPort: false,
      proxy,
    },
  }
})
