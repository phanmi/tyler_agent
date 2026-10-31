import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../../src/api'
import type { LlmSettings, LlmSettingsUpdate, ProviderSettings } from '../../src/api'
import ProviderSettingsPanel from '../../src/components/settings/ProviderSettingsPanel'
import { useLlmSettings } from '../../src/components/settings/useLlmSettings'

// Keep the real constants and error class; replace only the network calls.
vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>()
  return { ...actual, loadLlmSettings: vi.fn(), saveLlmSettings: vi.fn(), saveProviderKey: vi.fn() }
})

const KEY = 'SYNTHETIC_FIXTURE_KEY_NEVER_SEND_TO_PROVIDER'

function provider(id: ProviderSettings['id'], name: string, suggestions: string[], configured = false): ProviderSettings {
  return { id, name, model: suggestions[0], defaultModel: suggestions[0], suggestedModels: suggestions, configured }
}

function settings(active: ProviderSettings['id'] = 'openai'): LlmSettings {
  return {
    activeProvider: active,
    providers: [
      provider('openai', 'OpenAI', ['gpt-6-sol', 'gpt-6.1-sol', 'gpt-5.6'], true),
      provider('anthropic', 'Claude', ['claude-sonnet-5-5', 'claude-opus-5-5', 'claude-haiku-5-5']),
      provider('deepseek', 'DeepSeek', ['deepseek-flash', 'deepseek-v4-pro']),
    ],
  }
}

