import { useEffect, useState } from 'react'

// Window-control API exposed by preload through contextBridge as window.tylerWindow.
// Available only in Electron; callers check for undefined in browser development mode.
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

// Custom title bar with app title, minimize, maximize/restore, and close buttons.
// CSS -webkit-app-region: drag moves the window; controls use no-drag.
export default function TitleBar() {
  const [maximized, setMaximized] = useState(false)

  // Subscribe to maximized-state changes to update the maximize/restore icon.
  // Skip the subscription when running in a browser without window.tylerWindow.
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
          aria-label="Minimize"
          onClick={() => window.tylerWindow?.minimize?.()}
        >
          ─
        </button>
        <button
          type="button"
          className="titlebar__btn"
          aria-label={maximized ? 'Restore' : 'Maximize'}
          onClick={() => window.tylerWindow?.toggleMaximize?.()}
        >
          {maximized ? '❐' : '□'}
        </button>
        <button
          type="button"
          className="titlebar__btn titlebar__btn--close"
          aria-label="Close"
          onClick={() => window.tylerWindow?.close?.()}
        >
          ✕
        </button>
      </div>
    </header>
  )
}
