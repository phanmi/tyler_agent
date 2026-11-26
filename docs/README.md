# Documentation

[Project README](../README.md) · [Developer guide](guides/DEVELOPMENT.md)

Each document is in the folder for what it is. A new document goes in the folder whose description fits it, and gets a row here.

## Guides

How to set up, run and change the project. Start here.

| Document | What it covers |
|---|---|
| [Developer guide](guides/DEVELOPMENT.md) | Running from source, the architecture, the repository layout, the HTTP API, configuration, testing, release versions and troubleshooting. |
| [Install prerequisites (Windows)](guides/SETUP.md) | Installing the tools the project needs, for a first Java or React project, and getting an API key. |

## Standards

The rules a change has to follow. Read the one for the area you work on before changing code.

| Document | What it covers |
|---|---|
| [Backend standards](standards/BACKEND_STANDARDS.md) | Coding and design rules for the Java backend. |
| [Python agent standards](standards/PYTHON_AGENT_STANDARDS.md) | Coding and design rules for the Python agent worker and its tools (PY-01 onward). |
| [Frontend standards](standards/FRONTEND_STANDARDS.md) | Coding and design rules for React, CSS and Electron (FE-01 onward). |

## Architecture

How the parts work and what they promise each other.

| Document | What it covers |
|---|---|
| [Agent protocol](architecture/AGENT_PROTOCOL.md) | The contract between Java and the Python worker: fields, limits and failure codes. |
| [Agent sessions](architecture/AGENT_SESSION.md) | The session model, its states and message types, and what Java and Python are each responsible for. |
| [Agent operations](architecture/AGENT_OPERATIONS.md) | The operation bridge to Java's services, the tools, provider behavior and limits. |
| [File sandbox](architecture/FILE_SANDBOX.md) | How file access is kept inside the workspace. |

## Deployment

How a build is packaged, released and run.

| Document | What it covers |
|---|---|
| [Cloud deployment](deployment/CLOUD_DEPLOYMENT.md) | Running the backend as a Linux container, and deploying it to AWS. |
| [Desktop packaging](deployment/DESKTOP_PACKAGING.md) | Packaging the Windows desktop app and accepting the offline bundle. |
| [Agent rollout](deployment/AGENT_ROLLOUT.md) | Rolling out the Python agent, and recovering when it fails. |

## Records

The plan and the dated record of one large piece of work. A record says what was decided and done, and when, so it is searched more than it is read through.

| Document | What it covers |
|---|---|
| [Agent migration](records/AGENT_MIGRATION.md) | Moving the agent from Java to Python. |
| [Verification overhaul](records/VERIFICATION_OVERHAUL.md) | Splitting food verification into separate checks (issue #113). |

## Evals

[`evals/`](evals/) holds one folder for each topic. Each eval run has a write-up, named for its date, and the report the eval harness generated, whose name ends in `-report.md`. The [eval harness guide](../agent-python/evals/README.md) says how to run one.

## Releases

[`releases/`](releases/) holds the notes for each published release, named for its version.

## Roadmap

[The roadmap](ROADMAP.md) lists what Tyler 1.0 needs.
