import { defineConfig } from 'vitest/config'

// Separate from the development server config: tests need no backend process or port.
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    clearMocks: true,
  },
})
