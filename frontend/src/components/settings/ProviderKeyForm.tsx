import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import type { ProviderSettings } from '../../api'

interface ProviderKeyFormProps {
  provider: ProviderSettings
  onSave: (apiKey: string) => Promise<void>
}

// One provider's key: a password field and its saved status. The saved key is never shown;
// the input is cleared after a successful save so the key does not stay in the page.
export default function ProviderKeyForm({ provider, onSave }: ProviderKeyFormProps) {
  const [value, setValue] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState(false)
  const inputId = useId()
  const statusId = useId()
  const errorId = useId()

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (pending) return
    const submitted = value
    if (!submitted.trim()) {
      setError('Enter an API key')
      setSaved(false)
      return
    }
    setPending(true)
    setError('')
    setSaved(false)
    try {
      await onSave(submitted.trim())
      // Clear only the submitted text; keep anything typed while the save was pending.
      setValue((current) => (current === submitted ? '' : current))
      setSaved(true)
    } catch (err) {
      // Keep the input so the user can correct it; the message never contains the key.
      setError(err instanceof Error ? err.message : 'Failed to save the API key')
    } finally {
      setPending(false)
    }
  }

  return (
    <form className="llm-form" onSubmit={submit} noValidate>
      <label htmlFor={inputId}>{provider.name} API key</label>
      <div className="llm-form__row">
        <input
          id={inputId}
          type="password"
          value={value}
          onChange={(event) => {
            setValue(event.target.value)
            setSaved(false)
          }}
          autoComplete="off"
          spellCheck={false}
          aria-invalid={error ? true : undefined}
          aria-describedby={`${statusId}${error ? ` ${errorId}` : ''}`}
        />
        <button type="submit" disabled={pending}>
          {pending ? 'Saving…' : 'Save key'}
        </button>
      </div>
      <p id={statusId} className="llm-msg">
        {provider.configured ? 'A key is saved.' : 'No key saved.'} Saved keys are never shown.
      </p>
      {saved && <p className="llm-msg llm-msg--ok" role="status">{provider.name} key saved</p>}
      {error && <p id={errorId} className="llm-msg llm-msg--err" role="alert">{error}</p>}
    </form>
  )
}
