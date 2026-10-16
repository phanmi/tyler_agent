import { defineConfig } from 'vitest/config'

// Separate from the development server config: tests need no backend process or port.
export default defineConfig({
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.{ts,tsx}'],
    clearMocks: true,
  },
})
