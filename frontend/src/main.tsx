import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

// 应用入口：把 React 组件树挂载到 index.html 里的 <div id="root">。
// StrictMode 是开发期的双调用检查，能更早暴露副作用类 bug，生产构建下无影响。
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
