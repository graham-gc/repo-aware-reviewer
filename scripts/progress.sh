# Progress goes to stderr so callers can still capture command results from stdout.
progress_line() {
    local label=$1 completed=$2 total=$3 message=$4
    local filled=$((10 * completed / total))
    local full='##########' empty='----------'
    printf '[%s] [%s%s] %d/%d %s\n' "$label" "${full:0:filled}" "${empty:filled}" \
        "$completed" "$total" "$message" >&2
}

progress_step() (
    local label=$1 completed=$2 total=$3 message=$4
    shift 4
    progress_line "$label" "$completed" "$total" "$message"

    (
        local timer_pid=''
        trap 'if [[ -n "$timer_pid" ]]; then kill "$timer_pid" 2>/dev/null || true; fi; exit 0' TERM
        SECONDS=0
        while true; do
            sleep 10 &
            timer_pid=$!
            wait "$timer_pid"
            progress_line "$label" "$completed" "$total" "$message (still working, ${SECONDS}s elapsed)"
        done
    ) &
    local heartbeat_pid=$!
    trap "kill $heartbeat_pid 2>/dev/null || true; wait $heartbeat_pid 2>/dev/null || true" EXIT
    "$@"
)
