# credo-lib.sh: shared by credo-server.sh (deploy, check, drill) and bench/credo-bench.sh (P16-11, P16-12).
# Sourced, never run. Shipped beside each script that sources it.
#
# The one Compose wrapper: every docker compose call on the server goes through credo_compose, which
#  - runs from CREDO_DIR with credo.env as the env file and compose.yaml (plus CREDO_COMPOSE_EXTRA, a
#    space-separated list of extra files in CREDO_DIR, empty in production);
#  - unsets, for that call only, every key named in credo.env.example or in credo.env, because a variable
#    exported by the calling shell beats --env-file for any key (P16-5 finding, generalised).
# Nothing here prints a value from credo.env.

CREDO_DIR="${CREDO_DIR:-/opt/credo}"
CREDO_ENV="$CREDO_DIR/credo.env"
CREDO_COMPOSE_EXTRA="${CREDO_COMPOSE_EXTRA:-}"
CREDO_PROJECT=credo

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
