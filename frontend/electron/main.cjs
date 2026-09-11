const { app, BrowserWindow, dialog } = require('electron')
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

// 定位 java 可执行文件：优先用 bundle 进来的 JRE（resources/runtime），
// 找不到再回退到 JAVA_HOME → ~/.jdks 扫描 → PATH 上的 java。
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

    // 开发态：优先 bundle 进来的 JRE（Phase 6）
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

// 定位 backend JAR：允许环境变量 BACKEND_JAR_PATH 覆盖，否则按源码目录结构找 target 下的产物。
function resolveJarPath() {
    if (process.env.BACKEND_JAR_PATH) return process.env.BACKEND_JAR_PATH
    if (app.isPackaged) {
        return path.join(process.resourcesPath, 'tyler-agent-0.1.0.jar')
    }
    return path.join(__dirname, '..', '..', 'target', 'tyler-agent-0.1.0.jar')
}

// 探测后端是否就绪：GET /api/apikey/status 返回 200 即视为 ready。
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

// 轮询直到后端 ready 或超时。
function waitForReady(timeoutMs) {
    const deadline = Date.now() + timeoutMs
    return new Promise((resolve, reject) => {
        const poll = async () => {
            if (await checkReady()) {
                resolve()
                return
            }
            if (Date.now() > deadline) {
                reject(new Error(`Backend 未在 ${timeoutMs / 1000} 秒内就绪`))
                return
            }
            setTimeout(poll, POLL_INTERVAL_MS)
        }
        poll()
    })
}

// 启动 Spring Boot JAR，等它 ready 后 resolve。
function startBackend() {
    return new Promise((resolve, reject) => {
        const javaPath = resolveJavaPath()
        const jarPath = resolveJarPath()

        if (!fs.existsSync(jarPath)) {
            reject(new Error(`找不到 backend JAR：${jarPath}\n请先执行 mvn clean package`))
            return
        }

        console.log(`[backend] 使用 Java: ${javaPath}`)
        console.log(`[backend] 启动 JAR: ${jarPath}`)

        backendProcess = spawn(javaPath, ['-jar', jarPath], {
            stdio: ['ignore', 'pipe', 'pipe'],
            windowsHide: true,
        })

        backendProcess.stdout.on('data', (d) => process.stdout.write(`[backend] ${d}`))
        backendProcess.stderr.on('data', (d) => process.stderr.write(`[backend] ${d}`))

        backendProcess.on('error', (err) => {
            reject(new Error(`无法启动 Java（${javaPath}）：${err.message}`))
        })

        backendProcess.on('exit', (code, signal) => {
            console.log(`[backend] 进程退出 code=${code} signal=${signal}`)
        })

        waitForReady(READY_TIMEOUT_MS).then(resolve).catch(reject)
    })
}

// 停止 backend：终止 Java 子进程，防止 Electron 退出后残留。
function stopBackend() {
    if (!backendProcess) return
    const p = backendProcess
    backendProcess = null
    try {
        p.kill()
    } catch (e) {
        // 已退出或 kill 失败时忽略，交给系统回收。
    }
}

function createWindow() {
    const win = new BrowserWindow({
        width: 1200,
        height: 800,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
        },
    })
    win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
}

// 启动链：先拉起 backend，ready 后再开窗，保证 UI 一打开就能聊天。
async function main() {
    try {
        await startBackend()
        createWindow()
    } catch (err) {
        dialog.showErrorBox('Tyler 启动失败', err && err.message ? err.message : String(err))
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