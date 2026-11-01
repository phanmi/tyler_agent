import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import * as api from '../../src/api'
import type { LlmSettings } from '../../src/api'
import App from '../../src/App'

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>()
  return {
    ...actual,
    loadLlmSettings: vi.fn(), loadChatHistory: vi.fn(), sendMessage: vi.fn(), loadFoodByDate: vi.fn(),
  }
})
vi.mock('react-calendar', () => ({ default: () => null }))

function settings(active: LlmSettings['activeProvider'], activeConfigured: boolean): LlmSettings {
  const base = [
    { id: 'openai' as const, name: 'OpenAI', model: 'gpt-6-sol' },
    { id: 'anthropic' as const, name: 'Claude', model: 'claude-sonnet-5-5' },
    { id: 'deepseek' as const, name: 'DeepSeek', model: 'deepseek-flash' },
  ]
  return {
    activeProvider: active,
    providers: base.map((item) => ({
      ...item, defaultModel: item.model, suggestedModels: [item.model],
      // Every other provider has a key, so only the active provider decides.
      configured: item.id === active ? activeConfigured : true,
    })),
  }
}

async function send(text: string) {
  const input = screen.getByPlaceholderText(/Write a message/)
  fireEvent.change(input, { target: { value: text } })
  await act(async () => { fireEvent.submit(input.closest('form')!) })
}

beforeEach(() => {
  // jsdom has no layout, so it does not implement scrolling.
  Element.prototype.scrollIntoView = vi.fn()
  vi.mocked(api.loadChatHistory).mockResolvedValue([])
  vi.mocked(api.loadFoodByDate).mockResolvedValue([])
  vi.mocked(api.sendMessage).mockResolvedValue({ id: 'r', role: 'assistant', content: 'Hello back' })
})
afterEach(() => {
  cleanup()
  vi.mocked(api.loadLlmSettings).mockReset()
  vi.mocked(api.sendMessage).mockReset()
})

it('names the active provider when its key is missing and does not call the backend', async () => {
  vi.mocked(api.loadLlmSettings).mockResolvedValue(settings('anthropic', false))
  await act(async () => { render(<App />) })

  await send('hello')

  expect(screen.getByText('Claude API key is empty; chat is unavailable')).toBeTruthy()
  expect(api.sendMessage).not.toHaveBeenCalled()
})

it('sends when the active provider has a key, even if another provider has none', async () => {
  const value = settings('deepseek', true)
  value.providers[0].configured = false
  vi.mocked(api.loadLlmSettings).mockResolvedValue(value)
  await act(async () => { render(<App />) })

  await send('hello')

  expect(api.sendMessage).toHaveBeenCalledWith('hello')
  expect(screen.getByText('Hello back')).toBeTruthy()
})

it('lets the backend decide when provider settings could not be loaded', async () => {
  vi.mocked(api.loadLlmSettings).mockRejectedValue(new Error('Failed to load provider settings (HTTP 500)'))
  vi.mocked(api.sendMessage).mockRejectedValue(new api.ApiError('OpenAI API key is empty; chat is unavailable', 400))
  await act(async () => { render(<App />) })

  await send('hello')

  expect(api.sendMessage).toHaveBeenCalledWith('hello')
  expect(screen.getByText('Error: OpenAI API key is empty; chat is unavailable')).toBeTruthy()
})
