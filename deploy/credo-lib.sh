# credo-lib.sh: shared by credo-server.sh (deploy, restart-exchange, check, drill) and bench/credo-bench.sh
# (P16-11, P16-12, Session G). Sourced, never run. Shipped beside each script that sources it.
#
# The one Compose wrapper: every docker compose call on the server goes through credo_compose, which
#  - runs from CREDO_DIR with credo.env as the env file and compose.yaml (plus CREDO_COMPOSE_EXTRA, a
#    space-separated list of extra files in CREDO_DIR, empty in production);
#  - unsets, for that call only, every key named in credo.env.example or in credo.env, because a variable
#    exported by the calling shell beats --env-file for any key (P16-5 finding, generalised).
# Also: the two locks (deploy and benchmark, which exclude each other), the log-line filters shared by the
# check and restart-exchange, and the pure helpers of the deploy messages (tested by test/check-files.sh).
# Nothing here prints a value from credo.env.

CREDO_DIR="${CREDO_DIR:-/opt/credo}"
CREDO_ENV="$CREDO_DIR/credo.env"
CREDO_COMPOSE_EXTRA="${CREDO_COMPOSE_EXTRA:-}"
CREDO_PROJECT=credo
# deploy, rollback and restart-exchange hold the first for their whole run; a benchmark run holds the second.
CREDO_DEPLOY_LOCK="$CREDO_DIR/.deploy.lock"
CREDO_BENCH_LOCK="${BENCH_ROOT:-$HOME/credo-bench}/.lock"

# Whether another process holds the lock file $1 (a missing file is free). Probes with a non-blocking flock
# on a private descriptor, released at once, so it never keeps the lock.
credo_lock_held() {
    [ -e "$1" ] || return 1
    ( flock -n 7 ) 7< "$1" && return 1
    return 0
}

# Key names of an env file (KEY=value lines), one per line, sorted. Values are never read.
credo_env_keys() {
    [ -f "$1" ] || return 0
    sed -n -E 's/^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=.*/\1/p' "$1" | sort -u
}

# The value of one key of credo.env, for the non-secret keys only (IMAGE_TAG, WS_TRUSTED_PROXIES,
# WS_EXEMPT_ADDRESSES, CREDO_DATA_DIR). Refuses any other key, so a secret can never be read by mistake.
credo_env_get() {
    case "$1" in
        IMAGE_TAG|WS_TRUSTED_PROXIES|WS_EXEMPT_ADDRESSES|CREDO_DATA_DIR) ;;
        *) echo "credo_env_get: refusing to read $1" >&2; return 2 ;;
    esac
    [ -f "$CREDO_ENV" ] || return 0
    sed -n -E "s/^[[:space:]]*$1=(.*)$/\\1/p" "$CREDO_ENV" | tail -n 1
}

credo_compose() {
    local -a unset_args=() files=(-f "$CREDO_DIR/compose.yaml")
    local key extra
    while IFS= read -r key; do
        [ -n "$key" ] && unset_args+=(-u "$key")
    done < <({ credo_env_keys "$CREDO_DIR/credo.env.example"; credo_env_keys "$CREDO_ENV"; } | sort -u)
    for extra in $CREDO_COMPOSE_EXTRA; do
        files+=(-f "$CREDO_DIR/$extra")
    done
    env "${unset_args[@]}" docker compose --project-name "$CREDO_PROJECT" --project-directory "$CREDO_DIR" \
        --env-file "$CREDO_ENV" "${files[@]}" "$@"
}

# The container of a service of the stack (empty if none), and whether it is running or restarting.
credo_container() {
    docker ps -aq --filter "label=com.docker.compose.project=$CREDO_PROJECT" \
        --filter "label=com.docker.compose.service=$1" | head -n 1
}

credo_service_active() {
    [ -n "$(docker ps -q --filter "label=com.docker.compose.project=$CREDO_PROJECT" \
        --filter "label=com.docker.compose.service=$1")" ]
}

# Waits for an HTTP 200 from $1, at most $2 seconds; prints a progress line every 10 s to stderr.
credo_wait_200() {
    local url="$1" bound="$2" start now code next
    start=$(date +%s)
    next=$((start + 10))
    while :; do
        code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$url" 2>/dev/null || true)
        [ "$code" = "200" ] && return 0
        now=$(date +%s)
        if [ $((now - start)) -ge "$bound" ]; then
            return 1
        fi
        if [ "$now" -ge "$next" ]; then
            echo "  waiting for $url: ${code:-no answer} after $((now - start)) s (bound $bound s)" >&2
            next=$((now + 10))
        fi
        sleep 2
    done
}

# --- log-line filters (stdin to stdout), shared by check and restart-exchange -----------------------------
# Each prints the matched part of a line only, never the logger prefix. The formats are pinned by ResendLogTest,
# CatchUpLogTest and ResendService; test/check-files.sh proves each filter on planted lines.

# The venue's RESEND completion lines (net.ResendLog).
credo_resend_lines() {
    grep -oE "RESEND fromSeq [0-9]+ to [0-9]+: [0-9]+ frames to [^ ]+ in [0-9]+ ms( \(read [0-9]+ events, [0-9]+ bytes from position [0-9]+\))?" || true
}

