# Windows desktop packaging and offline acceptance

[Developer guide](DEVELOPMENT.md) | [Migration tracker](AGENT_MIGRATION.md) | [Python worker](../agent-python/README.md)

## Run the prepared offline bundle

The handoff contains the application, its Java and Python runtimes, a separate test worker, an installer, checksums, and a test runner. No installed Java, Python, Node.js, npm, or internet connection is needed to run it. Use a disposable **Windows x64** environment with those development runtimes absent. Allow at least 4 GB of free disk space for the bundle and test copies.

1. Extract the entire ZIP into a local folder. Keep `inputs/` intact.
2. Choose one execution method:
   - **Windows Sandbox:** on a host with Windows Sandbox enabled, run `powershell -NoProfile -ExecutionPolicy Bypass -File .\Start-Sandbox.ps1` from the extracted folder. It regenerates mappings for the extraction location, disables networking, maps inputs read-only, and starts the tests automatically. Only the bundle's `results/` folder is writable from the Sandbox.
   - **Clean Windows VM:** copy the extracted folder into the VM, disconnect its network, and double-click `inputs\run.cmd`. Do not run the application on the host as a substitute for the clean-machine check.
3. Wait for `results/exit-code.txt` to appear; a normal run takes a few minutes. Brief test windows may appear. Do not close Sandbox during the run. A stalled run or absent exit file is not a pass.
4. Require exit code **0**, and `results/desktop-smoke-*/report.json` listing **all 10 scenarios** below. Preserve `smoke.log`, the report, and each scenario's `desktop.log`. A partially written report is not success.
5. Record the Windows edition/build, VM/Sandbox configuration, that Java/Python/Node were not installed, that networking was disabled, and the report's artifact hashes. Send that evidence back to finish the migration acceptance record.

The runner uses the bundled Electron executable in Node mode. This setting is removed from every application subprocess. It copies the app before injecting failure fixtures; it does not modify the supplied artifact or use your existing application data. Tests create synthetic settings and temporary databases and make no model-provider calls. `SHA256SUMS.txt` identifies every supplied input file; `Get-FileHash -Algorithm SHA256 <file>` can check an individual file.

### Installer check on the clean machine

The automated suite exercises the packaged application and Squirrel's obsolete event. It does **not** prove installer installation, upgrade, or uninstallation. Inside the disposable environment, run `inputs/installer/Tyler-<version> Setup.exe`, launch the installed app and confirm the window opens, then quit and uninstall through Windows Settings. Record any publisher warning, startup failure, orphaned Java/Python process, or uninstall failure. Python is the default from Step 10; leave `AGENT_IMPLEMENTATION` unset to verify it. See the [rollout and recovery guide](AGENT_ROLLOUT.md) for temporary Java rollback. Upgrade behavior requires a previous installer and a separate recorded exercise.

The installer is currently unsigned. Do not enter real API keys or copy user data into this test environment. Keep the environment open until you have saved the results; the generated Sandbox mappings preserve automated results on the host.

## Build from source

Build on Windows x64 using Java **26** (`JAVA_HOME` points to the JDK), Maven, Node **22**/npm **10**, and uv **0.13.1**. The pinned Python version and dependency hashes are in `agent-python/.python-version` and `uv.lock`; npm dependencies come from `frontend/package-lock.json`. See [prerequisite installation](SETUP.md) and the [Python setup guide](../agent-python/README.md). Downloads happen during setup/build, never application startup.

Run each command successfully before continuing. From the repository root:

```powershell
uv sync --project agent-python --locked --group dev
cd agent-python
uv run --locked ruff format --check src tests
uv run --locked ruff check src tests
uv run --locked mypy --platform linux
uv run --locked mypy --platform win32
uv run --locked pyinstaller --noconfirm --distpath dist --workpath build worker.spec
uv run --locked pyinstaller --noconfirm --distpath dist --workpath build-desktop-test tests/desktop-worker.spec
uv run --locked pytest tests --worker-executable dist/tyler-agent-worker/tyler-agent-worker.exe
cd ..
mvn --batch-mode clean verify
cd frontend
npm ci
npm run test:startup
npm run test:version
npm run typecheck
npm run test:ui
npm run build
npm run make
npm run test:runtime
npm run test:desktop
cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File frontend/tests/electron/prepare-sandbox.ps1
```

`prepare-sandbox.ps1` writes `target/clean-windows/` and refuses to replace existing evidence. Pass another checkout-relative `-OutputRoot` to prepare a new handoff. Zip the complete directory for transfer. The Sandbox launcher regenerates absolute mappings after extraction. On a VM, `inputs/run.cmd` writes to the neighboring `results/` directory.

### Artifacts and version ownership

`.mvn/maven.config` is the release-version source. After changing it, rebuild the backend and worker, update Python metadata using the documented lock/sync commands, and synchronize frontend metadata. Forge rejects a worker with a different release version. Protocol versioning is independent.

Forge's `prePackage` hook runs `scripts/prepare-desktop.cjs`: it builds a Java runtime with `jlink`, checks the JAR and worker, and writes `desktop-build.json` with runtime identity and artifact hashes. Runtime caching is keyed by the JDK release metadata and module configuration. This is a repeatable build procedure; byte-identical signed/installable artifacts across machines are not claimed.

The generated runtime's `java.exe` manifest opts into the [Windows UTF-8 process code page](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page). Java's native launcher resolves its DLLs before JVM encoding options apply; without this setting, a path such as `Tyler space 中文` fails on Windows-1252 hosts with `could not find java.dll`. The build preserves the existing privilege, DPI, and compatibility settings, changes only the generated runtime copy, invalidates the old runtime cache, and records the launcher hash/code page in the build manifest. The installed build JDK is untouched.

