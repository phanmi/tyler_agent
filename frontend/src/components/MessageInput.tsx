import { useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'

interface MessageInputProps {
  // Pass submitted text to App; the parent decides how to handle it.
  onSend: (text: string) => void
  // Disable the composer during requests to prevent duplicate submissions.
  disabled: boolean
}

// Keep the draft text in local component state.
// Its lifecycle is typing, sending, and clearing,
// independent of the conversation history owned by App.
// The composer owns the data used only by its input.
export default function MessageInput({ onSend, disabled }: MessageInputProps) {
  const [value, setValue] = useState('')

  // Submit nonblank text and clear the draft.
  const submit = () => {
    const text = value.trim()
    if (!text) return
    onSend(text)
    setValue('')
  }

  // Ctrl/Command + Enter sends; Shift + Enter retains the textarea's default newline behavior.
  const handleKeyDown = (e: ReactKeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      submit()
    }
  }

  return (
    <form
      className="input-form"
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <textarea
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="Write a message... (Ctrl + Enter to send)"
        disabled={disabled}
        rows={4}
      />
      <button type="submit" disabled={disabled || !value.trim()}>
        {disabled ? 'Thinking...' : 'Send'}
      </button>
    </form>
  )
}