# The venue's RESEND refusals for a full exempt pool (BUSY).
credo_resend_busy_lines() {
    grep -oE "RESEND fromSeq [0-9]+ from [^ ]+ rejected: [0-9]+ resends already pending" || true
}

# The bots' catch-up lines (participant.CatchUpLog).
credo_catchup_lines() {
    grep -oE "[A-Za-z]+ caught up through seq [0-9]+ in [0-9]+ ms \(RESEND from seq [0-9]+, [0-9]+ request\(s\)(, offline [0-9]+ ms)?\)" || true
}

# The bot names in "<NAME> lost its connection to ..." lines, one per line, sorted, unique.
credo_lost_names() {
    grep -oE "[A-Za-z]+ lost its connection to " | sed 's/ lost its connection to $//' | sort -u || true
}

# The session-open lines of the venue (a fresh open, or one recovered at start), matched part only.
credo_session_open_lines() {
    grep -oE "Session open for business date [0-9]+|Session for business date [0-9]+ is open \(recovered\)" || true
}

# --- pure helpers of the deploy messages ------------------------------------------------------------------

# The last tag recorded ok in the history file $1 that is not $2 (empty if none). Failed and attempt lines
# never count, so a failed attempt is never named as the rollback target.
credo_previous_ok() {
    [ -f "$1" ] || return 0
    awk -v t="$2" '$3 == "ok" && $2 != t { p = $2 } END { print p }' "$1"
}

# The IMAGE_TAG message: old value $1 (may be empty), new value $2.
credo_tag_note() {
    if [ "$1" = "$2" ]; then
        echo "IMAGE_TAG unchanged ($2)"
    elif [ -z "$1" ]; then
        echo "IMAGE_TAG set to $2 (was unset)"
    else
        echo "IMAGE_TAG set to $2 (was $1)"
    fi
}

# The first number after "key": in a JSON text (keys read this way are unique in /health), or empty.
credo_json_num() {
    grep -o "\"$2\":-\?[0-9]*" <<< "$1" | head -n 1 | sed 's/.*://'
}

# One field of /health's journal.outbound object ("startPosition", "recordedPosition", "bytesOnDisk").
credo_outbound_field() {
    grep -o '"outbound":{"recordingId":[0-9-]*,"startPosition":[0-9-]*,"recordedPosition":[0-9-]*,"bytesOnDisk":[0-9-]*' <<< "$1" \
        | grep -o "\"$2\":-\?[0-9]*" | sed 's/.*://'
}

# --- P16-13 helpers ---------------------------------------------------------------------------------------

# One field of a flat object in a JSON text: $1 json, $2 the object's key (its first occurrence), $3 the field.
# Prints a number, a string without its quotes, or "null"; empty if absent. Only flat objects (no nested braces
# before the field), which /health's reporter, clearing, connections and framesDropped objects are.
credo_obj_field() {
    grep -o "\"$2\":{[^{}]*" <<< "$1" | head -n 1 | grep -oE "\"$3\":(\"[^\"]*\"|-?[0-9]+|null)" | head -n 1 \
        | sed -E 's/^"[^"]*"://; s/^"(.*)"$/\1/'
}

# The /assets/ files (.js and .css) an index.html references, one path per line, in order, unique.
credo_index_assets() {
    grep -oE '(src|href)="/assets/[^"]+\.(js|css)"' | sed -E 's/^(src|href)="//; s/"$//' | awk '!seen[$0]++' || true
}

# The access mode of a Caddyfile: "private" or "public" from its one `import private|public` line, else empty
# (the P16-10 file, with basic_auth inline, has no such line).
credo_caddy_mode() {
    [ -r "$1" ] || return 0
    sed -n -E 's/^[[:space:]]*import[[:space:]]+(private|public)[[:space:]]*(#.*)?$/\1/p' "$1" | head -n 1
}

# Whether a UTC time HHMM (4 digits) falls in the window kept free for the calendar roll, clearing and the
# 06:15 backup: 03:50 to 06:30 UTC (New York midnight is 04:00 UTC in summer and 05:00 in winter).
credo_in_quiet_window() {
    local t=$((10#$1))
    [ "$t" -ge 350 ] && [ "$t" -lt 630 ]
}

# Searches every file under the directories given for the values of ALPACA_KEY_ID and ALPACA_SECRET_KEY in
# credo.env. The values go to grep -F through -f from a pipe, never through argv or an environment variable
# (argv is readable in ps by anyone on the box), and are never printed. Exit 0: a value was found; 1: none
# found; 2: neither key has a value (nothing to search for).
credo_scan_secret_values() {
    local n
    n=$(sed -n -E 's/^[[:space:]]*(ALPACA_KEY_ID|ALPACA_SECRET_KEY)=(.+)$/\2/p' "$CREDO_ENV" 2>/dev/null | wc -l)
    [ "$n" -gt 0 ] || return 2
    if sed -n -E 's/^[[:space:]]*(ALPACA_KEY_ID|ALPACA_SECRET_KEY)=(.+)$/\2/p' "$CREDO_ENV" \
        | grep -rqaF -f /dev/stdin -- "$@" 2>/dev/null; then
        return 0
    fi
    return 1
}
