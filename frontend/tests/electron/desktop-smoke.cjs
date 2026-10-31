// Real packaged Electron/Java/Python lifecycle. The inspector is enabled only by this test.
// Electron's Node mode must copy/hash app.asar as an ordinary file, not a virtual directory.
if (process.versions.electron) process.noAsar = true
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { spawn, spawnSync } = require('node:child_process')
const { createHash } = require('node:crypto')

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
async function until(check, label, timeout = 45000) {
    const end = Date.now() + timeout
    while (Date.now() < end) {
        const result = await check()
        if (result) return result
        await sleep(50)
    }
    throw new Error(`Timed out: ${label}`)
}
const alive = (pid) => { try { process.kill(pid, 0); return true } catch { return false } }

// A terminated backend or startup dialog is a failure, not a reason to keep polling readiness.
function backendReady(log, child, name) {
    const failure = /SMOKE_DIALOG ([^\r\n]+)/.exec(log)?.[1]
    if (failure) throw new Error(`Backend startup failed (${name}): ${failure}`)
    if (child.exitCode !== null || child.signalCode !== null) {
        throw new Error(`Application exited before readiness (${name}): code=${child.exitCode} signal=${child.signalCode}`)
    }
    return /\[backend\] Ready at (http:\/\/127\.0\.0\.1:\d+)/.exec(log)?.[1]
}

// Explicit traversal avoids Node 22's recursive cp directory-name conversion on Windows.
function copyTree(source, destination) {
    fs.mkdirSync(destination, { recursive: true })
    for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
        const from = path.join(source, entry.name), to = path.join(destination, entry.name)
        if (entry.isDirectory()) copyTree(from, to)
        else if (entry.isFile()) fs.copyFileSync(from, to)
        else throw new Error(`Unexpected nonregular artifact entry: ${entry.name}`)
    }
}

async function debuggerConnection(url) {
    const socket = new WebSocket(url)
    await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
    let sequence = 0
    const pending = new Map()
    socket.onmessage = ({ data }) => {
        const message = JSON.parse(data)
        if (message.id) {
            const waiter = pending.get(message.id)
            if (!waiter) return
            clearTimeout(waiter.timer)
            pending.delete(message.id)
            if (message.error) waiter.reject(new Error(JSON.stringify(message.error)))
            else waiter.resolve(message.result)
        }
    }
    return {
        socket,
        send(method, params = {}) {
            return new Promise((resolve, reject) => {
                const id = ++sequence
                const timer = setTimeout(() => { pending.delete(id); reject(new Error(`Inspector timeout: ${method}`)) }, 5000)
                pending.set(id, { resolve, reject, timer })
                socket.send(JSON.stringify({ id, method, params }))
            })
        },
    }
}

function smokeEnvironment(data) {
    const osVariables = new Set(['systemroot', 'windir', 'comspec', 'systemdrive', 'programdata',
        'programfiles', 'programfiles(x86)', 'programw6432', 'commonprogramfiles', 'commonprogramfiles(x86)',
        'commonprogramw6432', 'allusersprofile', 'public', 'username', 'userprofile', 'userdomain',
        'homedrive', 'homepath', 'os', 'pathext', 'number_of_processors', 'processor_architecture',
        'processor_identifier', 'processor_level', 'processor_revision', 'sessionname'])
    return {
        ...Object.fromEntries(Object.entries(process.env).filter(([key]) => osVariables.has(key.toLowerCase()))),
        TEMP: os.tmpdir(), TMP: os.tmpdir(), PATH: '', APPDATA: data, LOCALAPPDATA: data,
        AGENT_WORKSPACE_DIR: data,
    }
}

