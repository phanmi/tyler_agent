const { spawn } = require('node:child_process')
const { randomBytes } = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const readline = require('node:readline')

const READY_TIMEOUT_MS = 30000

function resolveJavaPath(packaged = false, resourcesPath = '') {
    if (packaged) {
        const candidates = [
            path.join(resourcesPath, 'runtime', 'bin', 'java.exe'),
            path.join(resourcesPath, 'bin', 'java.exe'),
        ]
        for (const candidate of candidates) {
            if (fs.existsSync(candidate)) return candidate
        }
    }

    const bundled = path.join(__dirname, '..', '..', 'resources', 'runtime', 'bin', 'java.exe')
    if (fs.existsSync(bundled)) return bundled
    if (process.env.JAVA_HOME) {
        const fromHome = path.join(process.env.JAVA_HOME, 'bin', 'java.exe')
        if (fs.existsSync(fromHome)) return fromHome
    }
    const jdksDir = path.join(os.homedir(), '.jdks')
    if (fs.existsSync(jdksDir)) {
        for (const directory of fs.readdirSync(jdksDir)) {
            const candidate = path.join(jdksDir, directory, 'bin', 'java.exe')
            if (fs.existsSync(candidate)) return candidate
        }
    }
    return 'java'
}

function resolveJarPath(packaged = false, resourcesPath = '') {
    if (process.env.BACKEND_JAR_PATH) return process.env.BACKEND_JAR_PATH
    if (packaged) return path.join(resourcesPath, 'tyler-agent-0.2.0.jar')
    return path.join(__dirname, '..', '..', 'target', 'tyler-agent-0.2.0.jar')
}

// Start one backend and accept a port only from its own ready message.
function startBackendProcess(command, args, options = {}) {
    const nonce = randomBytes(16).toString('hex')
    const timeoutMs = options.timeoutMs ?? READY_TIMEOUT_MS

    return new Promise((resolve, reject) => {
        let child
        try {
            child = spawn(command, args, {
                cwd: options.cwd,
                env: { ...process.env, ...options.env, TYLER_STARTUP_NONCE: nonce },
                stdio: ['ignore', 'pipe', 'pipe'],
                windowsHide: true,
            })
        } catch (error) {
            reject(error)
            return
        }

        let settled = false
        let ready = false
        const timer = setTimeout(() => {
            fail(new Error(`Backend did not become ready within ${timeoutMs / 1000} seconds`))
        }, timeoutMs)

        function fail(error) {
            if (settled) return
            settled = true
            clearTimeout(timer)
            child.kill()
            reject(error)
        }

        const lines = readline.createInterface({ input: child.stdout })
        lines.on('line', (line) => {
            const match = /^TYLER_READY ([0-9a-f]{32}) ([1-9]\d{0,4})$/.exec(line)
            if (!match || match[1] !== nonce || settled) return
            const port = Number(match[2])
            if (port > 65535) {
                fail(new Error(`Backend reported an invalid port: ${port}`))
                return
            }
            ready = true
            settled = true
            clearTimeout(timer)
            resolve({ child, baseUrl: `http://127.0.0.1:${port}`, port })
        })

        child.stdout.on('data', (data) => options.onStdout?.(data))
        child.stderr.on('data', (data) => options.onStderr?.(data))
        child.on('error', (error) => fail(error))
        child.on('exit', (code, signal) => {
            if (!ready) {
                fail(new Error(`Backend exited before it was ready: code=${code} signal=${signal}`))
            } else {
                options.onExit?.(code, signal)
            }
        })
    })
}

module.exports = { resolveJavaPath, resolveJarPath, startBackendProcess }
