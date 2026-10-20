const { app, BrowserWindow, dialog, ipcMain } = require('electron')
const path = require('path')
const fs = require('fs')
const { resolveJavaPath, resolveJarPath, backendArguments, startBackendProcess } = require('./backend-process.cjs')
const { handleInstallerEvent } = require('./installer-events.cjs')

let backendProcess = null
let backendBaseUrl = null
let stopping = false
const startup = new AbortController()

// Start the backend on an OS-assigned port and keep that exact child process.
async function startBackend() {
    const javaPath = resolveJavaPath(app.isPackaged, process.resourcesPath)
    const jarPath = resolveJarPath(app.isPackaged, process.resourcesPath)
    if (!fs.existsSync(jarPath)) {
        throw new Error(`Backend JAR not found: ${jarPath}\nRun mvn clean package first`)
    }

    console.log(`[backend] Java executable: ${javaPath}`)
    console.log(`[backend] Starting JAR: ${jarPath}`)
    const started = await startBackendProcess(javaPath,
        backendArguments(app.isPackaged, process.resourcesPath), {
            // Development resolves the worker from the repository; packaged argv uses absolute paths.
            cwd: app.isPackaged ? undefined : path.join(__dirname, '..', '..'),
            signal: startup.signal,
            onSpawn: (child) => {
                backendProcess = child
                console.log(`[backend] Owned process: pid=${child.pid}`)
            },
            onStdout: (data) => process.stdout.write(`[backend] ${data}`),
            onStderr: (data) => process.stderr.write(`[backend] ${data}`),
            onExit: (code, signal) => {
                console.log(`[backend] Process exited: code=${code} signal=${signal}`)
                if (!stopping) {
                    backendProcess = null
                    backendBaseUrl = null
                    dialog.showErrorBox('Tyler backend stopped', 'The backend process exited unexpectedly.')
                    app.quit()
                }
            },
        })
    if (stopping) { started.child.kill(); return }
    if (started.child.exitCode !== null) throw new Error('Backend exited immediately after startup')
    backendProcess = started.child
    backendBaseUrl = started.baseUrl
    console.log(`[backend] Ready at ${backendBaseUrl}`)
}

// Terminate the Java child process when Electron exits.
function stopBackend() {
    stopping = true
    startup.abort()
    if (!backendProcess) return
    const p = backendProcess
    backendProcess = null
    backendBaseUrl = null
    try {
        p.kill()
    } catch (e) {
        // Ignore failures if the process has already exited.
    }
}

function createWindow() {
    const win = new BrowserWindow({
        width: 1200,
        height: 800,
        // Use the renderer's TitleBar component in place of the system window frame.
        frame: false,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: path.join(__dirname, 'preload.cjs'),
        },
    })

    // Notify the renderer when maximized state changes so it can update the icon.
    const emitMaximized = () => {
        win.webContents.send('window:maximized-changed', win.isMaximized())
    }
    win.on('maximize', emitMaximized)
    win.on('unmaximize', emitMaximized)

    win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
}

// Window-control IPC is exposed to the renderer through window.tylerWindow.
function registerWindowControls() {
    ipcMain.handle('backend:get-url', () => {
        if (!backendBaseUrl) throw new Error('Backend is not ready')
        return backendBaseUrl
    })
    ipcMain.on('window:minimize', (event) => {
        BrowserWindow.fromWebContents(event.sender)?.minimize()
    })
    ipcMain.on('window:toggle-maximize', (event) => {
        const win = BrowserWindow.fromWebContents(event.sender)
        if (!win) return
        if (win.isMaximized()) {
            win.unmaximize()
        } else {
            win.maximize()
        }
    })
    ipcMain.on('window:close', (event) => {
        BrowserWindow.fromWebContents(event.sender)?.close()
    })
}

// Start the backend before opening the window so the UI can connect immediately.
async function main() {
    try {
        registerWindowControls()
        await startBackend()
        if (!stopping) createWindow()
    } catch (err) {
        if (!stopping) dialog.showErrorBox('Tyler failed to start', err && err.message ? err.message : String(err))
        app.quit()
    }
}

if (!handleInstallerEvent(app)) app.whenReady().then(main)

app.on('before-quit', stopBackend)

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit()
})

app.on('activate', () => {
    if (backendProcess && BrowserWindow.getAllWindows().length === 0) createWindow()
})
