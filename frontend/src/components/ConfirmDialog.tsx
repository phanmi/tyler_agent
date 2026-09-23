import { useEffect } from 'react'
import { createPortal } from 'react-dom'

export interface ConfirmDialogProps {
  open: boolean
  title: string
  message: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  onConfirm: () => void
  onCancel: () => void
}

// 自定义确认对话框：用 React 模态框替代 window.confirm。
// 根因：Electron 无边框窗口下，原生同步对话框关闭后焦点不归还渲染进程，
// 导致整个应用无法输入/点击。此处改为完全非阻塞、焦点由 React 掌控的 UI。
export default function ConfirmDialog({
  open,
  title,
  message,
  confirmText = '确定',
  cancelText = '取消',
  danger = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  // Esc 关闭。
  useEffect(() => {
    if (!open) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open, onCancel])

  if (!open) return null

  return createPortal(
    <div className="confirm-overlay" onClick={onCancel}>
      <div
        className="confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 className="confirm-dialog__title">{title}</h2>
        <p className="confirm-dialog__message">{message}</p>
        <div className="confirm-actions">
          <button type="button" className="confirm-btn" onClick={onCancel}>
            {cancelText}
          </button>
          <button
            type="button"
            className={danger ? 'confirm-btn confirm-btn--danger' : 'confirm-btn'}
            onClick={onConfirm}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}