`resedit` is a pinned build dependency already used by Electron's packaging tooling. Updating the generated launcher's resources removes its vendor Authenticode signature; the resulting launcher, like the current installer, is unsigned. Any future signing step must sign the modified launcher after this build step. The UTF-8 manifest requires Windows 10 version 1903 or later. `test:runtime` forces an `en-US` process code page in a disposable launcher copy, verifies the original DLL failure, applies the production fix, and verifies successful UTF-8 startup. That forced-locale regression uses Windows 11/Server 2025 manifest support and runs in the Windows CI job.

```text
frontend/out/Tyler-win32-x64/
  Tyler.exe
  resources/
    app.asar
    runtime/bin/java.exe
    tyler-agent-<version>.jar
    tyler-agent-worker/
      tyler-agent-worker.exe
      _internal/                  # Python, dependencies, schemas, version resource
    desktop-build.json
```

Java and Python resources are outside ASAR. Electron starts bundled Java with the bundled worker path as a single argument; Java owns Python readiness and shutdown. Packaged execution has no fallback to installed Java and ignores the development `BACKEND_JAR_PATH` override. `agent.implementation=python` is the default. The smoke suite leaves the implementation selector unset, so readiness and child-process checks exercise the shipped default. Set `AGENT_IMPLEMENTATION=java` only for restart-based rollback during the release window. The separate `desktop-test-worker` fixture is never included in production resources.

Installer files are under `frontend/out/make/squirrel.windows/x64/`. Implementation references: [Forge hook lifecycle](https://www.electronforge.io/config/hooks), [Forge packager configuration](https://www.electronforge.io/config/configuration), and [Electron main-process debugging](https://www.electronjs.org/docs/latest/tutorial/debugging-main-process). The test enables the inspector only for its own application processes; normal launch does not enable it.

## Automated smoke coverage

All scenarios use a relocated `Tyler space 中文` path, isolated data, an empty runtime `PATH`, and no host Python configuration or provider credentials.

| Scenario | Worker and assertion |
|---|---|
| `production-ready-quit` | Production worker; ready without an API key, history HTTP 200, Java and Python exit after app quit. |
| `forced-java-exit` | Production worker; terminate Java, observe a startup/runtime diagnostic and Python watchdog exit. |
| `missing-worker`, `corrupt-worker` | Missing or invalid executable produces a visible startup error and exits. |
| `incompatible`, `startup_failure`, `timeout` | Separate fixture emits incompatible readiness, exits early, or never becomes ready; startup fails and children exit. |
| `quit-during-startup` | Fixture waits before readiness; quitting cancels startup and stops both children. |
| `quit-during-active-request` | Fixture uses production protocol/runtime with a fake provider, commits a real sandbox write, then blocks; quitting stops the active request and children. |
| `installer-obsolete` | Maintenance launch exits successfully without starting the backend. Install/update/uninstall dispatch is also unit-tested. |

Before the 10 application scenarios, the smoke runner checks the launcher's hash and runs bundled Java from the relocated Unicode path with a bounded timeout. `runtime-preflight.log` records native-launch failures. Startup dialogs or application exit fail readiness immediately, with the scenario diagnostic path, instead of waiting for the generic readiness timeout. CI preserves the initial report and preflight log even if the first scenario fails.

Logs must contain neither the synthetic credential nor raw protocol frames. The report records Windows build, release/runtime metadata, launcher/JAR/worker hashes, Electron executable hash, ASAR hash, and completed scenarios. Local sanitized-environment success is useful evidence but does not replace the clean-machine run.

## CI and release gates

[CI](../.github/workflows/ci.yml) retains **Build and test** for locked Python checks/tests, full Java verification and cross-process integration, and frontend tests/types/build. **Windows packaged smoke** depends on that job, builds both workers and the installer, runs relocated-worker tests and the desktop smoke, and uploads release artifacts only after success. Failure diagnostics are uploaded separately even when the job fails.

Configure GitHub branch protection/rulesets to require **both job checks** after they have appeared in a workflow run. Editing workflow YAML does not configure merge protection. No remote CI success or repository-rule update is implied by local verification. Require these checks for startup/release changes and retain the clean-machine evidence before Step 9 completion/Step 10 rollout.

## Troubleshooting

| Failure | Action |
|---|---|
| Missing JAR/worker or version mismatch during packaging | Rebuild the required artifacts for the global release version. |
| Missing `JAVA_HOME` or wrong Java version during build | Point `JAVA_HOME` to the Java 26 JDK, including `bin/jlink.exe`. |
| Packaged runtime/worker missing or corrupt | Restore the complete artifact; do not install host runtimes to conceal an incomplete bundle. |
| `could not find java.dll` from a Unicode installation path | Rebuild with the UTF-8 launcher manifest and verify its hash/code page in `desktop-build.json`. Read `runtime-preflight.log`; increasing readiness timeouts cannot fix a launcher that has exited. |
| Worker incompatible, timeout, or startup failure | Check safe lifecycle codes in `desktop.log`; retain the failing scenario's evidence. |
| Clean-machine runner fails | Read `results/smoke.log`; report the failure and partial report rather than marking acceptance complete. |
| Sandbox unavailable | Use a clean offline Windows VM; the launcher does not install or enable Windows features. |

Developer-only outputs, temporary application data, and test workers stay under ignored build/test directories. Keep real credentials, prompts, and user files out of shared diagnostic attachments.
