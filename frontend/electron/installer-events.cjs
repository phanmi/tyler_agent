const path = require('node:path')
const { spawn } = require('node:child_process')

/** Handle Squirrel maintenance before windows, IPC, Java, or Python are started. */
function handleInstallerEvent(app, args = process.argv, executable = process.execPath, launch = spawn) {
    const event = args[1]
    if (!['--squirrel-install', '--squirrel-updated', '--squirrel-uninstall', '--squirrel-obsolete'].includes(event)) return false
    if (event === '--squirrel-obsolete') {
        app.quit()
        return true
    }
    const updater = path.resolve(path.dirname(executable), '..', 'Update.exe')
    const action = event === '--squirrel-uninstall' ? '--removeShortcut' : '--createShortcut'
    const child = launch(updater, [action, path.basename(executable)], { windowsHide: true })
    child.once('exit', () => app.quit())
    child.once('error', () => app.quit())
    return true
}

module.exports = { handleInstallerEvent }
