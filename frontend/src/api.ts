import type { FoodEntry, Message, UserInfo, Workout, WorkoutEntry, WorkoutPlan } from './types'

// In production, Electron loads dist with loadFile and the page uses a file:// origin.
// Relative /api paths would resolve to file:///api and miss the backend.
// Electron supplies its backend's assigned port before React mounts.
// Browser development uses relative URLs through Vite's same-origin proxy,
// which adds the access token itself, so the page never holds it.
let apiBase = ''
let accessToken = ''

export const ACCESS_TOKEN_HEADER = 'X-Tyler-Token'

export function configureApiBaseUrl(baseUrl: string, token: string): void {
  const url = new URL(baseUrl)
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port ||
      url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('Invalid backend address')
  }
  if (!/^[0-9a-f]{64}$/.test(token)) {
    throw new Error('Invalid backend access token')
  }
  apiBase = url.origin
  accessToken = token
}

// Every backend request goes through here so none can miss the access token.
function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  if (accessToken) headers.set(ACCESS_TOKEN_HEADER, accessToken)
  return fetch(`${apiBase}${path}`, { ...init, headers })
}

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
  const res = await apiFetch(`/api/agent/chat`, {
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
  const res = await apiFetch(`/api/userinfo`)
  if (!res.ok) {
    throw new Error(`Failed to load profile (HTTP ${res.status})`)
  }
  return (await res.json()) as UserInfo
}

// Save the profile: POST /api/userinfo.
// The backend validates gender and integer age, saves the profile, and returns normalized values.
export async function saveUserInfo(data: UserInfo): Promise<UserInfo> {
  const res = await apiFetch(`/api/userinfo`, {
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

// ===== Model providers and API keys =====

export type LlmProviderId = 'openai' | 'anthropic' | 'deepseek'

const PROVIDER_IDS: readonly LlmProviderId[] = ['openai', 'anthropic', 'deepseek']

// One provider's settings. Keys are never returned; `configured` says whether one is saved.
export interface ProviderSettings {
  id: LlmProviderId
  name: string
  model: string
  defaultModel: string
  suggestedModels: string[]
  configured: boolean
}

export interface LlmSettings {
  activeProvider: LlmProviderId
  // Always all three providers, in backend order.
  providers: ProviderSettings[]
}

// The backend replaces the whole settings file, so every save sends every model.
export interface LlmSettingsUpdate {
  activeProvider: LlmProviderId
  models: Record<LlmProviderId, string>
}

export interface KeyStatus {
  provider: LlmProviderId
  configured: boolean
}

// Same rule the backend enforces; checked here only for immediate feedback.
export const MODEL_ID_PATTERN = /^[A-Za-z0-9._:/-]{1,100}$/

// A failed backend call with its HTTP status kept for callers that need it.
export class ApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

// Use the backend's error text when present. It never contains a key: the backend's
// messages are fixed text and do not echo request values.
async function apiError(res: Response, fallback: string): Promise<ApiError> {
  let detail = ''
  try {
    const body = (await res.json()) as { error?: unknown }
    if (typeof body.error === 'string') detail = body.error
  } catch {
    // Ignore invalid JSON and use the fallback message.
  }
  return new ApiError(detail || `${fallback} (HTTP ${res.status})`, res.status)
}

function isProviderId(value: unknown): value is LlmProviderId {
  return typeof value === 'string' && (PROVIDER_IDS as readonly string[]).includes(value)
}

function parseProvider(value: unknown): ProviderSettings {
  const item = value as Partial<Record<keyof ProviderSettings, unknown>> | null
  if (
    !item || !isProviderId(item.id) || typeof item.name !== 'string' ||
    typeof item.model !== 'string' || typeof item.defaultModel !== 'string' ||
    !Array.isArray(item.suggestedModels) ||
    !item.suggestedModels.every((model) => typeof model === 'string') ||
    typeof item.configured !== 'boolean'
  ) {
    throw new Error('Invalid provider settings from the backend')
  }
  return {
    id: item.id, name: item.name, model: item.model, defaultModel: item.defaultModel,
    suggestedModels: [...item.suggestedModels], configured: item.configured,
  }
}

// Type assertions do not validate JSON; check the shape before the UI relies on it.
function parseLlmSettings(value: unknown): LlmSettings {
  const body = value as { activeProvider?: unknown; providers?: unknown } | null
  if (!body || !isProviderId(body.activeProvider) || !Array.isArray(body.providers)) {
    throw new Error('Invalid provider settings from the backend')
  }
  const providers = body.providers.map(parseProvider)
  const ids = providers.map((provider) => provider.id)
  if (ids.length !== PROVIDER_IDS.length || !PROVIDER_IDS.every((id) => ids.includes(id))) {
    throw new Error('Invalid provider settings from the backend')
  }
  return { activeProvider: body.activeProvider, providers }
}

// Load provider settings: GET /api/llm/settings.
export async function loadLlmSettings(signal?: AbortSignal): Promise<LlmSettings> {
  const res = await apiFetch(`/api/llm/settings`, { signal })
  if (!res.ok) throw await apiError(res, 'Failed to load provider settings')
  return parseLlmSettings(await res.json())
}

// Replace provider settings: PUT /api/llm/settings. Returns what the backend saved.
export async function saveLlmSettings(update: LlmSettingsUpdate): Promise<LlmSettings> {
  const res = await apiFetch(`/api/llm/settings`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(update),
  })
  if (!res.ok) throw await apiError(res, 'Failed to save provider settings')
  return parseLlmSettings(await res.json())
}

// Save one provider's key: PUT /api/llm/keys/{provider}. The response holds only the status.
export async function saveProviderKey(provider: LlmProviderId, apiKey: string): Promise<KeyStatus> {
  const res = await apiFetch(`/api/llm/keys/${provider}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey }),
  })
  if (!res.ok) throw await apiError(res, 'Failed to save the API key')
  const body = (await res.json()) as { provider?: unknown; configured?: unknown } | null
  if (!body || body.provider !== provider || typeof body.configured !== 'boolean') {
    throw new Error('Invalid key status from the backend')
  }
  return { provider, configured: body.configured }
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
  const res = await apiFetch(`/api/agent/history`)
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
  const res = await apiFetch(`/api/agent/history`, { method: 'DELETE' })
  if (!res.ok) {
    throw new Error(`Failed to clear chat history (HTTP ${res.status})`)
  }
}

// ===== Food records =====

// Load a day's food records with IDs: GET /api/food?date=YYYY-MM-DD.
// The backend returns an empty array with HTTP 200 when no records exist.
export async function loadFoodByDate(date: string): Promise<FoodEntry[]> {
  const res = await apiFetch(`/api/food?date=${encodeURIComponent(date)}`)
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
  const res = await apiFetch(`/api/food/${id}`, { method: 'DELETE' })
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
  const res = await apiFetch(`/api/food/date/${encodeURIComponent(date)}`, { method: 'DELETE' })
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

// ===== Workouts =====

async function workoutResponseError(res: Response, fallback: string): Promise<Error> {
  let detail = ''
  try {
    const body = (await res.json()) as { error?: string; message?: string }
    detail = body.error ?? body.message ?? ''
  } catch {
    // Use the fallback for empty or non-JSON responses.
  }
  return new Error(detail || `${fallback} (HTTP ${res.status})`)
}

export async function loadWorkoutsByDate(date: string): Promise<WorkoutEntry[]> {
  const res = await apiFetch(`/api/workouts?date=${encodeURIComponent(date)}`)
  if (!res.ok) throw await workoutResponseError(res, 'Failed to load workouts')
  return (await res.json()) as WorkoutEntry[]
}

export async function loadWorkoutPlan(startDate: string, goal: string, equipment: string): Promise<WorkoutPlan> {
  const params = new URLSearchParams({ startDate, goal, equipment })
  const res = await apiFetch(`/api/workouts/plan?${params}`)
  if (!res.ok) throw await workoutResponseError(res, 'Failed to load workout plan')
  return (await res.json()) as WorkoutPlan
}

export async function createWorkout(workout: Workout): Promise<WorkoutEntry> {
  const res = await apiFetch(`/api/workouts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(workout),
  })
  if (!res.ok) throw await workoutResponseError(res, 'Failed to save workout')
  return (await res.json()) as WorkoutEntry
}

export async function updateWorkout(id: number, workout: Workout): Promise<WorkoutEntry> {
  const res = await apiFetch(`/api/workouts/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(workout),
  })
  if (!res.ok) throw await workoutResponseError(res, 'Failed to update workout')
  return (await res.json()) as WorkoutEntry
}

export async function deleteWorkout(id: number): Promise<void> {
  const res = await apiFetch(`/api/workouts/${id}`, { method: 'DELETE' })
  if (!res.ok) throw await workoutResponseError(res, 'Failed to delete workout')
}
