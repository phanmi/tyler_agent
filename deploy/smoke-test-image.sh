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
readonly HEALTH_URL="${BASE_URL}/api/llm/settings"
readonly READY_TIMEOUT_SECONDS=90
readonly REFUSAL_TIMEOUT_SECONDS=60
readonly TIMEOUT_EXIT_CODE=124
readonly REQUEST_TIMEOUT_SECONDS=10
readonly STOP_GRACE_SECONDS=30
readonly LOG_TAIL_LINES=60
readonly OUTPUT_TAIL_BYTES=2000
readonly EXPECTED_UID=10001
readonly REFUSAL_MESSAGE="TYLER_ACCESS_TOKEN is required"
readonly WORKOUT_DATE="2026-01-15"
readonly WORKOUT_NAME="ContainerSmokeSquat"
TOKEN="$(openssl rand -hex 32)"
readonly TOKEN
readonly AUTH_HEADER="${TOKEN_HEADER}: ${TOKEN}"

cleanup() {
    docker rm --force "$CONTAINER" "$REFUSED_CONTAINER" >/dev/null 2>&1 || true
    docker volume rm --force "$VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
    echo "FAILED: $1" >&2
    if docker container inspect "$CONTAINER" >/dev/null 2>&1; then
        docker logs "$CONTAINER" 2>&1 | tail -n "$LOG_TAIL_LINES" >&2 || true
    fi
    exit 1
}

# Prints the HTTP status, or 000 when nothing answered.
http_status() {
    curl --silent --max-time "$REQUEST_TIMEOUT_SECONDS" --output /dev/null --write-out '%{http_code}' "$@" || true
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
        [[ "$(http_status --header "$AUTH_HEADER" "$HEALTH_URL")" == "200" ]] && return 0
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
    local output recent_output exit_code=0
    output="$(timeout "$REFUSAL_TIMEOUT_SECONDS" docker run --rm --name "$REFUSED_CONTAINER" "$IMAGE" 2>&1)" \
        || exit_code=$?
    recent_output="$(tail --bytes="$OUTPUT_TAIL_BYTES" <<<"$output")"
    [[ "$exit_code" -ne 0 && "$exit_code" -ne "$TIMEOUT_EXIT_CODE" ]] \
        || fail "backend started without a token (exit code ${exit_code}): ${recent_output}"
    grep --quiet "$REFUSAL_MESSAGE" <<<"$output" \
        || fail "startup failed for a reason other than the missing token: ${recent_output}"
}

check_runs_as_non_root() {
    [[ "$(docker exec "$CONTAINER" id -u)" == "$EXPECTED_UID" ]] \
        || fail "container is not running as UID ${EXPECTED_UID}"
}

check_token_is_enforced() {
    [[ "$(http_status "$HEALTH_URL")" == "401" ]] || fail "a request without the token was not rejected"
}

save_workout() {
    local status
    status="$(http_status --header "$AUTH_HEADER" --header 'Content-Type: application/json' \
        --data "{\"workoutName\":\"${WORKOUT_NAME}\",\"rep\":\"4X12\",\"weight\":20,\"workoutDate\":\"${WORKOUT_DATE}\"}" \
        "${BASE_URL}/api/workouts")"
    [[ "$status" == "201" ]] || fail "saving a workout returned HTTP ${status}"
}

check_workout_survived() {
    local body
    body="$(curl --silent --max-time "$REQUEST_TIMEOUT_SECONDS" --header "$AUTH_HEADER" \
        "${BASE_URL}/api/workouts?date=${WORKOUT_DATE}" || true)"
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
