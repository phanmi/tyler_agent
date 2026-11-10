#!/usr/bin/env bash
# Runs on the EC2 host as root, through SSM. Replaces the backend container with the given image
# and restores the previous image if the new one does not become healthy.
#
# The output reaches public GitHub Actions logs, so this prints neither the token nor container
# logs. Container logs are in CloudWatch.
#
# Usage: deploy-on-host.sh <image> <aws-region> <time-zone>
set -euo pipefail

readonly USAGE="Usage: deploy-on-host.sh <image> <aws-region> <time-zone>"
readonly IMAGE="${1:?$USAGE}"
readonly AWS_REGION="${2:?$USAGE}"
readonly TIME_ZONE="${3:?$USAGE}"

# These match infra/aws.
readonly CONTAINER="tyler-backend"
readonly NETWORK="tyler"
readonly DATA_MOUNT="/var/lib/tyler"
readonly TOKEN_PARAMETER="/tyler/access-token"
readonly LOG_GROUP="/tyler/containers"

readonly BACKEND_PORT=8080
readonly TOKEN_HEADER="X-Tyler-Token"
readonly HEALTH_URL="http://127.0.0.1:${BACKEND_PORT}/api/llm/settings"
# Longer than the 90 seconds the smoke test allows, because the host is a small burstable instance.
readonly READY_TIMEOUT_SECONDS=180
readonly REQUEST_TIMEOUT_SECONDS=10
readonly POLL_SECONDS=2
readonly STOP_GRACE_SECONDS=30
readonly LOG_HINT="See the ${CONTAINER} stream in the ${LOG_GROUP} CloudWatch log group."
readonly LOCK_FILE="/run/tyler-deploy.lock"

fail() {
    echo "FAILED: $1" >&2
    exit 1
}

# A cancelled workflow leaves its command running here, so the workflow's own concurrency limit
# does not stop the next deployment from overlapping it. The lock ends when this script exits.
take_deployment_lock() {
    local lock_fd
    exec {lock_fd}>"$LOCK_FILE"
    flock --nonblock "$lock_fd" || fail "another deployment is still running on the host"
}

# Without the volume, Docker would create the directory on the root disk and the backend would
# start with empty data.
require_data_volume() {
    mountpoint --quiet "$DATA_MOUNT" || fail "${DATA_MOUNT} is not mounted"
}

pull_image() {
    aws ecr get-login-password --region "$AWS_REGION" \
        | docker login --username AWS --password-stdin "${IMAGE%%/*}" >/dev/null
    docker pull --quiet "$IMAGE" >/dev/null
}

read_access_token() {
    TYLER_ACCESS_TOKEN="$(aws ssm get-parameter --region "$AWS_REGION" --with-decryption \
        --name "$TOKEN_PARAMETER" --query Parameter.Value --output text)"
    export TYLER_ACCESS_TOKEN
}

# Prints the image the current container runs, or nothing when there is no container.
current_image() {
    docker container inspect --format '{{.Config.Image}}' "$CONTAINER" 2>/dev/null || true
}

stop_backend() {
    docker stop --time "$STOP_GRACE_SECONDS" "$CONTAINER" >/dev/null
    docker rm "$CONTAINER" >/dev/null
}

start_backend() {
    docker run --init --detach --restart unless-stopped --name "$CONTAINER" --network "$NETWORK" \
        --publish "127.0.0.1:${BACKEND_PORT}:${BACKEND_PORT}" \
        --volume "${DATA_MOUNT}/backend:/data" \
        --env TYLER_ACCESS_TOKEN \
        --env TZ="$TIME_ZONE" \
        --log-driver awslogs \
        --log-opt awslogs-region="$AWS_REGION" \
        --log-opt awslogs-group="$LOG_GROUP" \
        --log-opt awslogs-stream="$CONTAINER" \
        "$1" >/dev/null
}

# Prints the HTTP status, or 000 when nothing answered.
health_status() {
    curl --silent --max-time "$REQUEST_TIMEOUT_SECONDS" --output /dev/null --write-out '%{http_code}' \
        --header "${TOKEN_HEADER}: ${TYLER_ACCESS_TOKEN}" "$HEALTH_URL" || true
}

# The backend does not start unless the worker became ready, so a 200 proves both are up.
# A crash shows up as a restart, which ends the wait early.
wait_until_ready() {
    local deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
    while ((SECONDS < deadline)); do
        [[ "$(health_status)" == "200" ]] && return 0
        [[ "$(docker inspect --format '{{.RestartCount}}' "$CONTAINER")" == "0" ]] || return 1
        sleep "$POLL_SECONDS"
    done
    return 1
}

remove_failed_backend() {
    docker rm --force "$CONTAINER" >/dev/null 2>&1 || true
}

roll_back() {
    local previous="$1"
    remove_failed_backend
    [[ -n "$previous" ]] || fail "the new image did not become healthy and there is no previous image. ${LOG_HINT}"
    echo "The new image did not become healthy. Restoring ${previous}"
    if start_backend "$previous" && wait_until_ready; then
        fail "the new image did not become healthy, so the previous image was restored. ${LOG_HINT}"
    fi
    fail "the previous image did not become healthy either, so the backend is down. ${LOG_HINT}"
}

main() {
    take_deployment_lock
    require_data_volume
    pull_image
    read_access_token

    local previous
    previous="$(current_image)"
    if [[ -n "$previous" ]]; then
        stop_backend
    fi

    if start_backend "$IMAGE" && wait_until_ready; then
        # Every image still in use stays, including the one the next deployment would roll back to.
        docker image prune --all --force >/dev/null
        echo "Deployed ${IMAGE}"
    else
        roll_back "$previous"
    fi
}

main
