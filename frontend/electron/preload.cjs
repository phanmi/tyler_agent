const { contextBridge, ipcRenderer } = require('electron')

// Expose window controls to the renderer through contextBridge (window.tylerWindow).
// Expose only approved methods; keep ipcRenderer private with contextIsolation enabled.
contextBridge.exposeInMainWorld('tylerWindow', {
  minimize: () => ipcRenderer.send('window:minimize'),
  toggleMaximize: () => ipcRenderer.send('window:toggle-maximize'),
  close: () => ipcRenderer.send('window:close'),
  // Subscribe to maximized-state changes and return an unsubscribe function for effect cleanup.
  onMaximizedChange: (callback) => {
    const listener = (_event, maximized) => callback(maximized)
    ipcRenderer.on('window:maximized-changed', listener)
    return () => ipcRenderer.removeListener('window:maximized-changed', listener)
  },
})
