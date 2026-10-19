// Electron Forge configuration: application packaging and the Squirrel.Windows installer.
const { prepareDesktop } = require('./scripts/prepare-desktop.cjs')

module.exports = {
  packagerConfig: {
    name: 'Tyler',
    executableName: 'Tyler',
    asar: true,
    // Executables and native libraries must remain ordinary files outside ASAR.
    ignore: [/^\/(tests|src|scripts|out2)(\/|$)/, /^\/.*\.(?:ts|tsx|log)$/],
  },
  hooks: {
    prePackage: async (config) => {
      const resources = await prepareDesktop()
      config.packagerConfig.extraResource = [resources.runtime, resources.jar, resources.worker, resources.manifest]
    },
  },
  makers: [
    {
      name: '@electron-forge/maker-squirrel',
      config: {
        name: 'Tyler',
        authors: 'Tyler',
        description: 'Tyler — AI Agent Desktop',
      },
    },
  ],
}
