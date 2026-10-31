const assert = require('node:assert/strict')
const test = require('node:test')
const { startBackendProcess } = require('../../electron/backend-process.cjs')

test('uses only the nonce-matched port reported by its child', async () => {
    const script = [
        "console.log('TYLER_READY ' + '0'.repeat(32) + ' 8080')",
        "console.log('TYLER_READY ' + process.env.TYLER_STARTUP_NONCE + ' 43210')",
        'setInterval(() => {}, 1000)',
    ].join(';')
    const started = await startBackendProcess(process.execPath, ['-e', script])
    try {
        assert.equal(started.port, 43210)
        assert.equal(started.baseUrl, 'http://127.0.0.1:43210')
    } finally {
        started.child.kill()
    }
})

test('gives the child a fresh access token and returns it to the launcher only', async () => {
    const script = [
        "console.log('TYLER_READY ' + process.env.TYLER_STARTUP_NONCE + ' 43210')",
        "process.stderr.write('TOKEN=' + process.env.TYLER_ACCESS_TOKEN + ' ')",
        'setInterval(() => {}, 1000)',
    ].join(';')
    let stderr = ''
    const first = await startBackendProcess(process.execPath, ['-e', script], {
        env: { TYLER_ACCESS_TOKEN: 'inherited-value-must-be-replaced' },
        onStderr: (data) => { stderr += data },
    })
    const second = await startBackendProcess(process.execPath, ['-e', script])
    try {
        assert.match(first.accessToken, /^[0-9a-f]{64}$/)
        assert.notEqual(first.accessToken, second.accessToken)
        await new Promise((resolve) => setTimeout(resolve, 100))
        assert.ok(stderr.includes('TOKEN=' + first.accessToken), 'child must receive the launcher token')
        assert.ok(!stderr.includes('inherited-value-must-be-replaced'))
    } finally {
        first.child.kill()
        second.child.kill()
    }
})

test('rejects a child that exits without its own ready message', async () => {
    const script = "console.log('TYLER_READY ' + '0'.repeat(32) + ' 8080')"
    await assert.rejects(
        startBackendProcess(process.execPath, ['-e', script]),
        /exited before it was ready/,
    )
})

test('rejects an invalid reported port', async () => {
    const script = "console.log('TYLER_READY ' + process.env.TYLER_STARTUP_NONCE + ' 99999')"
    await assert.rejects(
        startBackendProcess(process.execPath, ['-e', script]),
        /invalid port/,
    )
})

test('times out when the child never becomes ready', async () => {
    await assert.rejects(
        startBackendProcess(process.execPath, ['-e', 'setInterval(() => {}, 1000)'],
            { timeoutMs: 250 }),
        /did not become ready/,
    )
})

test('reports a missing executable without announcing a backend exit', async () => {
    let reportedExit = false
    await assert.rejects(
        startBackendProcess('tyler-missing-java-executable', [], {
            onExit: () => { reportedExit = true },
        }),
        /ENOENT/,
    )
    assert.equal(reportedExit, false)
})

test('reports a backend failure after it becomes ready', async () => {
    const script = [
        "console.log('TYLER_READY ' + process.env.TYLER_STARTUP_NONCE + ' 43210')",
        'setTimeout(() => process.exit(7), 50)',
    ].join(';')
    let reportExit
    const exited = new Promise((resolve) => { reportExit = resolve })
    await startBackendProcess(process.execPath, ['-e', script], {
        onExit: (code) => reportExit(code),
    })
    assert.equal(await exited, 7)
})
