// Windows-only regression: force the failing legacy code page without changing the machine locale.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { configureJavaLauncher } = require('../../scripts/java-launcher.cjs')

function copyTree(source, destination) {
    fs.mkdirSync(destination, { recursive: true })
    for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
        const from = path.join(source, entry.name), to = path.join(destination, entry.name)
        if (entry.isDirectory()) copyTree(from, to)
        else if (entry.isFile()) fs.copyFileSync(from, to)
        else throw new Error(`Unexpected runtime entry: ${entry.name}`)
    }
}

async function run() {
    assert.equal(process.platform, 'win32')
    const root = fs.mkdtempSync(path.resolve('../target/java-runtime-'))
    const runtime = path.join(root, 'Java space 中文')
    copyTree('out/Tyler-win32-x64/resources/runtime', runtime)
    const launcher = path.join(runtime, 'bin/java.exe')
    const launch = () => spawnSync(launcher, ['-XshowSettings:properties', '-version'], {
        encoding: 'utf8', windowsHide: true, timeout: 10000,
        env: { SystemRoot: process.env.SystemRoot, TEMP: root, TMP: root, PATH: '' },
    })
    // Change only the disposable copy to reproduce Windows-1252 on UTF-8 developer machines too.
    const pe = await require('resedit/cjs').load()
    const executable = pe.NtExecutable.from(fs.readFileSync(launcher))
    const resources = pe.NtExecutableResource.from(executable)
    const manifest = resources.entries.find((entry) => entry.type === 24 && entry.id === 1)
    const xml = Buffer.from(manifest.bin).toString('utf8')
    assert.ok(xml.includes('>UTF-8</activeCodePage>'))
    manifest.bin = Buffer.from(xml.replace('>UTF-8</activeCodePage>', '>en-US</activeCodePage>'))
    resources.outputResource(executable)
    fs.writeFileSync(launcher, Buffer.from(executable.generate()))
    const legacy = launch()
    assert.equal(legacy.status, 2)
    assert.match(legacy.stderr, /could not find java.dll/)
    await configureJavaLauncher(launcher)
    const unicode = launch()
    assert.equal(unicode.status, 0, unicode.stderr || unicode.error?.message)
    assert.match(unicode.stderr, /native.encoding = UTF-8/)
    fs.writeFileSync(path.join(root, 'result.json'), JSON.stringify({
        legacyExit: legacy.status, utf8Exit: unicode.status, nativeEncoding: 'UTF-8',
    }, null, 2))
    console.log(`PASS legacy failure and UTF-8 launch from Unicode path: ${root}`)
}

run().catch((error) => { console.error(error); process.exitCode = 1 })