/** Launch with host runtimes and provider variables absent; each run has isolated application data. */
async function launch(executable, directory, name, args = []) {
    const data = path.join(directory, name)
    fs.mkdirSync(data, { recursive: true })
    const env = smokeEnvironment(data)
    const child = spawn(executable, ['--inspect=0', ...args], { cwd: data, env, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
    let log = ''
    child.stdout.on('data', (chunk) => { log += chunk })
    child.stderr.on('data', (chunk) => { log += chunk })
    let debug
    const pids = () => [...log.matchAll(/(?:Owned process: pid=|worker launched: pid=)(\d+)/g)].map((match) => Number(match[1]))
    try {
        const url = await until(() => /ws:\/\/127\.0\.0\.1:\d+\/[^\s]+/.exec(log)?.[0], 'Electron inspector')
        debug = await debuggerConnection(url)
        await debug.send('Runtime.enable')
        await until(async () => {
            const initialized = await debug.send('Runtime.evaluate', {
                expression: `(() => { const e = process.mainModule.require('electron'); globalThis.__smokeElectron = e; e.dialog.showErrorBox = (title, text) => console.error('SMOKE_DIALOG ' + title + ': ' + text); e.app.on('browser-window-created', (_, w) => w.hide()); return true })()`,
                returnByValue: true,
            })
            return initialized.result?.value === true
        }, 'main process test initialization', 5000)
        return {
            child, debug, data, log: () => log, pids,
            async ready() {
                return until(() => backendReady(log, child, name), `backend ready (${name})`)
            },
            // Read the token the way the window does, through its preload bridge.
            async accessToken() {
                const token = await until(async () => (await debug.send('Runtime.evaluate', {
                    expression: "globalThis.__smokeElectron.BrowserWindow.getAllWindows()[0]?.webContents.executeJavaScript('window.tylerBackend.getAccessToken()')",
                    awaitPromise: true, returnByValue: true,
                })).result?.value, `access token (${name})`)
                return { 'X-Tyler-Token': token }
            },
            async quit() {
                // Disconnect before exit so Node's inspector cannot keep the app alive.
                debug.socket.send(JSON.stringify({ id: 999999, method: 'Runtime.evaluate', params: { expression: "setImmediate(() => globalThis.__smokeElectron.app.quit())" } }))
                debug.socket.close()
            },
            async stopped() {
                await until(() => child.exitCode !== null || child.signalCode !== null, `Electron exit (${name})`)
                await until(() => pids().every((pid) => !alive(pid)), `owned children exit (${name})`, 15000)
                assert.ok(!log.includes('SYNTHETIC_SMOKE_KEY'), 'key must not appear in logs')
                assert.ok(!log.includes('"protocolVersion"'), 'raw protocol frames must not appear in desktop logs')
                fs.writeFileSync(path.join(data, 'desktop.log'), log)
            },
            cleanup() {
                debug.socket.close()
                child.kill()
                for (const pid of pids()) if (alive(pid)) process.kill(pid)
                fs.writeFileSync(path.join(data, 'desktop.log'), log)
            },
        }
    } catch (error) {
        debug?.socket.close()
        child.kill()
        for (const pid of pids()) if (alive(pid)) process.kill(pid)
        fs.writeFileSync(path.join(data, 'desktop.log'), log)
        throw error
    }
}

async function run() {
    assert.equal(process.platform, 'win32', 'Desktop smoke must run on Windows')
    const artifact = path.resolve(process.argv[2] || 'out/Tyler-win32-x64')
    const fixture = path.resolve(process.argv[3] || '../agent-python/dist/desktop-test-worker')
    const output = path.resolve(process.argv[4] || '../target')
    fs.mkdirSync(output, { recursive: true })
    const root = fs.mkdtempSync(path.join(output, 'desktop-smoke-'))
    const relocated = path.join(root, 'Tyler space 中文')
    copyTree(artifact, relocated)
    const executable = path.join(relocated, 'Tyler.exe')
    const resources = path.join(relocated, 'resources')
    const worker = path.join(resources, 'tyler-agent-worker')
    const sha = (file) => createHash('sha256').update(fs.readFileSync(file)).digest('hex')
    const manifest = JSON.parse(fs.readFileSync(path.join(resources, 'desktop-build.json'), 'utf8'))
    assert.equal(sha(path.join(worker, 'tyler-agent-worker.exe')), manifest.workerSha256)
    assert.equal(sha(path.join(resources, `tyler-agent-${manifest.releaseVersion}.jar`)), manifest.jarSha256)
    const report = { platform: os.release(), artifact, manifest, executableSha256: sha(executable),
        appAsarSha256: sha(path.join(resources, 'app.asar')), cases: [] }
    fs.writeFileSync(path.join(root, 'report.json'), JSON.stringify(report, null, 2))
    const java = path.join(resources, 'runtime/bin/java.exe')
    assert.equal(sha(java), manifest.javaLauncherSha256, 'Packaged Java launcher must match the build manifest')
    // Diagnose native runtime failures before starting Electron or waiting on Spring readiness.
    const runtime = spawnSync(java, ['-version'], {
        env: smokeEnvironment(root), encoding: 'utf8', windowsHide: true, timeout: 10000,
    })
    const runtimeLog = path.join(root, 'runtime-preflight.log')
    fs.writeFileSync(runtimeLog, `${runtime.stdout || ''}${runtime.stderr || ''}${runtime.error?.message || ''}`)
    assert.equal(runtime.status, 0, `Bundled Java failed from the Unicode path; see ${runtimeLog}`)
    async function scenario(name, action, args) {
        const app = await launch(executable, root, name, args)
        try {
            await action(app)
            await app.stopped()
            report.cases.push(name)
            fs.writeFileSync(path.join(root, 'report.json'), JSON.stringify(report, null, 2))
            console.log(`PASS ${name}`)
        } catch (error) {
            console.error(`FAIL ${name}; diagnostics: ${path.join(app.data, 'desktop.log')}`)
            throw error
        } finally { app.cleanup() }
    }
    await scenario('production-ready-quit', async (app) => {
        const url = await app.ready()
        assert.equal((await fetch(url + '/api/agent/history')).status, 401, 'API must require the access token')
        assert.equal((await fetch(url + '/api/agent/history', { headers: await app.accessToken() })).status, 200)
        assert.equal(app.pids().length, 2)
        await app.quit()
    })
    await scenario('forced-java-exit', async (app) => {
        await app.ready()
        process.kill(app.pids()[0])
        await until(() => app.log().includes('SMOKE_DIALOG'), 'backend exit diagnostic')
        app.debug.socket.close()
    })
    fs.renameSync(worker, worker + '-production')
    await scenario('missing-worker', async (app) => {
        await until(() => app.log().includes('SMOKE_DIALOG'), 'missing worker diagnostic')
        app.debug.socket.close()
        assert.ok(!app.log().includes('[backend] Ready at'))
    })
    fs.mkdirSync(worker)
    fs.writeFileSync(path.join(worker, 'tyler-agent-worker.exe'), 'not an executable')
    await scenario('corrupt-worker', async (app) => {
        await until(() => app.log().includes('SMOKE_DIALOG'), 'corrupt worker diagnostic')
        app.debug.socket.close()
    })
    copyTree(fixture, worker)
    for (const mode of ['incompatible', 'startup_failure', 'timeout']) {
        fs.writeFileSync(path.join(worker, 'scenario.txt'), mode)
        await scenario(mode, async (app) => {
            await until(() => app.log().includes('SMOKE_DIALOG'), `${mode} diagnostic`)
            app.debug.socket.close()
            assert.ok(!app.log().includes('[backend] Ready at'))
        })
    }
    fs.writeFileSync(path.join(worker, 'scenario.txt'), 'timeout')
    await scenario('quit-during-startup', async (app) => {
        await until(() => app.pids().length === 2, 'worker launched before readiness')
        await app.quit()
        assert.ok(!app.log().includes('[backend] Ready at'))
    })
    fs.writeFileSync(path.join(worker, 'scenario.txt'), 'active')
    await scenario('quit-during-active-request', async (app) => {
        const url = await app.ready()
        const auth = await app.accessToken()
        await fetch(url + '/api/apikey', { method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' }, body: JSON.stringify({ apiKey: 'SYNTHETIC_SMOKE_KEY' }) })
        const pending = fetch(url + '/api/agent/chat', { method: 'POST', headers: { ...auth, 'Content-Type': 'application/json' }, body: JSON.stringify({ message: 'write then wait' }) }).catch(() => null)
        await until(() => fs.existsSync(path.join(app.data, 'smoke.txt')), 'real committed sandbox write')
        await app.quit()
        await pending
    })
    const maintenanceData = path.join(root, 'installer-obsolete')
    fs.mkdirSync(maintenanceData)
    const maintenance = spawn(executable, ['--squirrel-obsolete'], {
        cwd: maintenanceData, env: smokeEnvironment(maintenanceData), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
    })
    let maintenanceLog = ''
    maintenance.stdout.on('data', (chunk) => { maintenanceLog += chunk })
    try {
        await until(() => maintenance.exitCode !== null, 'installer maintenance exit', 10000)
        assert.equal(maintenance.exitCode, 0)
        assert.ok(!maintenanceLog.includes('[backend]'))
        report.cases.push('installer-obsolete')
        fs.writeFileSync(path.join(root, 'report.json'), JSON.stringify(report, null, 2))
    } finally { maintenance.kill() }
    console.log(`Smoke evidence: ${root}`)
}

if (require.main === module) run().catch((error) => { console.error(error); process.exitCode = 1 })
module.exports = { backendReady }
