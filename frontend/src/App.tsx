import { useCallback, useEffect, useRef, useState } from 'react'
import { clearChatHistory, loadApiKeyStatus, loadChatHistory, sendMessage } from './api'
import type { Message } from './types'
import FoodCalendar from './components/FoodCalendar'
import MessageBubble from './components/MessageBubble'
import MessageInput from './components/MessageInput'
import SettingsPage from './components/SettingsPage'
import Sidebar from './components/Sidebar'
import type { View } from './components/Sidebar'
import TitleBar from './components/TitleBar'

// App is the component-tree root and the single source of truth for message state.
// Message state is shared by the list and the composer:
// the list renders the history,
// and the composer appends new messages.
// Their common parent owns the state to keep both views consistent.
export default function App() {
  // messages: append new messages to the conversation history.
  const [messages, setMessages] = useState<Message[]>([])
  // isLoading: disable input and show a thinking indicator while waiting for a reply.
  const [isLoading, setIsLoading] = useState(false)
  // apiKeyConfigured: optimistically allow chat until the backend returns key status.
  // Load the actual status asynchronously after mounting.
  const [apiKeyConfigured, setApiKeyConfigured] = useState(true)
  // isHistoryLoading: disable input while restoring saved messages
  // so setMessages(history) cannot overwrite a newly submitted message.
  const [isHistoryLoading, setIsHistoryLoading] = useState(true)
  // view: switch between chat and settings using local state.
  const [view, setView] = useState<View>('chat')
  // calendarOpen: expand or collapse the food calendar beside the chat.
  const [calendarOpen, setCalendarOpen] = useState(true)

  // Anchor at the end of the message list for automatic scrolling.
  const bottomRef = useRef<HTMLDivElement>(null)

  // Scroll to the bottom when messages or loading state change.
  // Keep this DOM effect in App so each update scrolls the correct container once.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, isLoading])

  // Load key status on mount; allow chat optimistically if the request fails.
  useEffect(() => {
    let cancelled = false
    loadApiKeyStatus()
      .then((data) => {
        if (!cancelled) setApiKeyConfigured(data.configured)
      })
      .catch(() => {
        // Let the user try chatting even if the backend status check fails.
      })
    return () => {
      cancelled = true
    }
  }, [])

  // Restore saved history on mount; a failed restore leaves an empty conversation.
  useEffect(() => {
    let cancelled = false
    loadChatHistory()
      .then((history) => {
        if (!cancelled) setMessages(history)
      })
      .catch(() => {
        // Keep an empty history if the backend is unavailable.
      })
      .finally(() => {
        if (!cancelled) setIsHistoryLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  // handleSend: called when the composer submits a message.
  // Append the user message, set loading, call the backend, and append the reply.
  // Append an assistant error message when the request fails.
  const handleSend = useCallback(
    async (text: string) => {
      const userMessage: Message = {
        id: `user-${Date.now()}`,
        role: 'user',
        content: text,
      }
      setMessages((prev) => [...prev, userMessage])

      // Show a missing-key message before calling the chat API; the backend also validates it.
      if (!apiKeyConfigured) {
        setMessages((prev) => [
          ...prev,
          {
            id: `hint-${Date.now()}`,
            role: 'assistant',
            content: 'OpenAI API key is empty; chat is unavailable',
          },
        ])
        return
      }

      setIsLoading(true)
      try {
        const reply = await sendMessage(text)
        setMessages((prev) => [...prev, reply])
      } catch (err) {
        setMessages((prev) => [
          ...prev,
          {
            id: `error-${Date.now()}`,
            role: 'assistant',
            content: `Error: ${err instanceof Error ? err.message : 'Unknown error'}`,
          },
        ])
      } finally {
        setIsLoading(false)
      }
    },
    [apiKeyConfigured],
  )

  // Clear saved and displayed history; show an assistant error message on failure.
  const handleClear = useCallback(async () => {
    try {
      await clearChatHistory()
      setMessages([])
    } catch (err) {
      setMessages((prev) => [
        ...prev,
        {
          id: `clear-error-${Date.now()}`,
          role: 'assistant',
          content: `Failed to clear conversation: ${err instanceof Error ? err.message : 'Unknown error'}`,
        },
      ])
    }
  }, [])

  return (
    <div className="app-shell">
      {/* Custom title bar with minimize, maximize, and close controls. */}
      <TitleBar />

      <div className="app-body">
        {/* Sidebar navigation: chat and settings. */}
        <Sidebar current={view} onNavigate={setView} />

        <main className="app-main">
          {view === 'chat' ? (
            <div className="chat-view">
              {/* Main chat area. */}
              <section className="chat-pane card">
                <header className="chat-pane__header">
                  <h1 className="chat-pane__title">Tyler</h1>
                  <div className="chat-pane__actions">
                    <button
                      type="button"
                      className={`btn-calendar ${calendarOpen ? 'btn-calendar--active' : ''}`}
                      onClick={() => setCalendarOpen((v) => !v)}
                    >
                      📅 Calendar
                    </button>
                    {messages.length > 0 && (
                      <button
                        type="button"
                        className="btn-clear"
                        onClick={handleClear}
                        disabled={isLoading}
                      >
                        Clear conversation
                      </button>
                    )}
                  </div>
                </header>

                {/* Render messages as bubbles directly here.
                    The list currently needs only mapping and scrolling.
                    Extract a component if grouping, date separators, or virtualization are added. */}
                <div className="messages" role="log" aria-live="polite">
                  {messages.length === 0 && <div className="empty">Replies will appear here.</div>}
                  {messages.map((msg) => (
                    <MessageBubble key={msg.id} message={msg} />
                  ))}
                  {isLoading && <div className="loading-bubble">Thinking...</div>}
                  {/* The anchor stays at the end of the list for the scrolling effect. */}
                  <div ref={bottomRef} />
                </div>

                <MessageInput onSend={handleSend} disabled={isLoading || isHistoryLoading} />
                <p className="hint">Shift + Enter for a new line. Ctrl + Enter to send.</p>
              </section>

              {/* Collapsible food calendar; closing it gives the chat more space. */}
              {calendarOpen && (
                <aside className="calendar-panel">
                  <FoodCalendar />
                </aside>
              )}
            </div>
          ) : (
            <SettingsPage
              apiKeyConfigured={apiKeyConfigured}
              onConfiguredChange={setApiKeyConfigured}
            />
          )}
        </main>
      </div>
    </div>
  )
}
