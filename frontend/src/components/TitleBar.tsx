import { useEffect, useState } from 'react'

// preload 通过 contextBridge 注入到 window.tylerWindow 的窗口控制 API。
// 只在 Electron 环境存在；浏览器 dev 模式下为 undefined，调用方需判空。
interface TylerWindowApi {
  minimize?: () => void
  toggleMaximize?: () => void
  close?: () => void
  onMaximizedChange?: (cb: (maximized: boolean) => void) => () => void
}

declare global {
  interface Window {
    tylerWindow?: TylerWindowApi
  }
}

// 自定义标题栏：应用标题 + 最小化 / 最大化(还原) / 关闭三键。
// 窗口移动由 CSS 的 -webkit-app-region: drag 承担（标题文字区可拖，按钮区 no-drag）。
export default function TitleBar() {
  const [maximized, setMaximized] = useState(false)

  // 订阅最大化状态变化，用于切换「最大化/还原」图标。
  // dev（浏览器）下 window.tylerWindow 不存在，跳过订阅，避免报错。
  useEffect(() => {
    const api = window.tylerWindow
    if (!api?.onMaximizedChange) return
    return api.onMaximizedChange(setMaximized)
  }, [])

  return (
    <header className="titlebar">
      <div className="titlebar__brand">
        <span className="titlebar__logo" />
        <span>Tyler Agent</span>
      </div>
      <div className="titlebar__controls">
        <button
          type="button"
          className="titlebar__btn"
          aria-label="最小化"
          onClick={() => window.tylerWindow?.minimize?.()}
        >
          ─
        </button>
        <button
          type="button"
          className="titlebar__btn"
          aria-label={maximized ? '还原' : '最大化'}
          onClick={() => window.tylerWindow?.toggleMaximize?.()}
        >
          {maximized ? '❐' : '□'}
        </button>
        <button
          type="button"
          className="titlebar__btn titlebar__btn--close"
          aria-label="关闭"
          onClick={() => window.tylerWindow?.close?.()}
        >
          ✕
        </button>
      </div>
    </header>
  )
}
