const fs = require('node:fs')

const CODE_PAGE = '<activeCodePage xmlns="http://schemas.microsoft.com/SMI/2019/WindowsSettings">UTF-8</activeCodePage>'

/** Preserve the JDK's privileges, DPI, and compatibility settings while opting into Unicode paths. */
function utf8Manifest(xml) {
    const existing = /<(?:[\w.-]+:)?activeCodePage\b[^>]*>[\s\S]*?<\/(?:[\w.-]+:)?activeCodePage>/g
    if (xml.match(existing)?.length > 1) throw new Error('Java launcher has ambiguous code-page settings')
    if (existing.test(xml)) return xml.replace(existing, CODE_PAGE)
    const settingsEnd = /<\/(?:[\w.-]+:)?windowsSettings>/g
    if (xml.match(settingsEnd)?.length !== 1) throw new Error('Java launcher must contain one Windows settings section')
    return xml.replace(settingsEnd, `${CODE_PAGE}$&`)
}

/** Modify only the generated runtime copy; resource changes remove its vendor Authenticode signature. */
async function configureJavaLauncher(file) {
    const pe = await require('resedit/cjs').load()
    const executable = pe.NtExecutable.from(fs.readFileSync(file), { ignoreCert: true })
    const resources = pe.NtExecutableResource.from(executable)
    const manifests = resources.entries.filter((entry) => entry.type === 24 && entry.id === 1)
    if (manifests.length !== 1) throw new Error('Java launcher must contain one application manifest')
    const manifest = manifests[0]
    const original = Buffer.from(manifest.bin).toString('utf8')
    const updated = utf8Manifest(original)
    if (updated === original) return
    manifest.bin = Buffer.from(updated, 'utf8')
    resources.outputResource(executable)
    fs.writeFileSync(file, Buffer.from(executable.generate()))
}

module.exports = { configureJavaLauncher, utf8Manifest }
