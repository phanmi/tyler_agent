#!/usr/bin/env bash
# Runs deploy-on-host.sh against real Docker and a local registry, replacing only the AWS CLI and
# the CloudWatch log options. Checks that a deployment refuses an unmounted data volume, that a
# first deployment becomes healthy, and that an image that cannot start is rolled back.
#
# Run as root on a disposable machine, because it mounts over /var/lib/tyler.
#
# Usage: test-deploy-on-host.sh <image>
set -euo pipefail

readonly SOURCE_IMAGE="${1:?Usage: test-deploy-on-host.sh <image>}"
DEPLOY_SCRIPT="$(dirname "${BASH_SOURCE[0]}")/deploy-on-host.sh"
readonly DEPLOY_SCRIPT
readonly REGISTRY_IMAGE="registry:3.0.0"
readonly REGISTRY_CONTAINER="tyler-deploy-test-registry"
readonly REGISTRY="localhost:5000"
readonly REGISTRY_READY_SECONDS=30
readonly BROKEN_IMAGE="tyler-deploy-test-broken"
readonly AWS_REGION="us-east-2"
readonly TIME_ZONE="UTC"
readonly REQUEST_TIMEOUT_SECONDS=10
readonly LOG_TAIL_LINES=60

# These match deploy-on-host.sh.
readonly CONTAINER="tyler-backend"
readonly NETWORK="tyler"
readonly DATA_MOUNT="/var/lib/tyler"
readonly BACKEND_UID=10001
readonly TOKEN_HEADER="X-Tyler-Token"
readonly HEALTH_URL="http://127.0.0.1:8080/api/llm/settings"

WORK_DIR="$(mktemp --directory)"
readonly WORK_DIR
readonly FAKE_BIN="${WORK_DIR}/bin"
readonly VOLUME_DIR="${WORK_DIR}/volume"
REMOVED_CONTAINER_LOGS="${WORK_DIR}/removed-container.log"
TEST_ACCESS_TOKEN="$(openssl rand -hex 32)"
REAL_DOCKER="$(command -v docker)"
export REMOVED_CONTAINER_LOGS TEST_ACCESS_TOKEN REAL_DOCKER CONTAINER LOG_TAIL_LINES

cleanup() {
    docker rm --force "$CONTAINER" "$REGISTRY_CONTAINER" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
    umount "$DATA_MOUNT" 2>/dev/null || true
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

fail() {
    echo "FAILED: $1" >&2
    cat "$REMOVED_CONTAINER_LOGS" >&2 2>/dev/null || true
    exit 1
}

write_fake_tools() {
    mkdir -p "$FAKE_BIN"
    cat >"${FAKE_BIN}/aws" <<'FAKE'
#!/usr/bin/env bash
case "$1 $2" in
    "ecr get-login-password") echo unused ;;
    "ssm get-parameter") echo "$TEST_ACCESS_TOKEN" ;;
    *) echo "Unexpected aws call: $*" >&2; exit 1 ;;
esac
FAKE
    # The awslogs driver needs AWS credentials, so the containers use the default log driver.
    # A failed deployment removes its container, so its logs are saved first for fail() to print.
    cat >"${FAKE_BIN}/docker" <<'FAKE'
#!/usr/bin/env bash
if [[ "${1:-} ${2:-}" == "rm --force" ]]; then
    "$REAL_DOCKER" logs --tail "$LOG_TAIL_LINES" "$CONTAINER" >>"$REMOVED_CONTAINER_LOGS" 2>&1 || true
fi
arguments=()
while (($#)); do
    case "$1" in
        --log-driver | --log-opt) shift 2 ;;
        *) arguments+=("$1"); shift ;;
    esac
done
exec "$REAL_DOCKER" "${arguments[@]}"
FAKE
    chmod +x "${FAKE_BIN}/aws" "${FAKE_BIN}/docker"
}

deploy() {
    PATH="${FAKE_BIN}:${PATH}" bash "$DEPLOY_SCRIPT" "$1" "$AWS_REGION" "$TIME_ZONE"
}

start_registry() {
    docker run --detach --name "$REGISTRY_CONTAINER" --publish 127.0.0.1:5000:5000 "$REGISTRY_IMAGE" >/dev/null
    local deadline=$((SECONDS + REGISTRY_READY_SECONDS))
    until curl --silent --fail --output /dev/null "http://${REGISTRY}/v2/"; do
        ((SECONDS < deadline)) || fail "the local registry did not start"
        sleep 1
    done
}

# Pushes the image and prints the digest reference a deployment uses. The image also has digests
# under its other names, so only the one under the local registry is printed.
publish_image() {
    local target="${REGISTRY}/tyler-backend:$2"
    docker tag "$1" "$target"
    docker push --quiet "$target" >/dev/null
    docker inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$target" \
        | grep --max-count=1 "^${REGISTRY}/"
}

mount_data_volume() {
    mkdir -p "$DATA_MOUNT" "$VOLUME_DIR"
    mount --bind "$VOLUME_DIR" "$DATA_MOUNT"
    install -d -o "$BACKEND_UID" -g "$BACKEND_UID" -m 700 "${DATA_MOUNT}/backend"
}

check_backend_runs() {
    local image status
    image="$(docker inspect --format '{{.Config.Image}}' "$CONTAINER")"
    [[ "$image" == "$1" ]] || fail "expected ${1} to be running, found ${image}"
    status="$(curl --silent --max-time "$REQUEST_TIMEOUT_SECONDS" --output /dev/null --write-out '%{http_code}' \
        --header "${TOKEN_HEADER}: ${TEST_ACCESS_TOKEN}" "$HEALTH_URL" || true)"
    [[ "$status" == "200" ]] || fail "the backend answered HTTP ${status}"
}

check_refuses_unmounted_volume() {
    if deploy "$1"; then
        fail "deployed without the data volume mounted"
    fi
    if docker container inspect "$CONTAINER" >/dev/null 2>&1; then
        fail "started a container without the data volume mounted"
    fi
}

check_rolls_back() {
    if deploy "$1"; then
        fail "an image that cannot start was reported as deployed"
    fi
    check_backend_runs "$2"
}

main() {
    write_fake_tools
    docker network create "$NETWORK" >/dev/null
    start_registry

    printf 'FROM %s\nENTRYPOINT ["false"]\n' "$SOURCE_IMAGE" | docker build --quiet --tag "$BROKEN_IMAGE" - >/dev/null
    local good broken
    good="$(publish_image "$SOURCE_IMAGE" good)"
    broken="$(publish_image "$BROKEN_IMAGE" broken)"

    check_refuses_unmounted_volume "$good"
    mount_data_volume
    deploy "$good" || fail "the first deployment failed"
    check_backend_runs "$good"
    check_rolls_back "$broken" "$good"
    echo "Deployment test passed."
}

main
