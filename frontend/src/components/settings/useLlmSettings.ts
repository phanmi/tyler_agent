import { useCallback, useEffect, useRef, useState } from 'react'
import { isInvalidSettingsFile, loadLlmSettings, resetLlmSettings, saveLlmSettings, saveProviderKey } from '../../api'
import type { LlmProviderId, LlmSettings, ProviderSettings } from '../../api'

export type LlmSettingsState =
  | { status: 'loading' }
  | { status: 'error'; message: string; invalidFile: boolean }
  | { status: 'ready'; settings: LlmSettings }

export interface LlmSettingsController {
  state: LlmSettingsState
  // Retry after a failed load.
  reload: () => void
  // Replace an invalid settings file with the defaults; saved keys are kept.
  reset: () => Promise<void>
  // Each save resolves with the backend's confirmed settings or rejects with its error.
  saveActiveProvider: (provider: LlmProviderId) => Promise<void>
  saveModel: (provider: LlmProviderId, model: string) => Promise<void>
  saveKey: (provider: LlmProviderId, apiKey: string) => Promise<void>
}

export function findProvider(settings: LlmSettings, id: LlmProviderId): ProviderSettings {
  // parseLlmSettings guarantees every provider is present.
  return settings.providers.find((provider) => provider.id === id)!
}

function modelsOf(settings: LlmSettings): Record<LlmProviderId, string> {
  return {
    openai: findProvider(settings, 'openai').model,
    anthropic: findProvider(settings, 'anthropic').model,
    deepseek: findProvider(settings, 'deepseek').model,
  }
}

/**
 * Owns the provider settings shared by chat and the Settings page.
 *
 * The backend replaces the whole settings file on every save, so each save is built from
 * the last confirmed settings with only its own change applied: unsaved edits elsewhere are
 * never sent. Settings saves run one at a time, each starting from the previous result, so
 * a slow save cannot overwrite a newer one. A key save that completes while a settings save
 * is in flight keeps its `configured` status when the older settings response arrives.
 */
export function useLlmSettings(): LlmSettingsController {
  const [state, setState] = useState<LlmSettingsState>({ status: 'loading' })
  const [loadRequest, setLoadRequest] = useState(0)
  // The latest confirmed settings, read when a queued save actually starts.
  const confirmed = useRef<LlmSettings | null>(null)
  const settingsQueue = useRef<Promise<unknown>>(Promise.resolve())
  // Counts completed key saves per provider, to detect ones newer than a settings request.
  const keyRevisions = useRef<Record<LlmProviderId, number>>({ openai: 0, anthropic: 0, deepseek: 0 })
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const apply = useCallback((settings: LlmSettings) => {
    confirmed.current = settings
    if (mounted.current) setState({ status: 'ready', settings })
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    setState({ status: 'loading' })
    loadLlmSettings(controller.signal)
      .then((settings) => {
        if (!controller.signal.aborted) apply(settings)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({
          status: 'error',
          message: error instanceof Error ? error.message : 'Failed to load provider settings',
          invalidFile: isInvalidSettingsFile(error),
        })
      })
    return () => controller.abort()
  }, [loadRequest, apply])

  const reload = useCallback(() => setLoadRequest((request) => request + 1), [])

  // Settings writes run one at a time; a failed one must not block later ones.
  const enqueue = useCallback((task: () => Promise<void>) => {
    const run = settingsQueue.current.then(task)
    settingsQueue.current = run.catch(() => undefined)
    return run
  }, [])

  const reset = useCallback(
    () => enqueue(async () => apply(await resetLlmSettings())),
    [enqueue, apply],
  )

  const queueSettingsSave = useCallback(
    (change: (current: LlmSettings) => { activeProvider: LlmProviderId; models: Record<LlmProviderId, string> }) =>
      enqueue(async () => {
        const current = confirmed.current
        if (!current) throw new Error('Provider settings are not loaded')
        const revisions = { ...keyRevisions.current }
        const saved = await saveLlmSettings(change(current))
        // Keep key statuses confirmed after this request started; its response predates them.
        const latest = confirmed.current ?? saved
        apply({
          ...saved,
          providers: saved.providers.map((provider) =>
            keyRevisions.current[provider.id] === revisions[provider.id]
              ? provider
              : { ...provider, configured: findProvider(latest, provider.id).configured },
          ),
        })
      }),
    [enqueue, apply],
  )

  const saveActiveProvider = useCallback(
    (provider: LlmProviderId) =>
      queueSettingsSave((current) => ({ activeProvider: provider, models: modelsOf(current) })),
    [queueSettingsSave],
  )

  const saveModel = useCallback(
    (provider: LlmProviderId, model: string) =>
      queueSettingsSave((current) => ({
        activeProvider: current.activeProvider,
        models: { ...modelsOf(current), [provider]: model },
      })),
    [queueSettingsSave],
  )

  const saveKey = useCallback(
    async (provider: LlmProviderId, apiKey: string) => {
      const status = await saveProviderKey(provider, apiKey)
      keyRevisions.current[provider] += 1
      const current = confirmed.current
      if (!current) return
      apply({
        ...current,
        providers: current.providers.map((item) =>
          item.id === provider ? { ...item, configured: status.configured } : item,
        ),
      })
    },
    [apply],
  )

  return { state, reload, reset, saveActiveProvider, saveModel, saveKey }
}
