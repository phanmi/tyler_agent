const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { EventEmitter } = require('node:events')
const { resolveJavaPath, resolveJarPath, backendArguments, startBackendProcess } = require('../../electron/backend-process.cjs')
const { handleInstallerEvent } = require('../../electron/installer-events.cjs')

test('reading Forge configuration does not require a packaging JDK for development startup', () => {
    const previous = process.env.JAVA_HOME
    delete process.env.JAVA_HOME
    try { assert.equal(require('../../forge.config.cjs').packagerConfig.asar, true) }
    finally { if (previous !== undefined) process.env.JAVA_HOME = previous }
})

test('packaged resources never fall back to host Java or a JAR override', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'Tyler paths '))
    const old = process.env.BACKEND_JAR_PATH
    process.env.BACKEND_JAR_PATH = 'unexpected.jar'
    try {
        assert.throws(() => resolveJavaPath(true, root), /Bundled Java runtime is missing/)
        fs.mkdirSync(path.join(root, 'runtime/bin'), { recursive: true })
        fs.writeFileSync(path.join(root, 'runtime/bin/java.exe'), '')
        assert.equal(resolveJavaPath(true, root), path.join(root, 'runtime/bin/java.exe'))
        assert.equal(resolveJarPath(true, root), path.join(root, `tyler-agent-${require('../../package.json').version}.jar`))
        assert.ok(backendArguments(true, root).includes(`--agent.python.command[0]=${path.join(root, 'tyler-agent-worker/tyler-agent-worker.exe')}`))
    } finally {
        if (old === undefined) delete process.env.BACKEND_JAR_PATH
        else process.env.BACKEND_JAR_PATH = old
        fs.rmSync(root, { recursive: true }) // Only the directory returned by mkdtemp above.
    }
})

test('cancelling startup terminates the owned child before readiness', async () => {
    const controller = new AbortController()
    let exited
    const ready = startBackendProcess(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
        signal: controller.signal,
        onSpawn: (child) => {
            exited = new Promise((resolve) => child.once('exit', resolve))
            controller.abort()
        },
    })
    await assert.rejects(ready, /startup cancelled/)
    await exited
})

test('already cancelled startup does not spawn a child', async () => {
    const controller = new AbortController()
    controller.abort()
    await assert.rejects(startBackendProcess('missing', [], {
        signal: controller.signal, onSpawn: () => assert.fail('must not spawn'),
    }), /startup cancelled/)
})

for (const [event, action] of [['install', '--createShortcut'], ['updated', '--createShortcut'], ['uninstall', '--removeShortcut']]) {
    test(`installer ${event} waits for shortcut maintenance and exits`, () => {
        let quits = 0
        const child = new EventEmitter()
        assert.equal(handleInstallerEvent({ quit: () => quits++ }, ['Tyler', `--squirrel-${event}`], '/app/0.2.0/Tyler.exe',
            (file, args, options) => {
                assert.equal(path.basename(file), 'Update.exe')
                assert.deepEqual(args, [action, 'Tyler.exe'])
                assert.equal(options.windowsHide, true)
                return child
            }), true)
        assert.equal(quits, 0)
        child.emit('exit', 0)
        assert.equal(quits, 1)
    })
}

test('installer updater launch failure exits maintenance without starting the app', () => {
    const child = new EventEmitter()
    let quits = 0
    assert.equal(handleInstallerEvent({ quit: () => quits++ }, ['Tyler', '--squirrel-install'],
        '/app/0.2.0/Tyler.exe', () => child), true)
    child.emit('error', new Error('Update.exe unavailable'))
    assert.equal(quits, 1)
})
