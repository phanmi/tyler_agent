# Python agent worker prototype

[Migration tracker](../docs/AGENT_MIGRATION.md) | [Protocol contract](../docs/AGENT_PROTOCOL.md) | [Backend standards](../docs/BACKEND_STANDARDS.md) | [Python agent standards](../docs/PYTHON_AGENT_STANDARDS.md)

Step 4 provides a credential-free worker, strict protocol codecs, conformance tests, and a Windows packaging prototype. The application still uses the Java agent. Java process supervision, service handlers, and the Python model/tool loop are later migration steps.

## Install the development environment

Use **uv 0.13.1**. If Python is already available, install that exact version with:

```powershell
python -m pip install --user uv==0.13.1
```

Alternatively use the [official uv installation instructions](https://docs.astral.sh/uv/getting-started/installation/) for a standalone installation, selecting version `0.13.1`. Open a new terminal if the installer updates `PATH`.

From the repository root:

```powershell
cd agent-python
uv sync --locked
```

uv downloads **CPython 3.12.14** if needed and creates `.venv`. `.python-version` and `requires-python` pin that runtime; `uv.lock` records resolved packages and distribution hashes. Do not hand-edit the lockfile. Python installation and dependency downloads are development/build operations; the packaged worker performs neither at startup.

| Dependency | Pin | Purpose |
|---|---|---|
| `openai` | 3.31.0 | [Official Python SDK](https://developers.openai.com/api/docs/libraries), reserved for the later model loop; the prototype never creates a client. |
| `jsonschema` | 4.26.0 | Draft 2020-12 schema checks without coercion or defaults. |
| `pytest` | 9.1.1 | Unit and subprocess tests. |
| `ruff` | 0.17.1 | Formatting, imports, and linting. |
| `mypy` | 2.4.0 | Strict type checking of source and tests. |
| `types-jsonschema` | 4.26.0.20261006 | Validator type information. |
| `pyinstaller` | 6.22.3 | Standalone Windows runtime bundle. |
| `hatchling` | 1.27.0 | Editable project build and shared release-version metadata. |

The release version comes from `../.mvn/maven.config`. Hatch reads it for package metadata; the development worker reads it at startup, and packaging copies that same file into the bundle. After changing the global release version, run `uv lock`, `uv sync --locked`, and rebuild the bundle so generated metadata and resources agree. The protocol compatibility version remains `1`.

## Required checks

Run from `agent-python/`:

```powershell
uv run --locked ruff format --check src tests
uv run --locked ruff check src tests
uv run --locked mypy
uv run --locked pytest tests
```

Use `uv run --locked ruff format src tests` to apply formatting. Production modules live in `src/tyler_agent/`; all Python tests and test helpers live in `tests/`. Cross-language fixtures stay in the single shared `../contracts/agent/v1/fixtures/` directory and are loaded by both languages. Never copy fixtures into separate Java/Python versions.

From the repository root, run the Java conformance/error-mapping tests and the full backend suite:

```powershell
mvn "-Dtest=ProtocolContractTest,ProtocolFailuresTest" test
mvn test
```

The [migration tracker](../docs/AGENT_MIGRATION.md#execution-record) records local Windows Maven/Mockito runner options. No API key, network model call, application database, or user workspace is required by these tests.

## Worker behavior

The parent sets a nonsecret `TYLER_AGENT_SESSION_ID` containing 32 lowercase hexadecimal characters. Start with `uv run --locked python -m tyler_agent`. The worker immediately emits a versioned `ready` frame on stdout, without credentials. It reads newline-delimited JSON from stdin, writes only protocol messages to stdout, and logs safe codes/events to stderr.

- `shutdown` exits successfully; EOF also exits, except an incomplete frame is an error.
- A valid `run` returns `failed` / `WORKER_UNAVAILABLE`, stating that execution is not implemented. It never claims a chat succeeded or any write occurred.
- Invalid framing, versions, shapes, session IDs, directions, and correlation are rejected. Exit code `2` indicates a protocol/resource failure; code `0` indicates normal shutdown/EOF.
- The pure session observers exercise sequencing and effect-ledger rules using local test events. They perform no service/history writes and are not a process supervisor. The future Java adapter must supply actual service outcomes, timeouts, and history completion.

Use the subprocess tests for a scripted readiness/shutdown exchange; typing raw frames into a terminal is unnecessary.

## Build and smoke-test the Windows bundle

Build on Windows x64 from `agent-python/`:

```powershell
uv run --locked pyinstaller --noconfirm --distpath dist --workpath build worker.spec
uv run --locked pytest tests --worker-executable dist/tyler-agent-worker/tyler-agent-worker.exe
```

Distribute the **entire** `dist/tyler-agent-worker/` directory. Its executable, `_internal/python312.dll`, imported dependencies, schema, and release-version file are required. This is PyInstaller's [one-folder bundle](https://pyinstaller.org/en/stable/operating-mode.html), not an installer. `console=True` preserves redirected stdin/stdout/stderr; the future Java launcher must use private pipes and appropriate Windows process creation settings.

The packaged smoke tests copy the bundle into a different temporary directory, run with an empty `PATH`, omit `PYTHONHOME`/`PYTHONPATH` and credentials, and verify readiness, shutdown, EOF, malformed input, and explicit unavailable-run failure. Both the development module and relocated executable run the same smoke cases. Tests have bounded waits and terminate leftover child processes in cleanup.

This proves the prototype uses its bundled runtime on the tested Windows host. Clean-machine installation, signing, Electron integration, parent-death handling, active-provider cancellation, and release CI belong to later migration gates.

## Reproduce an isolated install from the lockfile

To check a new environment without replacing `.venv`, from `agent-python/`:

```powershell
$env:UV_PROJECT_ENVIRONMENT = Join-Path (Resolve-Path '..').Path 'target/agent-fresh-env'
uv sync --locked
uv run --locked pytest tests/test_worker.py
Remove-Item Env:UV_PROJECT_ENVIRONMENT
```

With a populated uv cache and the pinned interpreter already available, `uv sync --locked --offline` verifies that no dependency resolution/download is needed. An initially empty cache requires download access.

In restricted environments, use workspace-local `UV_CACHE_DIR` and `UV_PYTHON_INSTALL_DIR`, and pass a dedicated disposable directory such as `--basetemp ../target/pytest-step4` to pytest. pytest clears its `--basetemp` directory; never point it at source files or user data.
