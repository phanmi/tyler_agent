const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { once } = require('node:events')
const test = require('node:test')
const backend = require('../../electron/backend-process.cjs')

const frontend = path.resolve(__dirname, '../..')
const probe = path.join(__dirname, 'support/relative-path-probe.cjs')

/** Execute each real launcher up to its backend call without opening a window or starting Vite. */
function captureLaunch(entry, directory) {
    const source = path.join(frontend, entry)
    const requireSource = createRequire(source)
    const captured = Promise.withResolvers()
    const quiet = { write() {} }
    const app = { isPackaged: false, on() {}, quit() {}, whenReady: () => Promise.resolve() }
    vm.runInNewContext(fs.readFileSync(source, 'utf8'), {
        __dirname: directory,
        AbortController,
        console: { log() {}, error() {} },
        process: { argv: [], platform: process.platform, on() {}, stdout: quiet, stderr: quiet },
        require(name) {
            if (name.endsWith('backend-process.cjs')) {
                return {
                    ...backend,
                    startBackendProcess(command, args, options) {
                        captured.resolve({ command, args, options })
                        // Stop at this boundary; the test runs the captured paths in a real child below.
                        return new Promise(() => {})
                    },
                }
            }
            if (name === 'electron') return { app, ipcMain: { on() {}, handle() {} } }
            if (name.endsWith('installer-events.cjs')) return { handleInstallerEvent: () => false }
            return requireSource(name)
        },
    }, { filename: source, timeout: 1000 })
    return captured.promise
}

for (const entry of ['scripts/dev.cjs', 'electron/main.cjs']) {
    for (const jarPath of ['relative', 'absolute']) {
        test(`${entry}: ${jarPath} JAR and existing relative workspace survive child cwd`,
            { timeout: 10000 }, async () => {
                const root = fs.mkdtempSync(path.join(os.tmpdir(), 'Tyler relative paths '))
                const originalDirectory = process.cwd()
                const originalEnvironment = {
                    BACKEND_JAR_PATH: process.env.BACKEND_JAR_PATH,
                    AGENT_WORKSPACE_DIR: process.env.AGENT_WORKSPACE_DIR,
                }
                let child
                let exited
                try {
                    const launchDirectory = path.join(root, 'frontend')
                    const workspace = path.join(launchDirectory, 'data')
                    fs.mkdirSync(workspace, { recursive: true })
                    fs.mkdirSync(path.join(root, 'target'))
                    fs.mkdirSync(path.join(root, 'data'))
                    fs.writeFileSync(path.join(root, 'target', 'chosen.jar'), 'chosen JAR')
                    fs.writeFileSync(path.join(workspace, 'history.json'), 'existing history')
                    fs.writeFileSync(path.join(root, 'data', 'history.json'), 'different workspace')
                    process.chdir(launchDirectory)
                    process.env.BACKEND_JAR_PATH = jarPath === 'relative'
                        ? '../target/chosen.jar' : path.join(root, 'target/chosen.jar')
                    process.env.AGENT_WORKSPACE_DIR = './data'
                    const launch = await captureLaunch(entry, path.join(launchDirectory, path.dirname(entry)))
                    const { cwd, env } = launch.options
                    // A real child resolves the exact captured -jar path and effective workspace.
                    const started = await backend.startBackendProcess(process.execPath, [probe, ...launch.args], {
                        cwd, env, timeoutMs: 5000,
                        onSpawn(process) { child = process; exited = once(child, 'exit') },
                    })
                    assert.equal(started.port, 43210)
                    assert.equal(fs.readFileSync(path.join(workspace, 'saved.txt'), 'utf8'), 'saved')
                    assert.equal(fs.readFileSync(path.join(workspace, 'history.json'), 'utf8'), 'existing history')
                    assert.equal(fs.readFileSync(path.join(root, 'data', 'history.json'), 'utf8'), 'different workspace')
                    assert.equal(fs.existsSync(path.join(root, 'data', 'saved.txt')), false)
                    assert.equal(process.env.AGENT_WORKSPACE_DIR, './data', 'Parent environment must remain unchanged')
                } finally {
                    child?.kill()
                    if (exited) await exited
                    process.chdir(originalDirectory)
                    for (const [name, value] of Object.entries(originalEnvironment)) {
                        if (value === undefined) delete process.env[name]
                        else process.env[name] = value
                    }
                    fs.rmSync(root, { recursive: true, force: true }) // Only this test's mkdtemp directory.
                }
            })
    }
}
