import { useState } from 'react'
import type { LlmProviderId, LlmSettings } from '../../api'

interface ActiveProviderFieldProps {
  settings: LlmSettings
  onSave: (provider: LlmProviderId) => Promise<void>
}

// Radio choice of the provider every chat uses. Choosing one saves it immediately.
export default function ActiveProviderField({ settings, onSave }: ActiveProviderFieldProps) {
  // The choice being saved; the radios show it until the backend confirms or rejects it.
  const [pending, setPending] = useState<LlmProviderId | null>(null)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState('')

  const choose = async (provider: LlmProviderId) => {
    if (pending || provider === settings.activeProvider) return
    setPending(provider)
    setError('')
    setSaved('')
    try {
      await onSave(provider)
      const name = settings.providers.find((item) => item.id === provider)?.name ?? provider
      setSaved(`${name} is now used for chat`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to change the provider')
    } finally {
      setPending(null)
    }
  }

  const selected = pending ?? settings.activeProvider

  return (
    <fieldset className="llm-active" aria-busy={pending !== null}>
      <legend>Provider used for chat</legend>
      <div className="llm-active__options">
        {settings.providers.map((provider) => (
          <label key={provider.id} className="llm-active__option">
            <input
              type="radio"
              name="active-provider"
              value={provider.id}
              checked={selected === provider.id}
              disabled={pending !== null}
              onChange={() => void choose(provider.id)}
            />
            <span>{provider.name}</span>
            {!provider.configured && <span className="llm-tag llm-tag--warn">No key</span>}
          </label>
        ))}
      </div>
      {pending && <p className="llm-msg" role="status">Saving…</p>}
      {saved && <p className="llm-msg llm-msg--ok" role="status">{saved}</p>}
      {error && <p className="llm-msg llm-msg--err" role="alert">{error}</p>}
    </fieldset>
  )
}
