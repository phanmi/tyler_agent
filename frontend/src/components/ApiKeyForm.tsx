import { useState } from 'react'
import { saveApiKey } from '../api'

// Save state controls button labels and status messages.
type SaveStatus = 'idle' | 'saving' | 'saved' | 'error'

interface ApiKeyFormProps {
  // Current key status, loaded and owned by App.
  configured: boolean
  // Report status changes to App so it can enable or disable chat.
  onConfiguredChange: (configured: boolean) => void
}

// OpenAI API key form: password input and save button.
// App supplies configuration status; this component handles input and saving.
export default function ApiKeyForm({ configured, onConfiguredChange }: ApiKeyFormProps) {
  const [value, setValue] = useState('')
  const [status, setStatus] = useState<SaveStatus>('idle')
  const [errorMsg, setErrorMsg] = useState('')

  // Validate a nonempty key, save it, then clear the input without displaying the saved key.
  const handleSave = async () => {
    const apiKey = value.trim()
    if (!apiKey) {
      setStatus('error')
      setErrorMsg('Enter your API key')
      return
    }

    setStatus('saving')
    setErrorMsg('')
    try {
      const res = await saveApiKey(apiKey)
      onConfiguredChange(res.configured)
      setValue('') // Clear the input after saving; never display the saved key.
      setStatus('saved')
    } catch (err) {
      setStatus('error')
      setErrorMsg(err instanceof Error ? err.message : 'Failed to save')
    }
  }

  return (
    <aside className="userinfo-card">
      <h2>OpenAI API Key</h2>
      <p className="userinfo-subtitle">
        Your API key is saved locally and used to connect to OpenAI. It is hidden after saving.
      </p>

      <label>
        API Key
        <input
          type="password"
          value={value}
          onChange={(e) => {
            setValue(e.target.value)
            setStatus('idle')
          }}
          placeholder="sk-..."
          autoComplete="off"
        />
      </label>

      <button type="button" onClick={handleSave} disabled={status === 'saving'}>
        {status === 'saving' ? 'Saving...' : 'Save'}
      </button>

      <p className="userinfo-subtitle">
        Status: {configured ? 'Configured ✓' : 'Not configured'}
      </p>

      {status === 'saved' && <p className="userinfo-msg userinfo-msg--ok">Saved ✓</p>}
      {status === 'error' && <p className="userinfo-msg userinfo-msg--err">{errorMsg}</p>}
    </aside>
  )
}
