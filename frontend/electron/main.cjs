const { app, BrowserWindow, dialog, ipcMain } = require('electron')
const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')
const os = require('os')
const http = require('http')

const BACKEND_PORT = 8080
const READY_URL = `http://127.0.0.1:${BACKEND_PORT}/api/apikey/status`
const READY_TIMEOUT_MS = 30000
const POLL_INTERVAL_MS = 500

let backendProcess = null

// Locate Java: prefer the bundled JRE under resources/runtime,
// then try JAVA_HOME, scan ~/.jdks, and finally use java from PATH.
function resolveJavaPath() {
    if (app.isPackaged) {
        const candidates = [
            path.join(process.resourcesPath, 'runtime', 'bin', 'java.exe'),
            path.join(process.resourcesPath, 'bin', 'java.exe'),
        ]
        for (const c of candidates) {
            if (fs.existsSync(c)) return c
        }
    }

    // Development: prefer the bundled JRE (Phase 6).
    const bundled = path.join(__dirname, '..', '..', 'resources', 'runtime', 'bin', 'java.exe')
    if (fs.existsSync(bundled)) return bundled

    if (process.env.JAVA_HOME) {
        const fromHome = path.join(process.env.JAVA_HOME, 'bin', 'java.exe')
        if (fs.existsSync(fromHome)) return fromHome
    }
    const jdksDir = path.join(os.homedir(), '.jdks')
    if (fs.existsSync(jdksDir)) {
        const dirs = fs.readdirSync(jdksDir)
        for (const d of dirs) {
            const candidate = path.join(jdksDir, d, 'bin', 'java.exe')
            if (fs.existsSync(candidate)) return candidate
        }
    }
    return 'java'
}

// Locate the backend JAR: use BACKEND_JAR_PATH or the build output under target.
function resolveJarPath() {
    if (process.env.BACKEND_JAR_PATH) return process.env.BACKEND_JAR_PATH
    if (app.isPackaged) {
        return path.join(process.resourcesPath, 'tyler-agent-0.1.0.jar')
    }
    return path.join(__dirname, '..', '..', 'target', 'tyler-agent-0.1.0.jar')
}

// The backend is ready when GET /api/apikey/status returns 200.
function checkReady() {
    return new Promise((resolve) => {
        const req = http.get(READY_URL, (res) => {
            res.resume()
            resolve(res.statusCode === 200)
        })
        req.on('error', () => resolve(false))
        req.setTimeout(2000, () => {
            req.destroy()
            resolve(false)
        })
    })
}

// Poll until the backend is ready or the timeout expires.
function waitForReady(timeoutMs) {
    const deadline = Date.now() + timeoutMs
    return new Promise((resolve, reject) => {
        const poll = async () => {
            if (await checkReady()) {
                resolve()
                return
            }
            if (Date.now() > deadline) {
                reject(new Error(`Backend did not become ready within ${timeoutMs / 1000} seconds`))
                return
            }
            setTimeout(poll, POLL_INTERVAL_MS)
        }
        poll()
    })
}

// Start the Spring Boot JAR and resolve once it is ready.
function startBackend() {
    return new Promise((resolve, reject) => {
        const javaPath = resolveJavaPath()
        const jarPath = resolveJarPath()

        if (!fs.existsSync(jarPath)) {
            reject(new Error(`Backend JAR not found: ${jarPath}\nRun mvn clean package first`))
            return
        }

        console.log(`[backend] Java executable: ${javaPath}`)
        console.log(`[backend] Starting JAR: ${jarPath}`)

        backendProcess = spawn(javaPath, ['-jar', jarPath], {
            stdio: ['ignore', 'pipe', 'pipe'],
            windowsHide: true,
        })

        backendProcess.stdout.on('data', (d) => process.stdout.write(`[backend] ${d}`))
        backendProcess.stderr.on('data', (d) => process.stderr.write(`[backend] ${d}`))

        backendProcess.on('error', (err) => {
            reject(new Error(`Cannot start Java (${javaPath}): ${err.message}`))
        })

        backendProcess.on('exit', (code, signal) => {
            console.log(`[backend] Process exited: code=${code} signal=${signal}`)
        })

        waitForReady(READY_TIMEOUT_MS).then(resolve).catch(reject)
    })
}

// Terminate the Java child process when Electron exits.
function stopBackend() {
    if (!backendProcess) return
    const p = backendProcess
    backendProcess = null
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
        createWindow()
    } catch (err) {
        dialog.showErrorBox('Tyler failed to start', err && err.message ? err.message : String(err))
        app.quit()
    }
}

app.whenReady().then(main)

app.on('before-quit', stopBackend)

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit()
})

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
})