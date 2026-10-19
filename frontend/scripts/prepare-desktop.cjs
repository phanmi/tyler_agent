const fs = require('node:fs')
const path = require('node:path')
const { createHash } = require('node:crypto')
const { execFileSync } = require('node:child_process')
const { readReleaseVersion } = require('./release-version.cjs')
const { configureJavaLauncher } = require('./java-launcher.cjs')

const root = path.resolve(__dirname, '../..')
const hash = (file) => createHash('sha256').update(fs.readFileSync(file)).digest('hex')

/** Build-time only: no download, dependency installation, or host-runtime fallback at launch. */
function resourcePaths() {
    if (!process.env.JAVA_HOME) throw new Error('Set JAVA_HOME to the Java 26 build JDK')
    const jdk = path.resolve(process.env.JAVA_HOME)
    const release = fs.readFileSync(path.join(jdk, 'release'), 'utf8')
    if (!/^JAVA_VERSION="26(?:\.|\")/m.test(release)) throw new Error('Desktop packaging requires Java 26')
    const cacheKey = createHash('sha256').update(release + ':desktop-runtime-v2-utf8').digest('hex').slice(0, 16)
    return {
        jdk,
        runtime: path.join(root, 'target/desktop', cacheKey, 'runtime'),
        jar: path.join(root, 'target', `tyler-agent-${readReleaseVersion()}.jar`),
        worker: path.join(root, 'agent-python/dist/tyler-agent-worker'),
        manifest: path.join(root, 'target/desktop/desktop-build.json'),
    }
}

async function prepareDesktop() {
    if (process.platform !== 'win32' || process.arch !== 'x64') throw new Error('Build the desktop artifact on Windows x64')
    const resources = resourcePaths()
    for (const file of [resources.jar, path.join(resources.worker, 'tyler-agent-worker.exe')]) {
        if (!fs.existsSync(file)) throw new Error(`Build required artifact first: ${file}`)
    }
    const workerVersion = readReleaseVersion(path.join(resources.worker, '_internal/.mvn/maven.config'))
    if (workerVersion !== readReleaseVersion()) throw new Error('Rebuild the Python worker for the current release version')
    if (!fs.existsSync(path.join(resources.runtime, 'bin/java.exe'))) {
        fs.mkdirSync(path.dirname(resources.runtime), { recursive: true })
        execFileSync(path.join(resources.jdk, 'bin/jlink.exe'), [
            '--add-modules', 'java.se,jdk.crypto.ec,jdk.crypto.mscapi,jdk.unsupported,jdk.zipfs',
            '--strip-debug', '--no-header-files', '--no-man-pages', '--output', resources.runtime,
        ], { stdio: 'inherit', windowsHide: true })
    }
    // Java's native launcher uses ANSI APIs before JVM flags can affect encoding.
    const javaLauncher = path.join(resources.runtime, 'bin/java.exe')
    await configureJavaLauncher(javaLauncher)
    fs.writeFileSync(resources.manifest, JSON.stringify({
        releaseVersion: readReleaseVersion(), platform: process.platform, arch: process.arch,
        javaRelease: fs.readFileSync(path.join(resources.runtime, 'release'), 'utf8'),
        javaLauncherCodePage: 'UTF-8', javaLauncherSha256: hash(javaLauncher),
        jarSha256: hash(resources.jar), workerSha256: hash(path.join(resources.worker, 'tyler-agent-worker.exe')),
    }, null, 2) + '\n')
    return resources
}

if (require.main === module) prepareDesktop().catch((error) => { console.error(error); process.exitCode = 1 })
module.exports = { resourcePaths, prepareDesktop }