// What the backend would return for an update, keeping configured flags from `base`.
function savedFrom(update: LlmSettingsUpdate, base = settings()): LlmSettings {
  return {
    activeProvider: update.activeProvider,
    providers: base.providers.map((item) => ({ ...item, model: update.models[item.id] })),
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function Harness() {
  return <ProviderSettingsPanel llm={useLlmSettings()} />
}

async function renderReady(initial = settings()) {
  vi.mocked(api.loadLlmSettings).mockResolvedValue(initial)
  render(<Harness />)
  await screen.findByRole('group', { name: 'Provider used for chat' })
}

const card = (name: string) => screen.getByRole('region', { name: new RegExp(`^${name}`) })

beforeEach(() => {
  vi.mocked(api.loadLlmSettings).mockReset()
  vi.mocked(api.saveLlmSettings).mockReset()
  vi.mocked(api.saveProviderKey).mockReset()
})
afterEach(cleanup)

describe('loading', () => {
  it('shows each provider, the active one, and which have no key', async () => {
    await renderReady()
    const chooser = screen.getByRole('group', { name: 'Provider used for chat' })
    expect((within(chooser).getByRole('radio', { name: /OpenAI/ }) as HTMLInputElement).checked).toBe(true)
    expect((within(chooser).getByRole('radio', { name: /Claude/ }) as HTMLInputElement).checked).toBe(false)
    expect(within(chooser).getAllByText('No key')).toHaveLength(2)
    expect(within(card('OpenAI')).getByText('Used for chat')).toBeTruthy()
    expect(within(card('Claude')).getByText(/No key saved/)).toBeTruthy()
  })

  it('shows a load failure with a retry instead of empty settings', async () => {
    vi.mocked(api.loadLlmSettings)
      .mockRejectedValueOnce(new api.ApiError('Failed to read file: LLM settings file is invalid', 500))
      .mockResolvedValueOnce(settings())
    render(<Harness />)

    expect((await screen.findByRole('alert')).textContent).toContain('LLM settings file is invalid')
    expect(screen.queryByRole('radio')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('group', { name: 'Provider used for chat' })).toBeTruthy()
  })
})

describe('keys', () => {
  it('saves one provider key, clears the field, and never shows the key', async () => {
    await renderReady()
    vi.mocked(api.saveProviderKey).mockResolvedValue({ provider: 'anthropic', configured: true })
    const input = within(card('Claude')).getByLabelText('Claude API key')
    expect(input.getAttribute('type')).toBe('password')

    fireEvent.change(input, { target: { value: `  ${KEY}  ` } })
    await act(async () => { fireEvent.submit(input.closest('form')!) })

    expect(api.saveProviderKey).toHaveBeenCalledWith('anthropic', KEY)
    expect((input as HTMLInputElement).value).toBe('')
    expect(within(card('Claude')).getByText(/A key is saved/)).toBeTruthy()
    expect(within(card('Claude')).getByRole('status').textContent).toContain('Claude key saved')
    expect(document.body.textContent).not.toContain(KEY)
    expect(api.saveLlmSettings).not.toHaveBeenCalled()
  })

  it('keeps the typed key and shows the error when saving fails', async () => {
    await renderReady()
    vi.mocked(api.saveProviderKey).mockRejectedValue(
      new api.ApiError('API key must be at most 512 visible ASCII characters with no spaces', 400))
    const input = within(card('DeepSeek')).getByLabelText('DeepSeek API key')

    fireEvent.change(input, { target: { value: 'bad key' } })
    await act(async () => { fireEvent.submit(input.closest('form')!) })

    expect((input as HTMLInputElement).value).toBe('bad key')
    expect(input.getAttribute('aria-invalid')).toBe('true')
    expect(within(card('DeepSeek')).getByRole('alert').textContent).toContain('visible ASCII')
    expect(within(card('DeepSeek')).getByText(/No key saved/)).toBeTruthy()
  })

  it('asks for a key instead of submitting a blank one', async () => {
    await renderReady()
    const input = within(card('Claude')).getByLabelText('Claude API key')
    await act(async () => { fireEvent.submit(input.closest('form')!) })
    expect(within(card('Claude')).getByRole('alert').textContent).toContain('Enter an API key')
    expect(api.saveProviderKey).not.toHaveBeenCalled()
  })
})

describe('models', () => {
  it('saves only this provider model, ignoring unsaved drafts elsewhere', async () => {
    await renderReady()
    vi.mocked(api.saveLlmSettings).mockImplementation(async (update) => savedFrom(update))
    // An unsaved DeepSeek draft must not be sent with the Claude save.
    fireEvent.change(within(card('DeepSeek')).getByLabelText('DeepSeek model'), { target: { value: 'deepseek-v4-pro' } })
    const claude = within(card('Claude')).getByLabelText('Claude model')
    fireEvent.change(claude, { target: { value: 'claude-opus-5-5' } })

    await act(async () => { fireEvent.submit(claude.closest('form')!) })

    expect(api.saveLlmSettings).toHaveBeenCalledWith({
      activeProvider: 'openai',
      models: { openai: 'gpt-6-sol', anthropic: 'claude-opus-5-5', deepseek: 'deepseek-flash' },
    })
    expect(within(card('Claude')).getByText('Saved model: claude-opus-5-5')).toBeTruthy()
    expect((within(card('DeepSeek')).getByLabelText('DeepSeek model') as HTMLInputElement).value).toBe('deepseek-v4-pro')
    expect(within(card('DeepSeek')).getByText('Saved model: deepseek-flash')).toBeTruthy()
  })

  it('accepts a valid custom model ID and rejects an invalid one before saving', async () => {
    await renderReady()
    vi.mocked(api.saveLlmSettings).mockImplementation(async (update) => savedFrom(update))
    fireEvent.change(within(card('OpenAI')).getByLabelText('OpenAI model'), { target: { value: '__custom__' } })
    const custom = within(card('OpenAI')).getByLabelText('Custom model ID')

    fireEvent.change(custom, { target: { value: 'bad model<script>' } })
    await act(async () => { fireEvent.submit(custom.closest('form')!) })
    expect(within(card('OpenAI')).getByRole('alert').textContent).toContain('Use 1–100 letters')
    expect(custom.getAttribute('aria-invalid')).toBe('true')
    expect(api.saveLlmSettings).not.toHaveBeenCalled()

    fireEvent.change(custom, { target: { value: 'ft:gpt-6:org:custom-1' } })
    await act(async () => { fireEvent.submit(custom.closest('form')!) })
    expect(vi.mocked(api.saveLlmSettings).mock.calls[0][0].models.openai).toBe('ft:gpt-6:org:custom-1')
    // A saved model outside the suggestions shows as a custom ID.
    expect((within(card('OpenAI')).getByLabelText('OpenAI model') as HTMLInputElement).value).toBe('__custom__')
    expect((within(card('OpenAI')).getByLabelText('Custom model ID') as HTMLInputElement).value).toBe('ft:gpt-6:org:custom-1')
  })

  it('runs overlapping saves in order, each built on the previous result', async () => {
    await renderReady()
    const first = deferred<LlmSettings>()
    vi.mocked(api.saveLlmSettings)
      .mockImplementationOnce(() => first.promise)
      .mockImplementationOnce(async (update) => savedFrom(update))
    const claude = within(card('Claude')).getByLabelText('Claude model')
    const deepseek = within(card('DeepSeek')).getByLabelText('DeepSeek model')
    await act(async () => {
      fireEvent.change(claude, { target: { value: 'claude-haiku-5-5' } })
      fireEvent.submit(claude.closest('form')!)
      fireEvent.change(deepseek, { target: { value: 'deepseek-v4-pro' } })
      fireEvent.submit(deepseek.closest('form')!)
    })
    // The second save waits for the first instead of racing it.
    expect(api.saveLlmSettings).toHaveBeenCalledTimes(1)

    await act(async () => {
      first.resolve(savedFrom(vi.mocked(api.saveLlmSettings).mock.calls[0][0]))
    })

    expect(api.saveLlmSettings).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.saveLlmSettings).mock.calls[1][0].models).toEqual({
      openai: 'gpt-6-sol', anthropic: 'claude-haiku-5-5', deepseek: 'deepseek-v4-pro',
    })
    expect(within(card('Claude')).getByText('Saved model: claude-haiku-5-5')).toBeTruthy()
    expect(within(card('DeepSeek')).getByText('Saved model: deepseek-v4-pro')).toBeTruthy()
  })

  it('keeps the draft and shows the error when the save fails', async () => {
    await renderReady()
    vi.mocked(api.saveLlmSettings).mockRejectedValue(new api.ApiError('Failed to write file', 500))
    const claude = within(card('Claude')).getByLabelText('Claude model')
    fireEvent.change(claude, { target: { value: 'claude-opus-5-5' } })

    await act(async () => { fireEvent.submit(claude.closest('form')!) })

    expect((claude as HTMLInputElement).value).toBe('claude-opus-5-5')
    expect(within(card('Claude')).getByRole('alert').textContent).toContain('Failed to write file')
    expect(within(card('Claude')).getByText('Saved model: claude-sonnet-5-5')).toBeTruthy()
  })
})

