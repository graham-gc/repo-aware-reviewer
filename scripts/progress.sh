# Progress goes to stderr so callers can still capture command results from stdout.
progress_is_live() {
    [[ -t 2 && ${TERM:-} != dumb ]]
}

progress_render() {
    local label=$1 completed=$2 total=$3 message=$4
    local finished=$5
    local filled=$((10 * completed / total))
    local full='##########' empty='----------'
    local line="[$label] [${full:0:filled}${empty:filled}] $completed/$total $message"
    if progress_is_live; then
        local size columns
        size=$(stty size <&2 2>/dev/null) || size=''
        columns=${size##* }
        if [[ ! $columns =~ ^[1-9][0-9]*$ ]]; then
            columns=80
        fi
        printf '\r\033[2K%.*s' "$((columns - 1))" "$line" >&2
        if [[ $finished == true ]]; then
            printf '\n' >&2
        fi
    else
        printf '%s\n' "$line" >&2
    fi
}

progress_line() {
    progress_render "$@" true
}

progress_step() (
    # The subshell isolates these variables and keeps them available to the EXIT trap.
    label=$1 completed=$2 total=$3 message=$4
    shift 4
    interval=10 logs_dir=''
    if progress_is_live; then
        interval=1
        logs_dir=$(mktemp -d)
    fi
    progress_render "$label" "$completed" "$total" "$message" false

    (
        local timer_pid=''
        trap 'if [[ -n "$timer_pid" ]]; then kill "$timer_pid" 2>/dev/null || true; fi; exit 0' TERM
        SECONDS=0
        while true; do
            sleep "$interval" &
            timer_pid=$!
            wait "$timer_pid"
            local waiting="$message (still working, ${SECONDS}s elapsed)"
            if [[ -n $logs_dir ]]; then
                waiting="(${SECONDS}s) $message"
            fi
            progress_render "$label" "$completed" "$total" "$waiting" false
        done
    ) &
    heartbeat_pid=$!

    finish_step() {
        local status=$1
        kill "$heartbeat_pid" 2>/dev/null || true
        wait "$heartbeat_pid" 2>/dev/null || true
        if [[ -n $logs_dir ]]; then
            if (( status != 0 )); then
                progress_line "$label" "$completed" "$total" "$message (failed)"
            elif [[ -s $logs_dir/stdout || -s $logs_dir/stderr ]]; then
                printf '\r\033[2K' >&2
            fi
            cat "$logs_dir/stdout"
            cat "$logs_dir/stderr" >&2
            rm -f "$logs_dir/stdout" "$logs_dir/stderr"
            rmdir "$logs_dir"
        fi
    }

    trap 'finish_step "$?"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    if [[ -n $logs_dir ]]; then
        # Replay command output after the heartbeat stops so it cannot overwrite diagnostics.
        "$@" >"$logs_dir/stdout" 2>"$logs_dir/stderr"
    else
        "$@"
    fi
)
