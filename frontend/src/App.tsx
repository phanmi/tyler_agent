import { useCallback, useEffect, useRef, useState } from 'react'
import { sendMessage } from './api'
import type { Message } from './types'
import MessageBubble from './components/MessageBubble'
import MessageInput from './components/MessageInput'
import UserInfoForm from './components/UserInfoForm'

// App 是整棵组件树的根，也是消息状态的唯一所有者（single source of truth）。
// 为什么状态必须放这里？
//   消息历史会被「消息列表」和「输入框」两边需要——
//   列表负责渲染、输入框负责触发追加，
//   所以必须提升到它们共同的父级 App，谁都不私藏一份。
export default function App() {
  // messages：累积式聊天记录，一条条往下堆，不再像旧页面那样被覆盖。
  const [messages, setMessages] = useState<Message[]>([])
  // isLoading：是否正在等后端回复，用于禁用输入框、显示「思考中」。
  const [isLoading, setIsLoading] = useState(false)

  // 指向消息列表末尾的锚点元素，用于「新消息到达时自动滚动到底部」。
  const bottomRef = useRef<HTMLDivElement>(null)

  // 当消息列表变化（新消息加入 / loading 切换）时，把滚动条拉到底部。
  // 这个 DOM 副作用放在 App（而非某个气泡）里，保证每轮只滚一次、且滚对地方。
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, isLoading])

  // handleSend：输入框提交后的回调。
  // 流程：先把用户的话塞进历史 → 置 loading → 调后端 → 把回复塞进历史。
  // 失败时也塞一条 assistant 消息、内容是错误提示，保证用户总能看到反馈。
  const handleSend = useCallback(async (text: string) => {
    const userMessage: Message = {
      id: `user-${Date.now()}`,
      role: 'user',
      content: text,
    }
    setMessages((prev) => [...prev, userMessage])
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
          content: `出错了：${err instanceof Error ? err.message : '未知错误'}`,
        },
      ])
    } finally {
      setIsLoading(false)
    }
  }, [])

  return (
    <div className="app-layout">
      {/* 用户信息面板：独立于聊天流，自行负责「启动读取 + 保存写回」。 */}
      <UserInfoForm />

      <main className="card">
        <h1>Tyler Agent</h1>
        <p className="subtitle">输入一段话，ChatGPT 会回复你。</p>

        {/* 消息列表：直接在这里 map 成气泡。
            暂未单独抽出 MessageList 组件——当前规模下它只有「map + 滚动」两件小事，
            抽出来反而多一层间接。等列表逻辑变复杂（分组、日期分隔、虚拟滚动）再抽。 */}
        <div className="messages" role="log" aria-live="polite">
          {messages.length === 0 && <div className="empty">回复会显示在这里。</div>}
          {messages.map((msg) => (
            <MessageBubble key={msg.id} message={msg} />
          ))}
          {isLoading && <div className="loading-bubble">思考中……</div>}
          {/* 滚动锚点：永远停留在列表末尾，配合上面的 useEffect 实现自动滚动。 */}
          <div ref={bottomRef} />
        </div>

        <MessageInput onSend={handleSend} disabled={isLoading} />
        <p className="hint">Shift + Enter 换行，Ctrl + Enter 发送。</p>
      </main>
    </div>
  )
}
