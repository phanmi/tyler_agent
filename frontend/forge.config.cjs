// Electron Forge 配置（Phase 7 打 package，Phase 8 补 Squirrel.Windows 安装器）。
module.exports = {
  packagerConfig: {
    name: 'Tyler',
    executableName: 'Tyler',
    asar: true,
    // extraResource 里的内容会被复制到 process.resourcesPath（asar 之外）。
    // - 目录：Electron Packager 会递归复制；main.cjs 里做了「保留目录名/展开」两种形态的探测，
    //   因此无论最终落盘成 resources/runtime/ 还是 resources/，java.exe 都能被找到。
    // - 单文件：保留文件名，落到 resources/tyler-agent-0.1.0.jar。
    extraResource: [
      '../resources/runtime',
      '../target/tyler-agent-0.1.0.jar',
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
