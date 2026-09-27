# Install prerequisites (Windows)

[Back to the developer guide](DEVELOPMENT.md)

Start here if this is your first Java or React project. These instructions target Windows, which is also the target of the desktop launcher and installer. Use PowerShell for the commands below. You can open it from the Start menu or use IntelliJ IDEA's **Terminal** tab.

## What you need

| Tool | What it does | Version to install |
|---|---|---|
| Java Development Kit (JDK) | Compiles and runs the backend. Includes both `java` and the `javac` compiler. | **JDK 26**, matching `pom.xml`. |
| Apache Maven | Downloads Java libraries, runs tests, and builds the backend JAR. | A stable **Maven 3.9.x** binary distribution. |
| Node.js and npm | Run frontend build tools and install JavaScript packages. npm comes with Node.js. | **Node.js 24 LTS** is a suitable setup choice; the current dependency requires at least 22.12.0. |
| IntelliJ IDEA | Editor for navigating, editing, and debugging Java code. Command-line builds also work without it. | A current release supporting Java 26. |

Install Java, Maven, and Node.js before configuring the project in IntelliJ. The paths below are examples: use the actual folders on your computer.

## 1. Install Java 26

1. Download a **JDK 26** Windows installer from a JDK provider. One option is the [Oracle JDK 26 download page](https://www.oracle.com/java/technologies/javase/jdk26-archive-downloads.html). Choose the Windows x64 installer for an Intel/AMD 64-bit PC and follow the installer prompts. See the [official Windows installation guide](https://docs.oracle.com/en/java/javase/26/install/installation-jdk-microsoft-windows-platforms.html).
2. Note the installed JDK folder, for example `C:\Program Files\Java\jdk-26`. It should contain `bin\java.exe` and `bin\javac.exe`.
3. Open Start, search for **Edit environment variables for your account**, and open it.
4. Under **User variables**, create or update `JAVA_HOME` with the JDK folder path. Do not include `\bin` or surrounding quotes in its value.
5. Edit the user `Path` variable and add `%JAVA_HOME%\bin` as a new entry. Preserve the existing entries.
6. Close and reopen PowerShell so it picks up the changes, then run:

```powershell
java -version
javac -version
$env:JAVA_HOME
```

Both version commands should report **26**. `JAVA_HOME` should show your JDK folder. If an older Java installation takes precedence, use the troubleshooting checks below before continuing.

## 2. Install Maven

1. Open [Apache Maven downloads](https://maven.apache.org/download.cgi) and select the stable Maven 3.9.x **Binary zip archive**. The source archive is for building Maven itself.
2. Extract the ZIP into a permanent directory, for example `C:\Tools`. The resulting folder will be named something like `apache-maven-3.9.x`; use the actual version number from your download.
3. Add that folder's `bin` directory to your user `Path`, using the environment-variable window from the Java steps. An example entry is `C:\Tools\apache-maven-3.9.x\bin`.
4. Reopen PowerShell and run:

```powershell
mvn -version
```

The output should show Maven 3.9.x and **Java version 26**. Maven uses `JAVA_HOME`, so check it if Maven reports a different Java version. You do not need a separate `MAVEN_HOME` variable for these instructions. See [Maven installation instructions](https://maven.apache.org/install).

## 3. Install Node.js and npm

1. Open the [official Node.js download page](https://nodejs.org/en/download) and select **Node.js 24 LTS**, **Windows**, and the installer matching your computer, usually **x64**.
2. Run the `.msi` installer. Keep the npm package manager and **Add to PATH** options enabled.
3. Reopen PowerShell and verify both commands:

```powershell
node --version
npm --version
```

For the recommended setup, Node should report `v24.x.x`; npm should print its own version number. Do not install React, Vite, TypeScript, or Electron globally: the project installs its own versions in the next steps.

## 4. Install IntelliJ IDEA and open the project

1. Download the Windows installer from [JetBrains](https://www.jetbrains.com/idea/download/?os=win) and follow the [installation guide](https://www.jetbrains.com/help/idea/installation-guide.html). Core Java development features are available free of charge.
2. Get a local copy of this repository. On its GitHub page, **Code > Download ZIP** lets you get started without Git; extract the archive before opening it. If you plan to contribute commits, use a Git clone instead.
3. In IntelliJ, choose **Open** and select the repository root: the folder containing `pom.xml`, `src`, and `frontend`. Allow Maven import to complete.
4. Open **File > Project Structure > Project**. Set **SDK** to your installed JDK 26, using **Add SDK > JDK** to select its folder if needed. Set the language level to the SDK default. IntelliJ's bundled runtime runs the editor; the project still needs its own JDK. See [SDK configuration](https://www.jetbrains.com/help/idea/sdk.html).
5. In **Settings > Build, Execution, Deployment > Build Tools > Maven**, select your installed Maven directory as the Maven home. Under **Runner**, select the project JDK for the JRE; under **Importing**, select it for the importer JDK. See [IntelliJ Maven configuration](https://www.jetbrains.com/help/idea/maven-support.html).
6. Open a fresh IntelliJ terminal and run `mvn -version` and `node --version`. They should match the versions verified above. Restart IntelliJ if it was open while you changed environment variables.

## 5. Download the project's dependencies and start it

The **repository root** means the folder containing `pom.xml`. In PowerShell, navigate there using your own path, for example:

```powershell
cd "C:\Projects\tyler_agent"
mvn package
cd frontend
npm ci
npm run dev
```

- `mvn package` downloads backend dependencies, runs backend tests, and produces the JAR in `target/`. Wait for `BUILD SUCCESS` before continuing.
- `npm ci` installs the frontend versions recorded in `package-lock.json`. Run it from `frontend/` and wait for it to finish successfully.
- `npm run dev` launches the backend and frontend together. Open the local URL printed in the terminal; its port may vary. Leave the terminal running and press **Ctrl+C** when you want to stop development.

The first build needs an internet connection and may take several minutes. Maven installs Spring Boot, the OpenAI SDK, and the SQLite driver; npm installs React, Vite, TypeScript, Electron, and the other frontend packages. You do not need a separate SQLite database server. You can start the app and run backend tests without an API key; enter one in **Settings** when you want to use chat.

For a packaged desktop installer, there is an additional Java runtime bundling step in [Release version and packaging](DEVELOPMENT.md#release-version-and-packaging).

## Installation troubleshooting

| Problem | What to do |
|---|---|
| `java`, `javac`, `mvn`, or `node` is not recognized | Check the installation's `bin`/PATH entry, then restart the terminal and IntelliJ. Node's installer normally sets its PATH entry for you. |
| Java or Maven uses an older JDK | Run `where.exe java`, `where.exe javac`, and `mvn -version`. Check `JAVA_HOME` and the order of existing Java entries in your environment variables. The first matching executable on PATH wins. |
| `release version 26 not supported` | Run `mvn -version`; Maven must use JDK 26. Also check the IDE's project SDK and Maven runner/importer settings. |
| PowerShell reports that `npm.ps1` cannot run | Use `npm.cmd --version`, `npm.cmd ci`, and `npm.cmd run dev` in place of `npm`. This uses the installed command wrapper without changing execution policy. |
| Maven cannot find a POM, or npm cannot find `package.json` | Run `Get-Location`. Use Maven from the repository root and npm from its `frontend` folder. |
| Dependency downloads fail | Check the connection and any required organization proxy configuration. Retry the failed command after fixing access; do not disable TLS verification. |
| IntelliJ reports errors while command-line builds pass | Confirm JDK 26 is selected, then reload the Maven project from the Maven tool window and wait for indexing to finish. |

Continue with [Run from source](DEVELOPMENT.md#run-from-source) for browser, desktop, and backend workflows.
