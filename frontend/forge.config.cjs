// Electron Forge configuration: application packaging and the Squirrel.Windows installer.
const releaseVersion = require('./package.json').version
const jarName = `tyler-agent-${releaseVersion}.jar`

module.exports = {
  packagerConfig: {
    name: 'Tyler',
    executableName: 'Tyler',
    asar: true,
    // extraResource entries are copied to process.resourcesPath outside the ASAR archive.
    // Directories are copied recursively; main.cjs checks both preserved and flattened layouts,
    // so java.exe can be found under resources/runtime/ or resources/.
    // Individual files retain their names, including the versioned backend JAR.
    extraResource: [
      '../resources/runtime',
      `../target/${jarName}`,
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
