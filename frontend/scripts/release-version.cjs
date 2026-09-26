const fs = require('node:fs')
const path = require('node:path')

const frontendRoot = path.join(__dirname, '..')
const defaultPaths = {
    config: path.join(frontendRoot, '..', '.mvn', 'maven.config'),
    packageJson: path.join(frontendRoot, 'package.json'),
    packageLock: path.join(frontendRoot, 'package-lock.json'),
}

const releasePattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/

function isValidReleaseVersion(version) {
    const match = releasePattern.exec(version)
    return Boolean(match) && (!match[4] || match[4].split('.').every(
        (part) => !/^\d+$/.test(part) || part === '0' || !part.startsWith('0')))
}

/** Read the one release revision supplied to Maven by .mvn/maven.config. */
function readReleaseVersion(configPath = defaultPaths.config) {
    const lines = fs.readFileSync(configPath, 'utf8').split(/\r?\n/)
    const revisions = lines.map((line) => /^-Drevision=(.*)$/.exec(line.trim()))
        .filter(Boolean)
    if (revisions.length !== 1 || !isValidReleaseVersion(revisions[0][1])) {
        throw new Error(`${configPath} must contain exactly one valid -Drevision=X.Y.Z line`)
    }
    return revisions[0][1]
}

function readJson(filePath) {
    const original = fs.readFileSync(filePath, 'utf8')
    const json = JSON.parse(original)
    const eol = original.includes('\r\n') ? '\r\n' : '\n'
    return { original, json, eol }
}

function writeJson(filePath, content) {
    const { original, json, eol } = content
    const updated = JSON.stringify(json, null, 2).replace(/\n/g, eol) + eol
    if (updated !== original) fs.writeFileSync(filePath, updated)
}

/** Keep npm metadata in sync with the Maven release revision. */
function syncFrontendVersion(paths = defaultPaths) {
    const version = readReleaseVersion(paths.config)
    const packageJson = readJson(paths.packageJson)
    const packageLock = readJson(paths.packageLock)
    if (!packageLock.json.packages?.['']) {
        throw new Error(`${paths.packageLock} is missing the root package entry`)
    }
    packageJson.json.version = version
    packageLock.json.version = version
    packageLock.json.packages[''].version = version
    writeJson(paths.packageJson, packageJson)
    writeJson(paths.packageLock, packageLock)
    return version
}

module.exports = { readReleaseVersion, syncFrontendVersion }
