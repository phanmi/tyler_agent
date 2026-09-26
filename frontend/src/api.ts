import type { FoodEntry, Message, UserInfo } from './types'

// In production, Electron loads dist with loadFile and the page uses a file:// origin.
// Relative /api paths would resolve to file:///api and miss the backend.
// Use an absolute backend URL in production and an empty base URL in development
// to route development requests through the same-origin Vite proxy.
const API_BASE = import.meta.env.PROD ? 'http://127.0.0.1:8080' : ''

// Request body for POST /api/agent/chat.
interface ChatRequest {
  message: string
}

// The non-streaming response contains a single reply field.
interface ChatResponse {
  reply?: string
}

// Call the backend chat API.
// Keep fetch, JSON serialization, and error handling in one place
// so components can send and receive messages without handling HTTP details.
// This keeps network effects separate from UI rendering.
export async function sendMessage(message: string): Promise<Message> {
  const res = await fetch(`${API_BASE}/api/agent/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message } satisfies ChatRequest),
  })

  // For non-2xx responses, try to read a useful error message from the body.
  if (!res.ok) {
    let detail = ''
    try {
      const err = (await res.json()) as { error?: string; message?: string }
      detail = err.error ?? err.message ?? ''
    } catch {
      // Ignore invalid JSON and use the fallback message below.
    }
    throw new Error(detail || `Request failed (HTTP ${res.status})`)
  }

  const data = (await res.json()) as ChatResponse

  // The backend does not return message IDs; combine a timestamp and random value
  // for the assistant message's React key until persistent IDs are available.
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role: 'assistant',
    content: data.reply ?? '(Empty reply)',
  }
}

// ===== User profile =====

// Load the profile: GET /api/userinfo.
// Missing or invalid files produce an empty profile with HTTP 200.
export async function loadUserInfo(): Promise<UserInfo> {
  const res = await fetch(`${API_BASE}/api/userinfo`)
  if (!res.ok) {
    throw new Error(`Failed to load profile (HTTP ${res.status})`)
  }
  return (await res.json()) as UserInfo
}

// Save the profile: POST /api/userinfo.
// The backend validates gender and integer age, saves the profile, and returns normalized values.
export async function saveUserInfo(data: UserInfo): Promise<UserInfo> {
  const res = await fetch(`${API_BASE}/api/userinfo`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })

  // As in sendMessage, try to read an error message from non-2xx responses.
  if (!res.ok) {
    let detail = ''
    try {
      const err = (await res.json()) as { error?: string; message?: string }
      detail = err.error ?? err.message ?? ''
    } catch {
      // Ignore invalid JSON and use the fallback message below.
    }
    throw new Error(detail || `Failed to save (HTTP ${res.status})`)
  }

  return (await res.json()) as UserInfo
}

// ===== OpenAI API Key =====

// API key status exposes only whether a key is configured, never the key itself.
export interface ApiKeyStatus {
  configured: boolean
}

// Check key status: GET /api/apikey/status.
export async function loadApiKeyStatus(): Promise<ApiKeyStatus> {
  const res = await fetch(`${API_BASE}/api/apikey/status`)
  if (!res.ok) {
    throw new Error(`Failed to load API key status (HTTP ${res.status})`)
  }
  return (await res.json()) as ApiKeyStatus
}

// Save the API key: POST /api/apikey. The backend trims it, saves it, and returns its status.
export async function saveApiKey(apiKey: string): Promise<ApiKeyStatus> {
  const res = await fetch(`${API_BASE}/api/apikey`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey }),
  })

  if (!res.ok) {
    let detail = ''
    try {
      const err = (await res.json()) as { error?: string; message?: string }
      detail = err.error ?? err.message ?? ''
    } catch {
      // Ignore invalid JSON and use the fallback message below.
    }
    throw new Error(detail || `Failed to save (HTTP ${res.status})`)
  }

  return (await res.json()) as ApiKeyStatus
}

// ===== Chat history =====

// Saved history contains role and content; the frontend generates IDs for rendering.
interface HistoryEntry {
  role: 'user' | 'assistant'
  content: string
}

// Load saved chat history: GET /api/agent/history.
// Missing or invalid history files produce an empty array with HTTP 200.
export async function loadChatHistory(): Promise<Message[]> {
  const res = await fetch(`${API_BASE}/api/agent/history`)
  if (!res.ok) {
    throw new Error(`Failed to load chat history (HTTP ${res.status})`)
  }
  const data = (await res.json()) as HistoryEntry[]
  // Generate local message IDs, using the index to keep each restored batch unique.
  return data.map((entry, index) => ({
    id: `history-${Date.now()}-${index}`,
    role: entry.role,
    content: entry.content,
  }))
}

// Clear saved chat history: DELETE /api/agent/history.
export async function clearChatHistory(): Promise<void> {
  const res = await fetch(`${API_BASE}/api/agent/history`, { method: 'DELETE' })
  if (!res.ok) {
    throw new Error(`Failed to clear chat history (HTTP ${res.status})`)
  }
}

// ===== Food records =====

// Load a day's food records with IDs: GET /api/food?date=YYYY-MM-DD.
// The backend returns an empty array with HTTP 200 when no records exist.
export async function loadFoodByDate(date: string): Promise<FoodEntry[]> {
  const res = await fetch(`${API_BASE}/api/food?date=${encodeURIComponent(date)}`)
  if (!res.ok) {
    let detail = ''
    try {
      const err = (await res.json()) as { error?: string; message?: string }
      detail = err.error ?? err.message ?? ''
    } catch {
      // Ignore invalid JSON and use the fallback message below.
    }
    throw new Error(detail || `Failed to load food records (HTTP ${res.status})`)
  }
  return (await res.json()) as FoodEntry[]
}

// Delete one food record: DELETE /api/food/{id}.
export async function deleteFood(id: number): Promise<boolean> {
  const res = await fetch(`${API_BASE}/api/food/${id}`, { method: 'DELETE' })
  if (!res.ok) {
    let detail = ''
    try {
      const err = (await res.json()) as { error?: string; message?: string }
      detail = err.error ?? err.message ?? ''
    } catch {
      // Ignore invalid JSON and use the fallback message below.
    }
    throw new Error(detail || `Failed to delete (HTTP ${res.status})`)
  }
  return (await res.json()) as boolean
}

// Delete all food records for a date: DELETE /api/food/date/YYYY-MM-DD.
export async function deleteFoodByDate(date: string): Promise<boolean> {
  const res = await fetch(`${API_BASE}/api/food/date/${encodeURIComponent(date)}`, { method: 'DELETE' })
  if (!res.ok) {
    let detail = ''
    try {
      const err = (await res.json()) as { error?: string; message?: string }
      detail = err.error ?? err.message ?? ''
    } catch {
      // Ignore invalid JSON and use the fallback message below.
    }
    throw new Error(detail || `Failed to delete (HTTP ${res.status})`)
  }
  return (await res.json()) as boolean
}
