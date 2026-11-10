#!/usr/bin/env bash
# Runs deploy-on-host.sh on the EC2 host through SSM and reports its result.
# Expects AWS credentials and AWS_REGION in the environment.
#
# Usage: send-deploy-to-host.sh <image> <time-zone>
set -euo pipefail

readonly USAGE="Usage: send-deploy-to-host.sh <image> <time-zone>"
readonly IMAGE="${1:?$USAGE}"
readonly TIME_ZONE="${2:?$USAGE}"
readonly REGION="${AWS_REGION:?AWS_REGION is required}"

HOST_SCRIPT="$(dirname "${BASH_SOURCE[0]}")/deploy-on-host.sh"
readonly HOST_SCRIPT
readonly HOST_NAME_TAG="tyler-host"
readonly SSM_DOCUMENT="AWS-RunShellScript"
# Long enough for an image pull, a failed start, and a rollback.
readonly EXECUTION_TIMEOUT_SECONDS=900
# An unreachable host must not pick up this deployment long after the workflow has given up.
readonly DELIVERY_TIMEOUT_SECONDS=120
readonly RESULT_WAIT_SECONDS=$((EXECUTION_TIMEOUT_SECONDS + DELIVERY_TIMEOUT_SECONDS))
readonly POLL_SECONDS=5
readonly SUCCESS_STATUS="Success"
readonly NO_RESULT_STATUS="no result"

find_host() {
    aws ec2 describe-instances \
        --filters "Name=tag:Name,Values=${HOST_NAME_TAG}" "Name=instance-state-name,Values=running" \
        --query 'Reservations[].Instances[].InstanceId' --output text
}

# Sent encoded so that nothing in the script needs quoting for the host's shell.
build_parameters() {
    local host_command
    host_command="bash -c \"\$(echo ${1} | base64 --decode)\" deploy-on-host '${IMAGE}' '${REGION}' '${TIME_ZONE}'"
    jq --null-input --arg command "$host_command" --arg timeout "$EXECUTION_TIMEOUT_SECONDS" \
        '{commands: [$command], executionTimeout: [$timeout]}'
}

send_command() {
    aws ssm send-command \
        --document-name "$SSM_DOCUMENT" \
        --instance-ids "$1" \
        --timeout-seconds "$DELIVERY_TIMEOUT_SECONDS" \
        --parameters "$(build_parameters "$2")" \
        --query Command.CommandId --output text
}

invocation_field() {
    aws ssm get-command-invocation --instance-id "$1" --command-id "$2" --query "$3" --output text
}

# The invocation does not exist for a moment after sending, so a failed lookup keeps waiting.
wait_for_status() {
    local deadline=$((SECONDS + RESULT_WAIT_SECONDS)) status
    while ((SECONDS < deadline)); do
        status="$(invocation_field "$1" "$2" Status || true)"
        case "$status" in
            "$SUCCESS_STATUS" | Failed | Cancelled | TimedOut)
                echo "$status"
                return 0
                ;;
        esac
        sleep "$POLL_SECONDS"
    done
    echo "$NO_RESULT_STATUS"
}

main() {
    local instance_id encoded_script command_id status
    instance_id="$(find_host)"
    if [[ -z "$instance_id" ]]; then
        echo "FAILED: no running instance is tagged Name=${HOST_NAME_TAG}" >&2
        exit 1
    fi

    # Encoded here because a failure inside build_parameters' substitution would go unnoticed,
    # and the host would then report success for running an empty script.
    encoded_script="$(base64 --wrap=0 "$HOST_SCRIPT")"
    command_id="$(send_command "$instance_id" "$encoded_script")"
    status="$(wait_for_status "$instance_id" "$command_id")"

    if [[ "$status" != "$NO_RESULT_STATUS" ]]; then
        invocation_field "$instance_id" "$command_id" StandardOutputContent
        invocation_field "$instance_id" "$command_id" StandardErrorContent >&2
    fi
    if [[ "$status" != "$SUCCESS_STATUS" ]]; then
        echo "FAILED: the deployment on the host ended with status: ${status}" >&2
        exit 1
    fi
}

main
