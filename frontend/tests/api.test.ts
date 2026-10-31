import { afterEach, describe, expect, it, vi } from 'vitest'

const TOKEN = '0123456789abcdef'.repeat(4)

// Each test loads a fresh module so the configured address and token do not leak between tests.
async function freshApi() {
  vi.resetModules()
  return import('../src/api')
}

function stubFetch(body: unknown = { provider: 'anthropic', configured: true }, status = 200) {
  const fetchMock = vi.fn(async () => new Response(JSON.stringify(body), { status }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const provider = (id: string, name: string, model: string) => ({
  id, name, model, defaultModel: model, suggestedModels: [model], configured: false,
})
const SETTINGS = {
  activeProvider: 'openai',
  providers: [
    provider('openai', 'OpenAI', 'gpt-6-sol'),
    provider('anthropic', 'Claude', 'claude-sonnet-5-5'),
    provider('deepseek', 'DeepSeek', 'deepseek-flash'),
  ],
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('backend access token', () => {
  it('is sent on every request once configured', async () => {
    const api = await freshApi()
    const fetchMock = stubFetch()
    api.configureApiBaseUrl('http://127.0.0.1:43210/', TOKEN)

    await api.saveProviderKey('anthropic', 'SYNTHETIC_KEY')

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://127.0.0.1:43210/api/llm/keys/anthropic')
    expect(init.method).toBe('PUT')
    const headers = new Headers(init.headers)
    expect(headers.get(api.ACCESS_TOKEN_HEADER)).toBe(TOKEN)
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('is not sent in browser development, where the proxy adds it', async () => {
    const api = await freshApi()
    const fetchMock = stubFetch(SETTINGS)

    await api.loadLlmSettings()

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/llm/settings')
    expect(new Headers(init.headers).has(api.ACCESS_TOKEN_HEADER)).toBe(false)
  })

  it.each(['', 'short', TOKEN.toUpperCase(), TOKEN + '0'])('rejects a malformed token %#', async (token) => {
    const api = await freshApi()
    expect(() => api.configureApiBaseUrl('http://127.0.0.1:43210/', token)).toThrow('Invalid backend access token')
  })
})

describe('provider settings calls', () => {
  it('loads and validates settings', async () => {
    const api = await freshApi()
    stubFetch(SETTINGS)
    const settings = await api.loadLlmSettings()
    expect(settings.activeProvider).toBe('openai')
    expect(settings.providers.map((item) => item.name)).toEqual(['OpenAI', 'Claude', 'DeepSeek'])
  })

  it.each([
    { ...SETTINGS, activeProvider: 'mistral' },
    { ...SETTINGS, providers: SETTINGS.providers.slice(0, 2) },
    { ...SETTINGS, providers: [...SETTINGS.providers.slice(0, 2), SETTINGS.providers[0]] },
    { ...SETTINGS, providers: [{ ...SETTINGS.providers[0], configured: 'yes' }, ...SETTINGS.providers.slice(1)] },
    { ...SETTINGS, providers: [{ ...SETTINGS.providers[0], suggestedModels: [1] }, ...SETTINGS.providers.slice(1)] },
    null,
  ])('rejects malformed settings %#', async (body) => {
    const api = await freshApi()
    stubFetch(body)
    await expect(api.loadLlmSettings()).rejects.toThrow('Invalid provider settings')
  })

  it('sends every model with the active provider when saving', async () => {
    const api = await freshApi()
    const fetchMock = stubFetch(SETTINGS)
    const models = { openai: 'gpt-5.6', anthropic: 'claude-opus-5-5', deepseek: 'deepseek-flash' }

    await api.saveLlmSettings({ activeProvider: 'anthropic', models })

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/llm/settings')
    expect(init.method).toBe('PUT')
    expect(JSON.parse(init.body as string)).toEqual({ activeProvider: 'anthropic', models })
  })

  it('keeps the HTTP status and the backend message on failure', async () => {
    const api = await freshApi()
    stubFetch({ error: 'API key must be at most 512 visible ASCII characters with no spaces' }, 400)

    const failure = await api.saveProviderKey('deepseek', 'bad key').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(api.ApiError)
    expect((failure as InstanceType<typeof api.ApiError>).status).toBe(400)
    expect((failure as Error).message).toBe('API key must be at most 512 visible ASCII characters with no spaces')
  })

  it('falls back to a status message when the error body is not JSON', async () => {
    const api = await freshApi()
    vi.stubGlobal('fetch', vi.fn(async () => new Response('oops', { status: 500 })))
    await expect(api.loadLlmSettings()).rejects.toThrow('Failed to load provider settings (HTTP 500)')
  })

  it('rejects a key status for another provider', async () => {
    const api = await freshApi()
    stubFetch({ provider: 'openai', configured: true })
    await expect(api.saveProviderKey('anthropic', 'SYNTHETIC_KEY')).rejects.toThrow('Invalid key status')
  })
})
