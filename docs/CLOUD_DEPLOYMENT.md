# Running the backend as a Linux container

[Developer guide](DEVELOPMENT.md) | [File sandbox](FILE_SANDBOX.md) | [Session lifecycle](AGENT_SESSION.md)

The root `Dockerfile` builds one image holding the backend JAR and the Linux build of the Python worker that Java supervises. The frontend is not part of it.

## Build and run

From the repository root:

```shell
docker build --tag tyler-backend .

export TYLER_ACCESS_TOKEN="$(openssl rand -hex 32)"
docker run --init --detach --name tyler \
  --publish 127.0.0.1:8080:8080 \
  --volume tyler-data:/data \
  --env TYLER_ACCESS_TOKEN \
  --env TZ=America/New_York \
  tyler-backend
```

| Environment variable | Value in the image | Notes |
|---|---|---|
| `TYLER_ACCESS_TOKEN` | None; required | 64 lowercase hexadecimal characters. Clients send it in `X-Tyler-Token`. |
| `TZ` | None (UTC) | Set it to your zone. The agent's clock and food timestamps use the server's time zone. |
| `SERVER_ADDRESS` | `0.0.0.0` | Applies inside the container. Publish the port only to the reverse proxy. |
| `SERVER_PORT` | `8080` | |
| `AGENT_WORKSPACE_DIR` | `/data/workspace` | Databases, settings, provider keys, chat history, and agent-written files. |
| `LOG_DIR` | `/data/logs` | Logs also go to standard output. |

## Operating rules

- **The token is mandatory.** A backend bound to a non-loopback address refuses to start without one, so a failed secret lookup cannot expose an open API.
- **Run exactly one container per data volume.** File locks and the conversation lane exist only inside one process, and chat history is a read-modify-write file. Stop the old container before starting a new one.
- **Keep `/data` on a local or block disk, such as EBS.** SQLite locking is not reliable on network filesystems.
- **Put HTTPS in front of the published port.** A chat holds one HTTP request for up to `agent.python.run-timeout` (5 minutes by default), so proxy and load balancer timeouts must be longer than that.
- **Health checks must send the token.** Every path requires it. `GET /api/llm/settings` needs no provider key, and the backend does not start unless the worker became ready.
- **Keep logging at INFO.** DEBUG logs include chat and tool content.
- **Host directories mounted at `/data` must be owned by UID 10001**, the image's non-root user.
- **The agent's file tools act on `/data/workspace`** on the server, not on the client's computer.

## Verification

`bash deploy/smoke-test-image.sh <image>` runs the real image and checks that it refuses to start without a token, that the backend and worker start, that a request without the token gets 401, that the container is not running as root, and that saved data survives replacing the container. The **Backend container smoke** CI job runs this script and the worker bundle tests against the Linux build.

These checks make no model-provider calls. After deploying, send one real chat that uses a tool, such as recording a food.
