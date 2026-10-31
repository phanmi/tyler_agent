import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { MODEL_ID_PATTERN } from '../../api'
import type { ProviderSettings } from '../../api'

// The select value for "type my own model ID"; never a valid model ID itself.
const CUSTOM = '__custom__'

interface ModelDraft {
  choice: string
  custom: string
}

interface ModelPickerProps {
  provider: ProviderSettings
  onSave: (model: string) => Promise<void>
}

function draftFor(provider: ProviderSettings): ModelDraft {
  return provider.suggestedModels.includes(provider.model)
    ? { choice: provider.model, custom: '' }
    : { choice: CUSTOM, custom: provider.model }
}

function modelOf(draft: ModelDraft): string {
  return draft.choice === CUSTOM ? draft.custom.trim() : draft.choice
}

// One provider's model: a dropdown of suggestions plus a custom model ID.
export default function ModelPicker({ provider, onSave }: ModelPickerProps) {
  // null follows the saved model; an edit becomes a draft until it is saved.
  const [draft, setDraft] = useState<ModelDraft | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const selectId = useId()
  const customId = useId()
  const hintId = useId()
  const errorId = useId()

  const shown = draft ?? draftFor(provider)
  const model = modelOf(shown)
  const invalid = shown.choice === CUSTOM && !MODEL_ID_PATTERN.test(model)
  const unchanged = model === provider.model

  const edit = (next: ModelDraft) => {
    setDraft(next)
    setSaved(false)
    setError('')
  }

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (pending || unchanged) return
    if (invalid) {
      setError('Use 1–100 letters, digits, or . _ : / -')
      return
    }
    const submitted = shown
    setPending(true)
    setError('')
    try {
      await onSave(model)
      // Follow the saved model again unless the user changed the draft while saving.
      setDraft((current) => (current === submitted ? null : current))
      setSaved(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save the model')
    } finally {
      setPending(false)
    }
  }

  return (
    <form className="llm-form" onSubmit={submit} noValidate>
      <label htmlFor={selectId}>{provider.name} model</label>
      <div className="llm-form__row">
        <select
          id={selectId}
          value={shown.choice}
          onChange={(event) => edit({ ...shown, choice: event.target.value })}
        >
          {provider.suggestedModels.map((suggestion) => (
            <option key={suggestion} value={suggestion}>
              {suggestion}
              {suggestion === provider.defaultModel ? ' (default)' : ''}
            </option>
          ))}
          <option value={CUSTOM}>Custom model ID…</option>
        </select>
        <button type="submit" disabled={pending || unchanged}>
          {pending ? 'Saving…' : 'Save model'}
        </button>
      </div>
      {shown.choice === CUSTOM && (
        <>
          <label htmlFor={customId}>Custom model ID</label>
          <input
            id={customId}
            type="text"
            value={shown.custom}
            onChange={(event) => edit({ ...shown, custom: event.target.value })}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={error ? true : undefined}
            aria-describedby={`${hintId}${error ? ` ${errorId}` : ''}`}
          />
          <p id={hintId} className="llm-msg">
            The exact model ID from {provider.name}: up to 100 letters, digits, or . _ : / -
          </p>
        </>
      )}
      <p className="llm-msg">Saved model: {provider.model}</p>
      {saved && <p className="llm-msg llm-msg--ok" role="status">{provider.name} model saved</p>}
      {error && <p id={errorId} className="llm-msg llm-msg--err" role="alert">{error}</p>}
    </form>
  )
}
