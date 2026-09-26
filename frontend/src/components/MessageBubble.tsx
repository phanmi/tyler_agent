import type { Message } from '../types'

// Render one message with alignment and colors determined by its role.
// This reusable leaf component handles both user messages and Tyler replies.
// Both use the same message data
// and differ only in alignment and visual styling.
// Keeping bubble rendering here avoids duplicate JSX in the parent.
// Markdown rendering or syntax highlighting can be added in one place.
export default function MessageBubble({ message }: { message: Message }) {
  const isUser = message.role === 'user'

  return (
    <div className={`bubble ${isUser ? 'bubble--user' : 'bubble--assistant'}`}>
      {/* React escapes HTML when rendering message.content as text,
          avoiding the XSS risks of inserting raw HTML. */}
      <div className="bubble__content">{message.content}</div>
    </div>
  )
}
