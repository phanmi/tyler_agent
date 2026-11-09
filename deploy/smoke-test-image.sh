#!/usr/bin/env bash
# Exercises the real backend image: the startup token policy, Python worker readiness,
# token enforcement, the non-root user, and data surviving a container replacement.
#
# Usage: smoke-test-image.sh <image>
set -euo pipefail

readonly IMAGE="${1:?Usage: smoke-test-image.sh <image>}"
readonly CONTAINER="tyler-smoke-$$"
readonly REFUSED_CONTAINER="${CONTAINER}-no-token"
readonly VOLUME="${CONTAINER}-data"
readonly HOST_PORT=18080
readonly BASE_URL="http://127.0.0.1:${HOST_PORT}"
readonly TOKEN_HEADER="X-Tyler-Token"
readonly HEALTH_PATH="/api/llm/settings"
readonly READY_TIMEOUT_SECONDS=90
readonly REFUSAL_TIMEOUT_SECONDS=60
readonly TIMEOUT_EXIT_CODE=124
readonly STOP_GRACE_SECONDS=30
readonly EXPECTED_UID=10001
readonly REFUSAL_MESSAGE="TYLER_ACCESS_TOKEN is required"
readonly WORKOUT_DATE="2026-01-15"
readonly WORKOUT_NAME="ContainerSmokeSquat"
TOKEN="$(openssl rand -hex 32)"
readonly TOKEN

cleanup() {
    docker rm --force "$CONTAINER" "$REFUSED_CONTAINER" >/dev/null 2>&1 || true
    docker volume rm --force "$VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
    echo "FAILED: $1" >&2
    docker logs "$CONTAINER" 2>&1 | tail -n 60 >&2 || true
    exit 1
}

status_with_token() {
    curl --silent --output /dev/null --write-out '%{http_code}' \
        --header "${TOKEN_HEADER}: ${TOKEN}" "${BASE_URL}${HEALTH_PATH}" || true
}

status_without_token() {
    curl --silent --output /dev/null --write-out '%{http_code}' "${BASE_URL}${HEALTH_PATH}" || true
}

start_backend() {
    docker run --init --detach --name "$CONTAINER" \
        --publish "127.0.0.1:${HOST_PORT}:8080" \
        --volume "${VOLUME}:/data" \
        --env TYLER_ACCESS_TOKEN="$TOKEN" \
        "$IMAGE" >/dev/null
}

# The backend fails startup if the worker never becomes ready, so a 200 proves the worker launched.
wait_until_ready() {
    local deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
    while ((SECONDS < deadline)); do
        [[ "$(status_with_token)" == "200" ]] && return 0
        [[ "$(docker inspect --format '{{.State.Running}}' "$CONTAINER")" == "true" ]] \
            || fail "backend exited during startup"
        sleep 1
    done
    fail "backend was not ready within ${READY_TIMEOUT_SECONDS} seconds"
}

replace_backend() {
    docker stop --time "$STOP_GRACE_SECONDS" "$CONTAINER" >/dev/null
    docker rm "$CONTAINER" >/dev/null
    start_backend
    wait_until_ready
}

check_refuses_to_start_without_token() {
    local output exit_code=0
    output="$(timeout "$REFUSAL_TIMEOUT_SECONDS" docker run --rm --name "$REFUSED_CONTAINER" "$IMAGE" 2>&1)" \
        || exit_code=$?
    [[ "$exit_code" -ne 0 && "$exit_code" -ne "$TIMEOUT_EXIT_CODE" ]] \
        || fail "backend started without a token (exit code ${exit_code})"
    grep --quiet "$REFUSAL_MESSAGE" <<<"$output" \
        || fail "startup failed for a reason other than the missing token: ${output: -2000}"
}

check_runs_as_non_root() {
    [[ "$(docker exec "$CONTAINER" id -u)" == "$EXPECTED_UID" ]] \
        || fail "container is not running as UID ${EXPECTED_UID}"
}

check_token_is_enforced() {
    [[ "$(status_without_token)" == "401" ]] || fail "a request without the token was not rejected"
}

save_workout() {
    local status
    status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
        --header "${TOKEN_HEADER}: ${TOKEN}" --header 'Content-Type: application/json' \
        --data "{\"workoutName\":\"${WORKOUT_NAME}\",\"rep\":\"4X12\",\"weight\":20,\"workoutDate\":\"${WORKOUT_DATE}\"}" \
        "${BASE_URL}/api/workouts")"
    [[ "$status" == "201" ]] || fail "saving a workout returned HTTP ${status}"
}

check_workout_survived() {
    local body
    body="$(curl --silent --header "${TOKEN_HEADER}: ${TOKEN}" "${BASE_URL}/api/workouts?date=${WORKOUT_DATE}")"
    grep --quiet "$WORKOUT_NAME" <<<"$body" || fail "the saved workout did not survive replacing the container"
}

check_refuses_to_start_without_token
start_backend
wait_until_ready
check_runs_as_non_root
check_token_is_enforced
save_workout
replace_backend
check_workout_survived
echo "Container smoke test passed."
