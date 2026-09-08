import type { Message, UserInfo } from './types'

// 与后端约定的请求体：POST /api/agent/chat
interface ChatRequest {
  message: string
}

// 后端返回体：非流式、单轮，只有 reply 一个字段。
interface ChatResponse {
  reply?: string
}

// 封装对后端聊天接口的调用。
// 把网络细节（fetch、JSON 序列化、错误归一化）集中在这里，
// 让组件只关心「发出去一句话、拿回一句回复」，不必关心 HTTP 细节。
// 这也是把「网络副作用」与「UI 渲染」解耦的关键一步。
export async function sendMessage(message: string): Promise<Message> {
  const res = await fetch('/api/agent/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message } satisfies ChatRequest),
  })

  // 后端异常时返回非 2xx 状态码，尝试从响应体里解析出人类可读的错误信息。
  if (!res.ok) {
    let detail = ''
    try {
      const err = (await res.json()) as { error?: string; message?: string }
      detail = err.error ?? err.message ?? ''
    } catch {
      // 响应体不是合法 JSON 时忽略，走下方兜底文案。
    }
    throw new Error(detail || `请求失败（HTTP ${res.status}）`)
  }

  const data = (await res.json()) as ChatResponse

  // 后端不返回消息 id，这里用「时间戳 + 随机数」拼一个本地唯一 id，
  // 作为这条 assistant 消息的 React key。将来接入真实会话持久化时可替换为后端 id。
  return {
    id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    role: 'assistant',
    content: data.reply ?? '（空回复）',
  }
}

// ===== 用户信息 =====

// 读取用户信息：GET /api/userinfo。
// 后端在文件不存在或损坏时也会返回空结构（200），因此正常路径不会 reject。
export async function loadUserInfo(): Promise<UserInfo> {
  const res = await fetch('/api/userinfo')
  if (!res.ok) {
    throw new Error(`读取用户信息失败（HTTP ${res.status}）`)
  }
  return (await res.json()) as UserInfo
}

// 保存用户信息：POST /api/userinfo。
// 后端校验（gender 枚举、age 整数）后写盘，并返回归一化后的结构。
export async function saveUserInfo(data: UserInfo): Promise<UserInfo> {
  const res = await fetch('/api/userinfo', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })

  // 与 sendMessage 一致：非 2xx 时尝试从响应体解析人类可读的错误信息。
  if (!res.ok) {
    let detail = ''
    try {
      const err = (await res.json()) as { error?: string; message?: string }
      detail = err.error ?? err.message ?? ''
    } catch {
      // 响应体不是合法 JSON 时忽略，走下方兜底文案。
    }
    throw new Error(detail || `保存失败（HTTP ${res.status}）`)
  }

  return (await res.json()) as UserInfo
}

// ===== OpenAI API Key =====

// API Key 状态契约：只暴露「是否已配置」，绝不含 key 明文。
export interface ApiKeyStatus {
  configured: boolean
}

// 查询 key 是否已配置：GET /api/apikey/status。
export async function loadApiKeyStatus(): Promise<ApiKeyStatus> {
  const res = await fetch('/api/apikey/status')
  if (!res.ok) {
    throw new Error(`读取 API Key 状态失败（HTTP ${res.status}）`)
  }
  return (await res.json()) as ApiKeyStatus
}

// 保存 API Key：POST /api/apikey。后端 trim 后写进沙盒文件，返回配置状态。
export async function saveApiKey(apiKey: string): Promise<ApiKeyStatus> {
  const res = await fetch('/api/apikey', {
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
      // 响应体不是合法 JSON 时忽略，走下方兜底文案。
    }
    throw new Error(detail || `保存失败（HTTP ${res.status}）`)
  }

  return (await res.json()) as ApiKeyStatus
}
