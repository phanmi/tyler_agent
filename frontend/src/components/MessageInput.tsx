import { useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'

interface MessageInputProps {
  // 提交回调：把用户输入交给父组件（App）处理，自己不关心消息最终去哪。
  onSend: (text: string) => void
  // 是否禁用：请求进行中置 true，避免重复提交。
  disabled: boolean
}

// 输入区：维护「用户正在打的内容」这块独立本地状态。
// 这块状态的生命周期（打字 → 发送 → 清空）与消息历史完全无关，
// 是输入框自己的事，所以放在这里而不是 App——
// 这也是「谁的数据，谁负责」的边界体现。
export default function MessageInput({ onSend, disabled }: MessageInputProps) {
  const [value, setValue] = useState('')

  // 真正提交：只在有非空白内容时触发，成功后清空输入框。
  const submit = () => {
    const text = value.trim()
    if (!text) return
    onSend(text)
    setValue('')
  }

  // 键盘处理：Ctrl/⌘ + Enter 发送；Shift + Enter 换行是 textarea 默认行为，不拦截。
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
        placeholder="写点什么……（Ctrl + Enter 发送）"
        disabled={disabled}
        rows={4}
      />
      <button type="submit" disabled={disabled || !value.trim()}>
        {disabled ? '思考中……' : '发送'}
      </button>
    </form>
  )
}
