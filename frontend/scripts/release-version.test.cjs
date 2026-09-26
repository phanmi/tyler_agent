const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')
const { readReleaseVersion, syncFrontendVersion } = require('./release-version.cjs')

function fixture(t, revision = '1.2.3-rc.1') {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'tyler-version-test-'))
    t.after(() => {
        const resolved = fs.realpathSync(directory)
        const tempRoot = fs.realpathSync(os.tmpdir())
        if (!resolved.startsWith(tempRoot + path.sep)) throw new Error('Unsafe test cleanup path')
        fs.rmSync(resolved, { recursive: true, force: true })
    })
    const paths = {
        config: path.join(directory, 'maven.config'),
        packageJson: path.join(directory, 'package.json'),
        packageLock: path.join(directory, 'package-lock.json'),
    }
    fs.writeFileSync(paths.config, `-Drevision=${revision}\n`)
    fs.writeFileSync(paths.packageJson, '{\r\n  "name": "tyler",\r\n  "version": "0.1.0"\r\n}\r\n')
    fs.writeFileSync(paths.packageLock,
        '{\r\n  "version": "0.1.0",\r\n  "packages": {\r\n    "": {\r\n      "version": "0.0.1"\r\n    },\r\n    "node_modules/example": {\r\n      "version": "9.8.7"\r\n    }\r\n  }\r\n}\r\n')
    return paths
}

test('synchronizes both npm version fields and preserves dependency versions', (t) => {
    const paths = fixture(t)
    assert.equal(syncFrontendVersion(paths), '1.2.3-rc.1')
    assert.equal(JSON.parse(fs.readFileSync(paths.packageJson)).version, '1.2.3-rc.1')
    const lock = JSON.parse(fs.readFileSync(paths.packageLock))
    assert.equal(lock.version, '1.2.3-rc.1')
    assert.equal(lock.packages[''].version, '1.2.3-rc.1')
    assert.equal(lock.packages['node_modules/example'].version, '9.8.7')
    assert.match(fs.readFileSync(paths.packageLock, 'utf8'), /\r\n/)

    const firstResult = fs.readFileSync(paths.packageLock, 'utf8')
    syncFrontendVersion(paths)
    assert.equal(fs.readFileSync(paths.packageLock, 'utf8'), firstResult)
})

test('rejects invalid or duplicate release revisions', (t) => {
    const paths = fixture(t, 'next')
    assert.throws(() => readReleaseVersion(paths.config), /one valid/)
    fs.writeFileSync(paths.config, '-Drevision=1.2.3\n-Drevision=2.0.0\n')
    assert.throws(() => readReleaseVersion(paths.config), /exactly one/)
    fs.writeFileSync(paths.config, '-Drevision=1.2.3-01\n')
    assert.throws(() => readReleaseVersion(paths.config), /one valid/)
})

test('does not modify package.json when the lockfile is malformed', (t) => {
    const paths = fixture(t)
    const original = fs.readFileSync(paths.packageJson, 'utf8')
    fs.writeFileSync(paths.packageLock, '{"version":"0.1.0","packages":{}}\n')
    assert.throws(() => syncFrontendVersion(paths), /root package entry/)
    assert.equal(fs.readFileSync(paths.packageJson, 'utf8'), original)
})
