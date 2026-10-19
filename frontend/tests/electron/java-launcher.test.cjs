const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { configureJavaLauncher, utf8Manifest } = require('../../scripts/java-launcher.cjs')
const { backendReady } = require('./desktop-smoke.cjs')

const manifest = '<assembly><trustInfo>keep privileges</trustInfo><asmv3:windowsSettings>'
    + '<dpiAware>true/PM</dpiAware></asmv3:windowsSettings></assembly>'

test('launcher UTF-8 opt-in preserves settings and is idempotent', () => {
    const updated = utf8Manifest(manifest)
    assert.ok(updated.includes('<trustInfo>keep privileges</trustInfo>'))
    assert.ok(updated.includes('<dpiAware>true/PM</dpiAware>'))
    assert.ok(updated.includes('>UTF-8</activeCodePage>'))
    assert.equal(utf8Manifest(updated), updated)
    assert.equal(utf8Manifest(updated.replace('>UTF-8<', '>en-US<')), updated)
})

test('unsupported or ambiguous launcher manifests fail packaging', () => {
    assert.throws(() => utf8Manifest('<assembly/>'), /one Windows settings section/)
    assert.throws(() => utf8Manifest('<activeCodePage>en-US</activeCodePage>'.repeat(2)), /ambiguous/)
})

test('PE update preserves unrelated resources and rejects a missing manifest', async () => {
    const pe = await require('resedit/cjs').load()
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'java-manifest-'))
    const file = path.join(directory, 'java.exe')
    try {
        const executable = pe.NtExecutable.createEmpty(false, false)
        fs.writeFileSync(file, Buffer.from(executable.generate()))
        await assert.rejects(configureJavaLauncher(file), /one application manifest/)
        const resources = pe.NtExecutableResource.from(executable)
        resources.entries.push({ type: 24, id: 1, lang: 1033, codepage: 0, bin: Buffer.from(manifest) })
        resources.entries.push({ type: 10, id: 7, lang: 1033, codepage: 0, bin: Buffer.from('preserved') })
        resources.outputResource(executable)
        fs.writeFileSync(file, Buffer.from(executable.generate()))
        await configureJavaLauncher(file)
        const updated = fs.readFileSync(file)
        const entries = pe.NtExecutableResource.from(pe.NtExecutable.from(updated)).entries
        assert.equal(Buffer.from(entries.find((entry) => entry.type === 24).bin).toString(), utf8Manifest(manifest))
        assert.equal(Buffer.from(entries.find((entry) => entry.type === 10).bin).toString(), 'preserved')
        await configureJavaLauncher(file)
        assert.deepEqual(fs.readFileSync(file), updated)
    } finally { fs.rmSync(directory, { recursive: true }) } // Only this test's mkdtemp directory.
})

test('smoke readiness waits only while startup is live and rejects failure even after a ready log', () => {
    const running = { exitCode: null, signalCode: null }
    const ready = '[backend] Ready at http://127.0.0.1:1234'
    assert.equal(backendReady('', running, 'test'), undefined)
    assert.equal(backendReady(ready, running, 'test'), 'http://127.0.0.1:1234')
    assert.throws(() => backendReady(ready + '\nSMOKE_DIALOG startup failed: code=2', running, 'test'), /startup failed: code=2/)
    assert.throws(() => backendReady('', { exitCode: 2, signalCode: null }, 'test'), /code=2/)
    assert.throws(() => backendReady('', { exitCode: null, signalCode: 'SIGTERM' }, 'test'), /SIGTERM/)
})
