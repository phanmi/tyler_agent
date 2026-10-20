// Stand-in backend: exercise OS path resolution without requiring Java or application data.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const jar = process.argv[process.argv.indexOf('-jar') + 1]
assert.equal(fs.readFileSync(jar, 'utf8'), 'chosen JAR')
const workspace = path.resolve(process.env.AGENT_WORKSPACE_DIR)
assert.equal(fs.readFileSync(path.join(workspace, 'history.json'), 'utf8'), 'existing history')
fs.writeFileSync(path.join(workspace, 'saved.txt'), process.env.AGENT_IMPLEMENTATION)
console.log(`TYLER_READY ${process.env.TYLER_STARTUP_NONCE} 43210`)
setInterval(() => {}, 1000)