describe('active provider', () => {
  it('switches the provider used for chat without changing any model', async () => {
    await renderReady()
    vi.mocked(api.saveLlmSettings).mockImplementation(async (update) => savedFrom(update))

    await act(async () => { fireEvent.click(screen.getByRole('radio', { name: /Claude/ })) })

    expect(api.saveLlmSettings).toHaveBeenCalledWith({
      activeProvider: 'anthropic',
      models: { openai: 'gpt-6-sol', anthropic: 'claude-sonnet-5-5', deepseek: 'deepseek-flash' },
    })
    expect((screen.getByRole('radio', { name: /Claude/ }) as HTMLInputElement).checked).toBe(true)
    expect(within(card('Claude')).getByText('Used for chat')).toBeTruthy()
    expect(screen.getByText('Claude is now used for chat')).toBeTruthy()
  })

  it('returns to the saved provider when switching fails', async () => {
    await renderReady()
    const save = deferred<LlmSettings>()
    vi.mocked(api.saveLlmSettings).mockReturnValue(save.promise)

    fireEvent.click(screen.getByRole('radio', { name: /DeepSeek/ }))
    // While saving, the choice shows and the radios are locked against a second change.
    expect((screen.getByRole('radio', { name: /DeepSeek/ }) as HTMLInputElement).checked).toBe(true)
    expect((screen.getByRole('radio', { name: /Claude/ }) as HTMLInputElement).disabled).toBe(true)

    await act(async () => { save.reject(new api.ApiError('Failed to write file', 500)) })

    expect((screen.getByRole('radio', { name: /OpenAI/ }) as HTMLInputElement).checked).toBe(true)
    expect(screen.getByRole('group', { name: 'Provider used for chat' }).textContent).toContain('Failed to write file')
  })
})
