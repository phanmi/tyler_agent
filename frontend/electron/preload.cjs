const { contextBridge, ipcRenderer } = require('electron')

// 通过 contextBridge 把窗口控制能力安全暴露给渲染进程（window.tylerWindow）。
// 只暴露白名单方法，不暴露 ipcRenderer 本体，保持 contextIsolation 隔离。
contextBridge.exposeInMainWorld('tylerWindow', {
  minimize: () => ipcRenderer.send('window:minimize'),
  toggleMaximize: () => ipcRenderer.send('window:toggle-maximize'),
  close: () => ipcRenderer.send('window:close'),
  // 订阅最大化状态变化；返回取消订阅函数，供 React effect 清理。
  onMaximizedChange: (callback) => {
    const listener = (_event, maximized) => callback(maximized)
    ipcRenderer.on('window:maximized-changed', listener)
    return () => ipcRenderer.removeListener('window:maximized-changed', listener)
  },
})
