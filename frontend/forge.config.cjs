// Electron Forge configuration: application packaging and the Squirrel.Windows installer.
module.exports = {
  packagerConfig: {
    name: 'Tyler',
    executableName: 'Tyler',
    asar: true,
    // extraResource entries are copied to process.resourcesPath outside the ASAR archive.
    // Directories are copied recursively; main.cjs checks both preserved and flattened layouts,
    // so java.exe can be found under resources/runtime/ or resources/.
    // Individual files retain their names, including resources/tyler-agent-0.2.0.jar.
    extraResource: [
      '../resources/runtime',
      '../target/tyler-agent-0.2.0.jar',
    ],
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
