# Tyler Agent

Desktop assistant with an Electron/React frontend (`frontend/`), a Java 26/Spring Boot backend (`src/`), and a Python agent worker (`agent-python/`).

```text
Java HTTP controller -> Java service interface -> Java DAO interface -> storage
Python agent tool -> operation protocol -> Java bridge -> Java service interface
```

All agent logic (providers, prompts, the tool loop, tool schemas) is Python. Java owns HTTP, worker supervision, domain rules, persistence, and sandboxed file access. Python reaches data and files only through the Java bridge.

## Read before changing code

| Working on | Read |
|---|---|
| Java | `docs/BACKEND_STANDARDS.md` |
| Python agent or tools | `docs/PYTHON_AGENT_STANDARDS.md` and `docs/BACKEND_STANDARDS.md` |
| React, CSS, Electron | `docs/FRONTEND_STANDARDS.md` |
| Worker protocol | `docs/AGENT_PROTOCOL.md` and `contracts/agent/` |
| Tools, provider behavior, limits | `docs/AGENT_OPERATIONS.md` |
| File access | `docs/FILE_SANDBOX.md` |
| Packaging or desktop startup | `docs/DESKTOP_PACKAGING.md` |

`docs/DEVELOPMENT.md` covers setup, architecture, and configuration. `docs/AGENT_MIGRATION.md` is a 1,000-line record, so search it rather than reading it whole.

## Before writing code

1. Read the issue's acceptance criteria. If they conflict or leave a decision open, list the question and ask. Once it's answered, suggest wording to add to the issue.
2. If the change adds a layer, registry, dispatcher, protocol message, or new class structure, present a short design and wait for approval. Say what would have to change to add the 50th tool or service. Small fixes don't need this.

## Maintainability rules

The maintainer rejects these in review even when the code works:

- **Long functions and long `if` chains.** Break them into named functions with one job each. Put validation in a validator class or module.
- **Shared code that grows with every feature.** Adding a tool, service, or operation must not add a field, a `switch` case, or an entry to a shared file. Give each its own handler or package and register it.
- **Overdesign.** Don't add handling for input that won't realistically occur. If you think a case needs it, say how it would happen. Validation required by the standards or a wire contract still applies.
- **Unnamed values.** Error codes, limits, and timeouts are named constants.
- **Repeated code.** Reuse the existing helper or extract one instead of copying logic between classes.
- **Vague errors.** Say what is wrong instead of a generic `INVALID FRAME`, without including sensitive data.
- **Undocumented state.** A stateful class needs a comment explaining its design and how its state changes.

## Checks

Run the checks for every area you touched before saying work is done or opening a PR. They match the required CI job. Changes to `contracts/agent/`, the worker protocol, or tool schemas need both the Python and Java checks.

```powershell
# Python, from agent-python/
uv sync --locked --group dev
uv run --locked ruff format --check src tests evals
uv run --locked ruff check src tests evals
uv run --locked mypy --platform linux
uv run --locked mypy --platform win32
uv run --locked pytest tests

# Java, from the repository root (use `mvn clean test` after moving or deleting classes)
mvn test

# Frontend, from frontend/ (runs format, lint, typecheck, UI and script tests, and build)
npm run check
```

For a single test, use `uv run --locked pytest tests/test_tools.py -k name` or `mvn "-Dtest=ClassName" test`.

Before opening a PR, run `/code-review` on the branch and resolve what it finds.

## Tests

- Show that each new regression test catches the bug: remove the fix, confirm the test fails, restore the fix, and report that you did this.
- Exercise the real path. Trigger the exception production actually receives and send contract tests through the real boundary instead of injecting a convenient stand-in.
- Give each new Java class that contains logic its own test class.

## Documentation

When behavior changes, search `docs/` and the READMEs for the old behavior and update what you find. Outside the migration execution record, don't write point-in-time status such as "current head", a commit SHA, or "uncommitted".

## Review findings

- Check each finding against the code and say how it could actually happen. Reviewers, including AI ones, are sometimes wrong.
- Give each finding a decision: fix now, won't fix (with the reason), or follow-up.
- Offer to open an issue for each follow-up, because a PR comment alone gets lost.

## Commits and PRs

- Branch from `main` as `issue#<number>`.
- Keep commit subjects under 70 characters so GitHub doesn't cut off the PR title, and say what changed. "fixes some problems" tells a later reader nothing.
- A PR title describes the change and is never the branch name.
- Fill in `.github/pull_request_template.md`. Under Verification, list the commands you ran, their results, and what wasn't tested. Tick only the boxes you checked.

## Environment

- Development is on Windows and the required CI job runs on Ubuntu, so guard OS-specific APIs with `sys.platform`.
- Run Python only through `uv run --locked`. A bare `python` can resolve to the Windows Store stub.
- Java integration tests start the worker from `agent-python/.venv`. A `WORKER_UNAVAILABLE` failure is real, not flaky: check that `.venv/pyvenv.cfg` points to an interpreter that exists.
- CI installs with npm 10. On Node 24, change dependencies with `npx npm@10 install ...`, or `npm ci` will reject the lockfile.
