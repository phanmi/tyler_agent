import { useEffect, useState } from 'react'
import { loadUserInfo, saveUserInfo } from '../api'
import type { Gender, UserInfo } from '../types'

// Type aliases for updating each profile group with Partial.
type UserPart = UserInfo['User']
type OtherPart = UserInfo['Other']

// Empty profile: blank strings and a null age, matching the backend JSON structure.
function emptyUserInfo(): UserInfo {
  return {
    User: { Name: '', Gender: '', Age: null, JobType: '' },
    Other: { Info: '', expectationFromLLM: '' },
  }
}

// Gender options; an empty string means no selection.
const GENDER_OPTIONS: { value: Gender; label: string }[] = [
  { value: '', label: 'Not selected' },
  { value: 'Male', label: 'Male' },
  { value: 'Female', label: 'Female' },
  { value: 'Other', label: 'Other' },
]

// Save state controls button labels and status messages.
type SaveStatus = 'idle' | 'saving' | 'saved' | 'error'

// Edit the profile, save it locally, and restore it on startup.
// This form owns its profile state
// because the data is used only here and is independent of chat messages.
// App does not need to manage the form's fields.
export default function UserInfoForm() {
  const [form, setForm] = useState<UserInfo>(emptyUserInfo)
  const [status, setStatus] = useState<SaveStatus>('idle')
  const [errorMsg, setErrorMsg] = useState('')

  // Load saved profile information once on mount.
  // Ignore results after unmount; React Strict Mode may run the effect twice.
  useEffect(() => {
    let cancelled = false
    loadUserInfo()
      .then((data) => {
        if (!cancelled) setForm(data)
      })
      .catch(() => {
        // Leave the form empty if the backend is unavailable.
      })
    return () => {
      cancelled = true
    }
  }, [])

  // Merge changes into the basic-information group.
  const updateUserField = (patch: Partial<UserPart>) => {
    setForm((prev) => ({ ...prev, User: { ...prev.User, ...patch } }))
    setStatus('idle') // Clear the previous save result when editing starts.
  }

  // Merge changes into the additional-information group.
  const updateOtherField = (patch: Partial<OtherPart>) => {
    setForm((prev) => ({ ...prev, Other: { ...prev.Other, ...patch } }))
    setStatus('idle')
  }

  // Validate integer age locally before saving through the API.
  const handleSave = async () => {
    const age = form.User.Age
    // Number.isInteger rejects fractions and non-numbers; null means unspecified.
    if (age !== null && !Number.isInteger(age)) {
      setStatus('error')
      setErrorMsg('Age must be an integer or left blank')
      return
    }

    setStatus('saving')
    setErrorMsg('')
    try {
      const saved = await saveUserInfo(form)
      setForm(saved) // Display the normalized values returned by the backend.
      setStatus('saved')
    } catch (err) {
      setStatus('error')
      setErrorMsg(err instanceof Error ? err.message : 'Failed to save')
    }
  }

  return (
    <aside className="userinfo-card">
      <h2>User profile</h2>
      <p className="userinfo-subtitle">
        Save your profile locally to restore it the next time you open Tyler.
      </p>

      <fieldset>
        <legend>Basic information</legend>
        <label>
          Name
          <input
            value={form.User.Name}
            onChange={(e) => updateUserField({ Name: e.target.value })}
            placeholder="Optional"
          />
        </label>
        <label>
          Gender
          <select
            value={form.User.Gender}
            onChange={(e) => updateUserField({ Gender: e.target.value as Gender })}
          >
            {GENDER_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Age
          <input
            type="number"
            step={1}
            value={form.User.Age ?? ''}
            onChange={(e) =>
              updateUserField({ Age: e.target.value === '' ? null : Number(e.target.value) })
            }
            placeholder="Whole number (optional)"
          />
        </label>
        <label>
          Occupation
          <input
            value={form.User.JobType}
            onChange={(e) => updateUserField({ JobType: e.target.value })}
            placeholder="Optional"
          />
        </label>
      </fieldset>

      <fieldset>
        <legend>Additional information</legend>
        <label>
          Notes
          <textarea
            value={form.Other.Info}
            onChange={(e) => updateOtherField({ Info: e.target.value })}
            rows={2}
            placeholder="Optional"
          />
        </label>
        <label>
          What you expect from Tyler
          <textarea
            value={form.Other.expectationFromLLM}
            onChange={(e) => updateOtherField({ expectationFromLLM: e.target.value })}
            rows={2}
            placeholder="Optional"
          />
        </label>
      </fieldset>

      <button type="button" onClick={handleSave} disabled={status === 'saving'}>
        {status === 'saving' ? 'Saving...' : 'Save'}
      </button>

      {status === 'saved' && <p className="userinfo-msg userinfo-msg--ok">Saved ✓</p>}
      {status === 'error' && <p className="userinfo-msg userinfo-msg--err">{errorMsg}</p>}
    </aside>
  )
}