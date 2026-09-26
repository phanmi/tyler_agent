const fs = require('node:fs')
const path = require('node:path')
const { resolveJavaPath, resolveJarPath, startBackendProcess } = require('../electron/backend-process.cjs')

async function main() {
    const previewMode = process.argv.includes('--preview')
    const jarPath = resolveJarPath()
    if (!fs.existsSync(jarPath)) {
        throw new Error(`Backend JAR not found: ${jarPath}\nRun mvn package in the project root first`)
    }

    let started = null
    let vite = null
    let stopping = false
    async function stop(exitCode = 0) {
        if (stopping) return
        stopping = true
        started?.child.kill()
        if (vite) await vite.close()
        process.exitCode = exitCode
    }

    process.on('SIGINT', () => { void stop() })
    process.on('SIGTERM', () => { void stop() })
    try {
        started = await startBackendProcess(resolveJavaPath(),
            ['-jar', jarPath, '--server.address=127.0.0.1', '--server.port=0'], {
                onStdout: (data) => process.stdout.write(`[backend] ${data}`),
                onStderr: (data) => process.stderr.write(`[backend] ${data}`),
                onExit: (code, signal) => {
                    process.stderr.write(`Backend exited: code=${code} signal=${signal}\n`)
                    void stop(1)
                },
            })
        if (started.child.exitCode !== null) throw new Error('Backend exited immediately after startup')

        process.env.TYLER_BACKEND_URL = started.baseUrl
        process.stdout.write(`Backend ready at ${started.baseUrl}\n`)

        const { createServer, preview } = await import('vite')
        const config = { configFile: path.join(__dirname, '..', 'vite.config.ts') }
        if (previewMode) {
            vite = await preview(config)
        } else {
            vite = await createServer(config)
            await vite.listen()
        }
        vite.printUrls()
    } catch (error) {
        await stop(1)
        throw error
    }
}

main().catch((error) => {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`)
    process.exitCode = 1
})
