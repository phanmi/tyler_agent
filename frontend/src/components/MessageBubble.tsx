import type { Message } from '../types'

// 单条消息气泡：接收一条 Message，根据 role 渲染成不同对齐和配色。
// 这是最典型的「可复用叶子组件」——
//   用户消息和 Tyler 回复是「同一种数据、两种视觉形态」，
//   数据结构相同（谁说的 + 内容），只有样式不同（左右、颜色）。
// 不拆的话，就得在父组件里写两遍几乎一样的 JSX；
// 以后要加 Markdown 渲染 / 代码高亮，也只需改这一个地方。
export default function MessageBubble({ message }: { message: Message }) {
  const isUser = message.role === 'user'

  return (
    <div className={`bubble ${isUser ? 'bubble--user' : 'bubble--assistant'}`}>
      {/* 直接用 {message.content} 渲染：React 会自动转义 HTML，
          比旧页面用 innerHTML 更安全，天然规避 XSS 注入。 */}
      <div className="bubble__content">{message.content}</div>
    </div>
  )
}
