/// <reference types="vite/client" />

interface Window {
  tylerBackend?: {
    getBaseUrl: () => Promise<string>
  }
}
