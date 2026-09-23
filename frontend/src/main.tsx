import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './styles/tokens.css'
import './styles/theme.css'
import './styles/layout.css'
import './styles/chat.css'

// 应用入口：把 React 组件树挂载到 index.html 里的 <div id="root">。
// StrictMode 是开发期的双调用检查，能更早暴露副作用类 bug，生产构建下无影响。
// CSS 按 tokens → theme → layout → chat 顺序加载：变量先行，基础元素其次，骨架再其次，页面细节最后。
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
