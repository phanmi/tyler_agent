const { syncFrontendVersion } = require('./release-version.cjs')

try {
    const version = syncFrontendVersion()
    process.stdout.write(`Release version: ${version}\n`)
} catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    process.exitCode = 1
}
