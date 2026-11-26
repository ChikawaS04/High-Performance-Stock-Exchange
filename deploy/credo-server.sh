#!/usr/bin/env bash
# credo-server.sh: the server half of the deploy flow (P16-11, D13). deploy.sh copies it to the server with
# scp, beside credo-lib.sh, and runs it with one ssh command. It is never pasted into a terminal.
#
#   credo-server.sh deploy --tag <12 hex> --stage <dir> [--rollback] [--force] [--health-timeout <s>]
#   credo-server.sh restart-exchange [--health-timeout <s>] [--catchup-timeout <s>]
#   credo-server.sh crash-exchange --yes [--health-timeout <s>] [--catchup-timeout <s>]
#   credo-server.sh bounce-postgres --yes [--down-seconds <s>] [--catchup-timeout <s>]
#   credo-server.sh check [--password]
#   credo-server.sh drill
#   credo-server.sh flood-report --since <seconds>s
#
# deploy: preflight (nothing changes until it passes), the tag's bundle and static release, the bundle's
# files installed into /opt/credo, IMAGE_TAG set in credo.env, bots then exchange stopped, compose up under
# one bounded deadline with the /health wait, then the static symlink switch, the history line and pruning.
# A rollback (--rollback) uses the bundle and release already on the server for that tag.
# restart-exchange: `compose restart exchange` alone under the deploy lock, with the bots left running; waits
# for /health 200 and for every bot's catch-up, then prints the RESEND and catch-up timing lines of the new
# process and the outbound journal's size. No history line, no symlink change, no prune (Session G).
# crash-exchange (P16-13 item 5): a SIGKILL from the host to the exchange's main process; the restart policy
# brings the same container back; waits for /health 200 and every bot's catch-up, and proves the policy did it.
# bounce-postgres (P16-13 item 7): stops postgres alone, shows matching continuing and /api answering 503, starts
# it again and waits for the Reporter to be RUNNING with lag 0. Both hold the deploy lock and need --yes.
# check: one PASS, FAIL, WAIT or SKIP line per item of the first-deploy checks; exit 1 on any FAIL.
# flood-report: whether any bot lost its connection since a time (the bots' side of deploy.sh --flood).
# drill: restores the newest nightly dump into a scratch database and compares it with the live one (P16-6).
# deploy, restart-exchange and drill refuse while a benchmark run holds its lock (CREDO_BENCH_LOCK).
#
# Paths, overridable only so the local live check can run this file unchanged (production uses the defaults):
#   CREDO_DIR (/opt/credo), CREDO_WEB_DIR (/var/www/directroute), CREDO_UNIT_DIR (/etc/systemd/system),
#   CREDO_BACKUP_DIR (/srv/credo/backups), CREDO_HEALTH_URL (http://127.0.0.1:8080/health),
#   CREDO_SITE_URL (https://davidsexchange.com; empty skips the Caddy rows), CREDO_COMPOSE_EXTRA (empty),
#   CREDO_CADDYFILE (/etc/caddy/Caddyfile, read for the access mode), CREDO_NOW_UTC (HHMM; tests of the
#   bounce-postgres time window only).
#
# Never prints a value from credo.env except the non-secret IMAGE_TAG, WS_TRUSTED_PROXIES and
# WS_EXEMPT_ADDRESSES. Refuses to run as root, so credo.env and the bundles are never root-owned.
set -euo pipefail
umask 022

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=credo-lib.sh
. "$HERE/credo-lib.sh"
export CREDO_DIR CREDO_COMPOSE_EXTRA

CREDO_WEB_DIR="${CREDO_WEB_DIR:-/var/www/directroute}"
CREDO_UNIT_DIR="${CREDO_UNIT_DIR:-/etc/systemd/system}"
CREDO_BACKUP_DIR="${CREDO_BACKUP_DIR:-/srv/credo/backups}"
CREDO_HEALTH_URL="${CREDO_HEALTH_URL:-http://127.0.0.1:8080/health}"
CREDO_SITE_URL="${CREDO_SITE_URL-https://davidsexchange.com}"
CREDO_CADDYFILE="${CREDO_CADDYFILE:-/etc/caddy/Caddyfile}"
# The HSTS value the public Caddyfile mode sends (deploy/Caddyfile, snippet "public").
HSTS_EXPECTED="max-age=86400; includeSubDomains"
HISTORY="$CREDO_DIR/deploy-history"
KEEP=5
UNITS="credo-pg-backup.service credo-pg-backup.timer"
TAG_RE='^[0-9a-f]{12}$'

say()  { echo "credo-server: $*"; }
die()  { echo "credo-server: REFUSED: $*" >&2; exit 2; }

[ "$(id -u)" -ne 0 ] || die "run as david, not root (credo.env and the bundles must stay owned by david)"

# --- shared checks ----------------------------------------------------------------------------------------

check_env_file() {
    [ -f "$CREDO_ENV" ] || die "$CREDO_ENV is missing; create it from credo.env.example (owner david, mode 600)"
    local mode owner
    mode=$(stat -c '%a' "$CREDO_ENV")
    owner=$(stat -c '%U' "$CREDO_ENV")
    [ "$owner" = "$(id -un)" ] || die "$CREDO_ENV is owned by $owner, not $(id -un)"
    [ "$mode" = "600" ] || die "$CREDO_ENV has mode $mode; run: chmod 600 $CREDO_ENV"
}

# Every key of the example must be in credo.env (names only, never values).
check_env_keys() {
    local example="$1" missing extra
    missing=$(comm -23 <(credo_env_keys "$example") <(credo_env_keys "$CREDO_ENV") | tr '\n' ' ')
    extra=$(comm -13 <(credo_env_keys "$example") <(credo_env_keys "$CREDO_ENV") | tr '\n' ' ')
    [ -z "${missing// /}" ] || die "credo.env lacks keys of this tag's credo.env.example: ${missing% }. Add them (blank means the default) and run again"
    [ -z "${extra// /}" ] || say "WARN credo.env has keys this tag's example does not name (ignored by Compose): ${extra% }"
}

image_label() {
    docker image inspect -f "{{ index .Config.Labels \"$2\" }}" "credo-exchange:$1" 2>/dev/null | sed 's/<no value>//'
}

# The compatibility floor: the highest credo.flyway and credo.snapshot of every attempt that reached compose up.
floor_of() {
    local label="$1"
    [ -f "$HISTORY" ] || { echo "0 -"; return; }
    awk -v k="$label=" '{ for (i = 4; i <= NF; i++) if (index($i, k) == 1) { v = substr($i, length(k) + 1) + 0;
        if (v >= best) { best = v; tag = $2 } } } END { printf "%d %s\n", best + 0, (tag == "" ? "-" : tag) }' "$HISTORY"
}

# The rollback command for a tag, with --force when the tag is below the compatibility floor.
rollback_cmd() {
    local f s fl ft sn st
    f=$(image_label "$1" credo.flyway); s=$(image_label "$1" credo.snapshot)
    read -r fl ft < <(floor_of flyway)
    read -r sn st < <(floor_of snapshot)
    if [ "${f:-0}" -lt "$fl" ] || [ "${s:-0}" -lt "$sn" ]; then
        echo "deploy/deploy.sh --tag $1 --force (it is below the compatibility floor V$fl, snapshot schema $sn)"
    else
        echo "deploy/deploy.sh --tag $1"
    fi
}

refuse_during_bench() {
    if credo_lock_held "$CREDO_BENCH_LOCK"; then
        die "a benchmark run holds $CREDO_BENCH_LOCK (deploy/bench/bench.sh status <run id>); wait for it to finish"
    fi
}

take_deploy_lock() {
    mkdir -p "$CREDO_DIR"
    exec 9> "$CREDO_DEPLOY_LOCK"
    flock -n 9 || die "another deploy holds $CREDO_DEPLOY_LOCK"
    # Checked while holding the deploy lock; a benchmark checks the deploy lock while holding its own.
    refuse_during_bench
}

history_add() {   # tag status flyway snapshot
    printf '%s %s %s flyway=%s snapshot=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${3:-0}" "${4:-0}" >> "$HISTORY"
}

set_image_tag() {
    local tag="$1" tmp="$CREDO_DIR/.credo.env.tmp"
    ( umask 077
      awk -v t="$tag" 'BEGIN { done = 0 } /^[[:space:]]*IMAGE_TAG=/ { if (!done) print "IMAGE_TAG=" t; done = 1; next }
           { print } END { if (!done) print "IMAGE_TAG=" t }' "$CREDO_ENV" > "$tmp" )
    chmod 600 "$tmp"
    mv -f "$tmp" "$CREDO_ENV"
}

install_file() {   # src dst mode: atomic
    local tmp
    tmp="$(dirname "$2")/.$(basename "$2").new"
    install -m "$3" "$1" "$tmp"
    mv -f "$tmp" "$2"
}

install_bundle() {   # bundle dir: compose.yaml, credo.env.example, postgres-init/, backup/
    local b="$1" unit changed=0
    install_file "$b/compose.yaml" "$CREDO_DIR/compose.yaml" 644
    install_file "$b/credo.env.example" "$CREDO_DIR/credo.env.example" 644
    rm -rf "$CREDO_DIR/.postgres-init.new"
    cp -R "$b/postgres-init" "$CREDO_DIR/.postgres-init.new"
    chmod 755 "$CREDO_DIR/.postgres-init.new" "$CREDO_DIR/.postgres-init.new"/*.sh
    rm -rf "$CREDO_DIR/postgres-init"
    mv -T "$CREDO_DIR/.postgres-init.new" "$CREDO_DIR/postgres-init"
    mkdir -p "$CREDO_DIR/backup"
    install_file "$b/backup/credo-pg-backup.sh" "$CREDO_DIR/backup/credo-pg-backup.sh" 755
    for unit in $UNITS; do
        if cmp -s "$b/backup/$unit" "$CREDO_UNIT_DIR/$unit"; then
            say "unit $unit unchanged; not reinstalled"
        else
            sudo -n install -m 644 -o root -g root "$b/backup/$unit" "$CREDO_UNIT_DIR/$unit"
            say "unit $unit installed"
            changed=1
        fi
    done
    if [ "$changed" -eq 1 ]; then
        sudo -n systemctl daemon-reload
    fi
    # The timer only; the service is never started here, so an install never runs a dump by itself.
    sudo -n systemctl enable --now credo-pg-backup.timer
}

stage_release() {   # tag stage
    local tag="$1" stage="$2" rel="$CREDO_WEB_DIR/releases"
    if [ -d "$rel/$tag" ]; then
        say "release $tag already on the server; kept"
        return
    fi
    [ -f "$stage/release.tgz" ] || die "no release.tgz in $stage"
    rm -rf "$rel/.incoming-$tag"
    mkdir -p "$rel/.incoming-$tag"
    tar -xzf "$stage/release.tgz" --no-same-owner --no-same-permissions -C "$rel/.incoming-$tag"
    [ -f "$rel/.incoming-$tag/index.html" ] || die "release.tgz has no index.html"
    chmod -R u=rwX,go=rX "$rel/.incoming-$tag"
    mv -T "$rel/.incoming-$tag" "$rel/$tag"
    say "release $tag in place (not yet current)"
}

stage_bundle() {   # tag stage
    local tag="$1" stage="$2" dst="$CREDO_DIR/bundles"
    mkdir -p "$dst"
    if [ -d "$dst/$tag" ]; then
        say "bundle $tag already on the server; kept"
        return
    fi
    [ -d "$stage/bundle" ] || die "no bundle in $stage"
    rm -rf "$dst/.incoming-$tag"
    cp -R "$stage/bundle" "$dst/.incoming-$tag"
    mv -T "$dst/.incoming-$tag" "$dst/$tag"
}

prune() {   # current previous
    local current="$1" previous="$2" keep t
    keep=$( { [ -f "$HISTORY" ] && awk '$3 == "ok" { print $2 }' "$HISTORY" | tac | awk '!seen[$0]++' | head -n "$KEEP";
              echo "$current"; echo "$previous"; } | grep -E "$TAG_RE" | sort -u || true)
    is_kept() { grep -qx "$1" <<< "$keep"; }
    while IFS= read -r t; do
        [ -n "$t" ] || continue
        is_kept "$t" && continue
        docker image rm "credo-exchange:$t" > /dev/null && say "pruned image credo-exchange:$t"
    done < <(docker image ls credo-exchange --format '{{.Tag}}' | grep -E "$TAG_RE" || true)
    for t in $(ls "$CREDO_WEB_DIR/releases" 2>/dev/null | grep -E "$TAG_RE" || true); do
        is_kept "$t" || { rm -rf "${CREDO_WEB_DIR:?}/releases/$t"; say "pruned release $t"; }
    done
    for t in $(ls "$CREDO_DIR/bundles" 2>/dev/null | grep -E "$TAG_RE" || true); do
        is_kept "$t" || { rm -rf "${CREDO_DIR:?}/bundles/$t"; say "pruned bundle $t"; }
    done
}

# --- deploy -----------------------------------------------------------------------------------------------

cmd_deploy() {
    local tag="" stage="" rollback=0 force=0 bound=600
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --tag) tag="$2"; shift 2 ;;
            --stage) stage="$2"; shift 2 ;;
            --rollback) rollback=1; shift ;;
            --force) force=1; shift ;;
            --health-timeout) bound="$2"; shift 2 ;;
            *) die "unknown option $1" ;;
        esac
    done
    [[ "$tag" =~ $TAG_RE ]] || die "--tag must be 12 lowercase hex digits"
    [[ "$bound" =~ ^[0-9]+$ ]] && [ "$bound" -ge 10 ] || die "--health-timeout must be a number of seconds, at least 10"

    take_deploy_lock

    # S0 preflight: nothing changes until every check passes.
    check_env_file
    docker image inspect "credo-exchange:$tag" > /dev/null 2>&1 \
        || die "image credo-exchange:$tag is not on the server$( [ "$rollback" -eq 1 ] && echo " (pruned?); deploy that commit again without --tag")"
    local sha flyway snapshot
    sha=$(image_label "$tag" credo.sha)
    [ "${sha:0:12}" = "$tag" ] || die "image credo-exchange:$tag carries credo.sha '${sha:-none}', not a sha starting $tag"
    flyway=$(image_label "$tag" credo.flyway); flyway=${flyway:-0}
    snapshot=$(image_label "$tag" credo.snapshot); snapshot=${snapshot:-0}
    local bundle="$CREDO_DIR/bundles/$tag"
    if [ "$rollback" -eq 1 ]; then
        [ -d "$bundle" ] || die "no bundle for $tag on the server (pruned?); deploy that commit again without --tag"
        [ -d "$CREDO_WEB_DIR/releases/$tag" ] || die "no release $tag on the server (pruned?); deploy that commit again without --tag"
        check_env_keys "$bundle/credo.env.example"
    else
        [ -n "$stage" ] && [ -d "$stage" ] || die "--stage <dir> is required for a deploy"
        check_env_keys "$stage/bundle/credo.env.example"
    fi
    local fl fl_tag sn sn_tag
    read -r fl fl_tag < <(floor_of flyway)
    read -r sn sn_tag < <(floor_of snapshot)
    if [ "$flyway" -lt "$fl" ] || [ "$snapshot" -lt "$sn" ]; then
        local why="credo-exchange:$tag has migrations to V$flyway and snapshot schema $snapshot, below the floor"
        why="$why (V$fl set by $fl_tag, snapshot schema $sn set by $sn_tag). A migration or a newer snapshot cannot be"
        why="$why undone: the older image would run on a newer schema"
        if [ "$force" -eq 1 ]; then
            say "WARN $why; going ahead because of --force"
        else
            die "$why. Add --force only if you know this tag works on them"
        fi
    fi
    local previous old_tag
    # The previous tag is only ever the last one recorded ok other than this one; credo.env's value is never
    # used for it, because it may name a failed attempt or this same tag (P16-11 first-deploy finding 1).
    previous=$(credo_previous_ok "$HISTORY" "$tag")
    old_tag=$(credo_env_get IMAGE_TAG)

    # S1, S2: the tag's bundle and release, then its files in place (the running stack reads them only at up).
    if [ "$rollback" -eq 0 ]; then
        stage_bundle "$tag" "$stage"
        stage_release "$tag" "$stage"
    fi
    install_bundle "$bundle"
    say "installed bundle $tag into $CREDO_DIR"

    # S3: credo.env names the new tag (mode 600, owner kept).
    set_image_tag "$tag"
    say "$(credo_tag_note "$old_tag" "$tag")"

    # S4: bots first, so the maker cancels its ladder against a venue that is still up; then the exchange
    # (graceful shutdown, snapshot), so migrate never runs under a live venue.
    say "stopping bots"
    credo_compose stop bots
    say "stopping exchange"
    credo_compose stop exchange

    # S5, S6: one deadline over compose up and the /health wait.
    history_add "$tag" attempt "$flyway" "$snapshot"
    local start remaining ok=0 rc=0 why=""
    start=$(date +%s)
    say "compose up (bound $bound s)"
    timeout "$bound" bash -c '. "$1"; credo_compose up -d' _ "$HERE/credo-lib.sh" || rc=$?
    if [ "$rc" -eq 124 ]; then
        why="compose up did not finish within the $bound s bound"
    elif [ "$rc" -ne 0 ]; then
        why="compose up failed (exit $rc) after $(( $(date +%s) - start )) s"
    else
        remaining=$((bound - ($(date +%s) - start)))
        [ "$remaining" -lt 1 ] && remaining=1
        if credo_wait_200 "$CREDO_HEALTH_URL" "$remaining"; then
            local running
            running=$(docker inspect -f '{{.Config.Image}}' "$(credo_container exchange)" 2>/dev/null || true)
            if [ "$running" = "credo-exchange:$tag" ]; then ok=1; else why="/health answered, but from $running"; fi
        else
            why="/health did not answer 200 within the $bound s bound"
        fi
    fi
    if [ "$ok" -ne 1 ]; then
        history_add "$tag" failed "$flyway" "$snapshot"
        echo
        say "FAILED: credo-exchange:$tag: $why"
        credo_compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}' || true
        echo "--- last 60 lines of the exchange log ---"
        credo_compose logs --no-color --tail 60 exchange 2>&1 || true
        echo "---"
        say "the static site still points at $(readlink "$CREDO_WEB_DIR/current" 2>/dev/null || echo nothing)"
        say "credo.env names $tag. Nothing was rolled back."
        if [ -n "$previous" ]; then
            say "to roll back:  $(rollback_cmd "$previous")"
        else
            say "no tag other than $tag is recorded ok, so there is nothing to roll back to: fix the cause and deploy again"
        fi
        exit 1
    fi
    say "/health 200 from credo-exchange:$tag after $(( $(date +%s) - start )) s"

    # S7: the static symlink, absolute target, switched atomically.
    ln -sfn "$CREDO_WEB_DIR/releases/$tag" "$CREDO_WEB_DIR/current.tmp"
    mv -T "$CREDO_WEB_DIR/current.tmp" "$CREDO_WEB_DIR/current"
    say "current -> $(readlink "$CREDO_WEB_DIR/current")"

    # S8: history, pruning, the previous tag.
    history_add "$tag" ok "$flyway" "$snapshot"
    prune "$tag" "$previous"
    if [ -n "$stage" ] && [ -d "$stage" ]; then rm -rf "$stage"; fi
    echo
    say "DEPLOYED $tag"
    if [ -n "$previous" ]; then
        say "previous tag: $previous (roll back with: $(rollback_cmd "$previous"))"
    else
        say "previous tag: none (no tag other than $tag is recorded ok)"
    fi
}

# --- restart-exchange -------------------------------------------------------------------------------------

container_state() { docker inspect -f '{{.State.Status}}' "$1" 2>/dev/null || echo missing; }

restart_failed() {   # why, exchange container, since (RFC 3339)
    echo
    say "FAILED: restart-exchange: $1"
    credo_compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}' || true
    echo "--- last 60 lines of the exchange log since the restart began ---"
    docker logs --since "$3" --tail 60 "$2" 2>&1 || true
    echo "---"
    say "nothing else was restarted or rolled back; the bots keep reconnecting on their own"
    exit 1
}

# The timing evidence of one restart: the venue's RESEND lines and the bots' catch-ups of the new process,
# and the outbound journal's size (every RESEND reads it from its start).
restart_report() {   # exchange, bots, since (the new process's StartedAt)
    local venue bots_log health s r
    venue=$(docker logs --since "$3" "$1" 2>&1 || true)
    bots_log=$(docker logs --since "$3" "$2" 2>&1 || true)
    echo
    echo "INFO  RESEND completions (venue, since $3):"
    credo_resend_lines <<< "$venue" | sed 's/^/        /'
    echo "INFO  RESEND refusals, pool full (venue): $(credo_resend_busy_lines <<< "$venue" | wc -l)"
    credo_resend_busy_lines <<< "$venue" | sed 's/^/        /'
    echo "INFO  catch-ups (bots, since $3):"
    credo_catchup_lines <<< "$bots_log" | sed 's/^/        /'
    health=$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)
    s=$(credo_outbound_field "$health" startPosition)
    r=$(credo_outbound_field "$health" recordedPosition)
    if [ -n "$s" ] && [ -n "$r" ]; then
        echo "INFO  journal.outbound (/health, as last computed by the retention thread): startPosition $s," \
            "recordedPosition $r: $((r - s)) bytes ($(( (r - s) / 1048576 )) MiB) read by every full RESEND"
    else
        echo "INFO  journal.outbound: not in /health"
    fi
}

cmd_restart_exchange() {
    local bound=600 catchup=600
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --health-timeout) bound="${2:-}"; shift 2 ;;
            --catchup-timeout) catchup="${2:-}"; shift 2 ;;
            *) die "unknown option $1" ;;
        esac
    done
    [[ "$bound" =~ ^[0-9]+$ ]] && [ "$bound" -ge 10 ] || die "--health-timeout must be a number of seconds, at least 10"
    [[ "$catchup" =~ ^[0-9]+$ ]] && [ "$catchup" -ge 1 ] || die "--catchup-timeout must be a number of seconds, at least 1"

    check_env_file
    take_deploy_lock

    # Preflight: nothing changes until it passes.
    local ex bots code health exempt image bots_started cfg_tag state
    ex=$(credo_container exchange)
    bots=$(credo_container bots)
    [ -n "$ex" ] || die "there is no exchange container; deploy first"
    [ -n "$bots" ] || die "there is no bots container; deploy first"
    state=$(container_state "$ex")
    [ "$state" = running ] || die "the exchange is $state, not running; restart-exchange restarts a running venue only"
    state=$(container_state "$bots")
    [ "$state" = running ] || die "the bots are $state, not running, so no bot would catch up; start them first"
    code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$CREDO_HEALTH_URL" || true)
    [ "$code" = 200 ] || die "/health answers ${code:-nothing} on $CREDO_HEALTH_URL, not 200; investigate before restarting"
    health=$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)
    exempt=$(grep -o '"connections":{"public":[0-9]*,"exempt":[0-9]*' <<< "$health" | sed -n 's/.*"exempt":\([0-9]*\).*/\1/p' || true)
    [ -n "$exempt" ] || die "/health has no access.connections.exempt"
    [ "$exempt" -ge 1 ] || die "no bot is connected (access.connections.exempt 0), so no bot would catch up"
    image=$(docker inspect -f '{{.Config.Image}}' "$ex")
    bots_started=$(started_at "$bots")
    cfg_tag=$(credo_env_get IMAGE_TAG)
    [ "$image" = "credo-exchange:$cfg_tag" ] \
        || say "WARN the exchange runs $image while credo.env names ${cfg_tag:-nothing}; a restart keeps $image"
    say "restart-exchange of $image: exchange ${ex:0:12}, bots ${bots:0:12} left running ($exempt connected)"

    # The restart: compose restart names the exchange only (no migrate, no recreate, the bots untouched).
    # Docker sends SIGTERM, waits up to the stop timeout (stop_grace_period, 60 s) for the shutdown snapshot,
    # and starts the same container only once the old process has exited.
    local t0 t0_unix start rc=0 remaining up_s new_ex new_image started
    t0=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
    t0_unix=$(date +%s)
    start=$t0_unix
    say "compose restart exchange (bound $bound s over the restart and the /health wait)"
    timeout "$bound" bash -c '. "$1"; credo_compose restart exchange' _ "$HERE/credo-lib.sh" || rc=$?
    if [ "$rc" -eq 124 ]; then restart_failed "compose restart did not finish within the $bound s bound" "$ex" "$t0"
    elif [ "$rc" -ne 0 ]; then restart_failed "compose restart failed (exit $rc)" "$ex" "$t0"; fi
    remaining=$((bound - ($(date +%s) - start)))
    [ "$remaining" -lt 1 ] && remaining=1
    credo_wait_200 "$CREDO_HEALTH_URL" "$remaining" || restart_failed "/health did not answer 200 within the $bound s bound" "$ex" "$t0"
    up_s=$(( $(date +%s) - start ))
    new_ex=$(credo_container exchange)
    new_image=$(docker inspect -f '{{.Config.Image}}' "$new_ex" 2>/dev/null || true)
    [ "$new_ex" = "$ex" ] || restart_failed "the exchange container changed (${ex:0:12} to ${new_ex:0:12})" "$new_ex" "$t0"
    [ "$new_image" = "$image" ] || restart_failed "the exchange runs $new_image, not $image" "$ex" "$t0"
    started=$(started_at "$ex")
    say "/health 200 after $up_s s; same container, same image; the new process started at $started"

    # The old process's end: its exit code (143 is the JVM's SIGTERM exit) and its shutdown lines.
    local code_old
    code_old=$(docker events --since "$t0_unix" --until "$(date +%s)" --filter "container=$ex" --filter event=die \
        --format '{{index .Actor.Attributes "exitCode"}}' 2>/dev/null | head -n 1 || true)
    case "$code_old" in
        143|0) say "the old process exited $code_old (graceful shutdown)" ;;
        "") say "WARN no exit event found for the old process" ;;
        *) say "WARN the old process exited $code_old, not 143: not a graceful shutdown (137 is a kill after the stop timeout), so no shutdown snapshot" ;;
    esac
    docker logs --since "$t0" --until "$started" "$ex" 2>&1 \
        | grep -oE "Snapshot written: [^ ]+ \([0-9]+ bytes\)|Snapshot [^ ]+ already exists and is valid; kept|Shutdown (complete|incomplete)[^\"]*" \
        | sed 's/^/        stop:  /' || true
    docker logs --since "$started" "$ex" 2>&1 \
        | grep -oE "Using snapshot [^ ]+: inbound seq [0-9]+, [0-9]+ resting orders|Recovered in [0-9]+ ms: [^,]*, replayed [0-9]+ commands" \
        | sed 's/^/        start: /' || true

    await_catchups "$ex" "$bots" "$t0" "$started" "$exempt" "$catchup" "$bots_started" || exit 1
    say "RESTARTED the exchange ($image); /health 200 after $up_s s, every bot caught up"
}

# Shared by restart-exchange and crash-exchange: waits for every connected bot's catch-up line after the new
# process started, prints the timing report, and confirms the bots container is the same one. Returns 1 on
# any failure (a bot not caught up within the bound, or the bots container changed).
await_catchups() {   # exchange, bots, t0 (RFC 3339, before the restart), started (new StartedAt), exempt, bound, bots_started
    local ex="$1" bots="$2" t0="$3" started="$4" exempt="$5" catchup="$6" bots_started="$7"
    # Every bot that was connected must log its catch-up after the new process started. The expected names
    # are the bots that logged a lost connection since the restart began; their count is cross-checked with
    # the exempt connections /health showed before it.
    local names n_names done_names n cstart now next timed_out=0
    names=$(docker logs --since "$t0" "$bots" 2>&1 | credo_lost_names | tr '\n' ' ')
    n_names=$(wc -w <<< "$names")
    [ "$n_names" -eq "$exempt" ] \
        || say "WARN $n_names bot(s) logged a lost connection (${names% }) but $exempt were connected; waiting for $exempt catch-ups"
    say "waiting for $exempt catch-up(s) (bound $catchup s)"
    cstart=$(date +%s)
    next=$((cstart + 30))
    while :; do
        done_names=$(docker logs --since "$started" "$bots" 2>&1 | credo_catchup_lines | awk '{ print $1 }' | sort -u | tr '\n' ' ')
        n=$(wc -w <<< "$done_names")
        [ "$n" -ge "$exempt" ] && break
        now=$(date +%s)
        if [ $((now - cstart)) -ge "$catchup" ]; then timed_out=1; break; fi
        if [ "$now" -ge "$next" ]; then
            echo "  waiting for catch-up: $n of $exempt (${done_names% }) after $((now - cstart)) s (bound $catchup s)" >&2
            next=$((now + 30))
        fi
        sleep 2
    done
    local caught_s=$(( $(date +%s) - cstart ))

    restart_report "$ex" "$bots" "$started"

    # The bots' container must be the same one, never restarted.
    local fails=0 b_now b_started
    b_now=$(credo_container bots)
    b_started=$(started_at "$b_now")
    echo
    if [ "$b_now" = "$bots" ] && [ "$b_started" = "$bots_started" ]; then
        say "bots container ${bots:0:12} unchanged (process started $bots_started)"
    else
        say "FAILED: the bots container changed: ${bots:0:12} started $bots_started, now ${b_now:0:12} started ${b_started:-?}"
        fails=1
    fi
    if [ "$timed_out" -eq 1 ]; then
        local missing="" b
        for b in $names; do
            grep -qw "$b" <<< "$done_names" || missing="$missing $b"
        done
        say "FAILED: $n of $exempt bots caught up within $catchup s; not caught up:${missing:- (names unknown)}"
        echo "--- last 20 WARN lines of the bots since the restart began ---"
        docker logs --since "$t0" "$bots" 2>&1 | grep WARN | tail -n 20 || true
        echo "---"
        say "nothing was restarted or rolled back; the bots keep retrying on their own; run deploy/deploy.sh --check later"
        fails=1
    else
        say "$n of $exempt bots caught up $caught_s s after /health 200"
    fi
    [ "$fails" -eq 0 ]
}

# --- crash-exchange (P16-13 acceptance item 5) ------------------------------------------------------------

# A SIGKILL from the host to the exchange container's main process. `docker kill` would count as a manual stop
# and leave the venue down (Session G finding 1); a process killed from outside counts as a crash, so the
# restart policy (unless-stopped) starts the same container again. This script never starts anything itself.

crash_failed() {   # why, exchange container, since
    echo
    say "FAILED: crash-exchange: $1"
    credo_compose ps --format 'table {{.Service}}\t{{.State}}\t{{.Status}}' || true
    echo "--- last 60 lines of the exchange log since the kill ---"
    docker logs --since "$3" --tail 60 "$2" 2>&1 || true
    echo "---"
    say "to bring the stack back:  deploy/deploy.sh --tag $(credo_env_get IMAGE_TAG)"
    exit 1
}

# The host PID of a container's main process, checked: alive, a java process, and in that container's cgroup.
exchange_pid() {   # container
    local pid
    pid=$(docker inspect -f '{{.State.Pid}}' "$1" 2>/dev/null || true)
    [[ "$pid" =~ ^[0-9]+$ ]] && [ "$pid" -gt 1 ] || return 1
    [ "$(ps -o comm= -p "$pid" 2>/dev/null | tr -d ' ')" = java ] || return 1
    grep -q "$1" "/proc/$pid/cgroup" 2>/dev/null || return 1
    echo "$pid"
}

cmd_crash_exchange() {
    local bound=600 catchup=600 yes=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --health-timeout) bound="${2:-}"; shift 2 ;;
            --catchup-timeout) catchup="${2:-}"; shift 2 ;;
            --yes) yes=1; shift ;;
            *) die "unknown option $1" ;;
        esac
    done
    [ "$yes" -eq 1 ] || die "crash-exchange kills the live venue with SIGKILL; add --yes to confirm"
    [[ "$bound" =~ ^[0-9]+$ ]] && [ "$bound" -ge 10 ] || die "--health-timeout must be a number of seconds, at least 10"
    [[ "$catchup" =~ ^[0-9]+$ ]] && [ "$catchup" -ge 1 ] || die "--catchup-timeout must be a number of seconds, at least 1"

    check_env_file
    take_deploy_lock

    # Preflight: nothing changes until it passes.
    local ex bots state code health exempt image bots_started restarts started pid seq_before session
    ex=$(credo_container exchange)
    bots=$(credo_container bots)
    [ -n "$ex" ] || die "there is no exchange container; deploy first"
    [ -n "$bots" ] || die "there is no bots container; deploy first"
    state=$(container_state "$ex")
    [ "$state" = running ] || die "the exchange is $state, not running"
    state=$(container_state "$bots")
    [ "$state" = running ] || die "the bots are $state, not running, so no bot would catch up; start them first"
    code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$CREDO_HEALTH_URL" || true)
    [ "$code" = 200 ] || die "/health answers ${code:-nothing} on $CREDO_HEALTH_URL, not 200; investigate first"
    health=$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)
    exempt=$(credo_obj_field "$health" connections exempt)
    [ -n "$exempt" ] || die "/health has no access.connections.exempt"
    [ "$exempt" -ge 1 ] || die "no bot is connected (access.connections.exempt 0), so no bot would catch up"
    [ "$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$ex")" = unless-stopped ] \
        || die "the exchange's restart policy is not unless-stopped, so nothing would bring it back"
    image=$(docker inspect -f '{{.Config.Image}}' "$ex")
    restarts=$(docker inspect -f '{{.RestartCount}}' "$ex")
    started=$(started_at "$ex")
    bots_started=$(started_at "$bots")
    pid=$(exchange_pid "$ex") || die "cannot identify the exchange's main process (host PID, java, in its cgroup)"
    sudo -n kill -0 "$pid" 2>/dev/null \
        || die "sudo -n kill cannot signal PID $pid without a password; david needs a NOPASSWD sudoers entry for /usr/bin/kill"
    session=$(grep -o '"session":"[A-Z]*"' <<< "$health" | head -n 1 | sed 's/.*:"\([A-Z]*\)"/\1/')
    say "crash-exchange of $image: exchange ${ex:0:12} (host PID $pid, restart count $restarts), bots ${bots:0:12} left running ($exempt connected), session ${session:-?}"

    # The kill. The PID and the container's start time are read again just before it, so a process that changed
    # in between is never signalled.
    local t0 t0_unix pid2
    seq_before=$(credo_json_num "$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)" appliedInboundSeq)
    pid2=$(exchange_pid "$ex") || die "the exchange's main process changed before the kill; nothing was done"
    [ "$pid2" = "$pid" ] && [ "$(started_at "$ex")" = "$started" ] || die "the exchange's main process changed before the kill; nothing was done"
    t0=$(date -u +%Y-%m-%dT%H:%M:%S.%NZ)
    t0_unix=$(date +%s)
    sudo -n kill -9 "$pid" || die "sudo -n kill -9 $pid failed; nothing was done"
    say "SIGKILL sent to host PID $pid at $t0 (appliedInboundSeq before the kill: ${seq_before:-?})"

    # The restart policy must start the same container again: RestartCount rises within 60 s.
    local now new_restarts=$restarts new_started
    while :; do
        new_restarts=$(docker inspect -f '{{.RestartCount}}' "$ex" 2>/dev/null || echo "$restarts")
        new_started=$(started_at "$ex")
        [ "$new_restarts" -gt "$restarts" ] && [ "$new_started" != "$started" ] && [ "$(container_state "$ex")" = running ] && break
        now=$(date +%s)
        [ $((now - t0_unix)) -lt 60 ] || crash_failed "the restart policy did not restart the container within 60 s (state $(container_state "$ex"), restart count $new_restarts)" "$ex" "$t0"
        sleep 1
    done
    say "restart policy: restart count $restarts -> $new_restarts, same container ${ex:0:12}, new process started $new_started"
    local remaining=$((bound - ($(date +%s) - t0_unix)))
    [ "$remaining" -lt 1 ] && remaining=1
    credo_wait_200 "$CREDO_HEALTH_URL" "$remaining" || crash_failed "/health did not answer 200 within the $bound s bound" "$ex" "$t0"
    local up_s=$(( $(date +%s) - t0_unix ))
    [ "$(credo_container exchange)" = "$ex" ] || crash_failed "the exchange container changed" "$ex" "$t0"
    say "/health 200 $up_s s after the kill"

    # Evidence that the policy, not this script, restarted it: the die event's exit code, and no kill, stop,
    # restart or start request from a client (the restart policy's own start emits a start event too, so only
    # the count of client requests that could have caused it is checked: kill, stop and restart).
    local events die_code client_events
    events=$(docker events --since "$t0_unix" --until "$(date +%s)" --filter "container=$ex" \
        --format '{{.Action}} {{index .Actor.Attributes "exitCode"}}' 2>/dev/null || true)
    die_code=$(awk '$1 == "die" { print $2; exit }' <<< "$events")
    client_events=$(awk '$1 == "kill" || $1 == "stop" || $1 == "restart" { n++ } END { print n + 0 }' <<< "$events")
    say "daemon events since the kill, health-check execs left out: $(awk '$1 !~ /^exec_/ { print $1 }' <<< "$events" | tr '\n' ' ')(die exit code ${die_code:-?}; kill, stop or restart requests: $client_events)"
    [ "$die_code" = 137 ] || crash_failed "the old process's exit code was ${die_code:-unknown}, not 137 (SIGKILL)" "$ex" "$t0"
    [ "$client_events" -eq 0 ] || crash_failed "the daemon saw a kill, stop or restart request in the window, so the restart cannot be credited to the policy" "$ex" "$t0"
    [ "$new_restarts" -eq $((restarts + 1)) ] || say "WARN the restart count rose by $((new_restarts - restarts)), not 1 (a refused start was retried by the policy)"

    # The recovery: the 11 s retry (P15-5) when the Archive's heartbeat was still fresh, the snapshot used and the
    # replay, and the sequence continuing from at least where it was.
    local logs rec rec_to seq_after
    logs=$(docker logs --since "$t0" "$ex" 2>&1 || true)
    if grep -q "heartbeat in .* is fresh" <<< "$logs"; then
        say "the new process found a fresh heartbeat and retried once after the 11 s delay (P15-5)"
    else
        say "the new process started without the 11 s retry (no fresh heartbeat)"
    fi
    grep -oE "Using snapshot [^ ]+: inbound seq [0-9]+, [0-9]+ resting orders" <<< "$logs" | tail -n 1 | sed 's/^/        start: /' || true
    rec=$(grep -oE "Recovered in [0-9]+ ms: [^,]*, replayed [0-9]+ commands \(seq [0-9]+ to [0-9]+\)" <<< "$logs" | tail -n 1 || true)
    [ -n "$rec" ] || crash_failed "no recovery line in the new process's log" "$ex" "$t0"
    echo "        start: $rec"
    rec_to=$(sed -E 's/.* to ([0-9]+)\)$/\1/' <<< "$rec")
    if [ -n "$seq_before" ] && [ "$rec_to" -ge "$seq_before" ]; then
        say "recovered through seq $rec_to, at or above the $seq_before applied before the kill: nothing acknowledged was lost"
    else
        crash_failed "recovered through seq $rec_to, below the ${seq_before:-?} applied before the kill" "$ex" "$t0"
    fi

    new_started=$(started_at "$ex")
    await_catchups "$ex" "$bots" "$t0" "$new_started" "$exempt" "$catchup" "$bots_started" || exit 1
    seq_after=$(credo_json_num "$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)" appliedInboundSeq)
    if [ -n "$seq_after" ] && [ "$seq_after" -gt "$rec_to" ]; then
        say "matching continues: appliedInboundSeq $seq_after (recovered through $rec_to)"
    else
        say "WARN appliedInboundSeq ${seq_after:-?} has not moved past $rec_to yet (the session may be closed)"
    fi
    say "CRASHED AND RECOVERED the exchange ($image); /health 200 $up_s s after the kill, every bot caught up"
}

# --- bounce-postgres (P16-13 acceptance item 7) -----------------------------------------------------------

PG_STOPPED=0
pg_restore_on_exit() {
    if [ "$PG_STOPPED" -eq 1 ]; then
        echo "credo-server: postgres was stopped by this run; starting it again" >&2
        credo_compose start postgres >&2 || true
        PG_STOPPED=0
    fi
}

api_probe() {   # prints "<code> <error or ok>" for /api/reports/daily of a date on the host port
    local out code err
    out=$(curl -s -m 15 -w '\n%{http_code}' "${CREDO_HEALTH_URL%/health}/api/reports/daily?date=$1" || true)
    code=$(tail -n 1 <<< "$out")
    err=$(grep -o '"error":"[A-Z_]*"' <<< "$out" | head -n 1 | sed 's/.*:"\(.*\)"/\1/')
    echo "${code:-000} ${err:-ok}"
}

cmd_bounce_postgres() {
    local catchup=600 down=30 yes=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --catchup-timeout) catchup="${2:-}"; shift 2 ;;
            --down-seconds) down="${2:-}"; shift 2 ;;
            --yes) yes=1; shift ;;
            *) die "unknown option $1" ;;
        esac
    done
    [ "$yes" -eq 1 ] || die "bounce-postgres stops the live database for a while; add --yes to confirm"
    [[ "$catchup" =~ ^[0-9]+$ ]] && [ "$catchup" -ge 1 ] || die "--catchup-timeout must be a number of seconds, at least 1"
    [[ "$down" =~ ^[0-9]+$ ]] && [ "$down" -ge 10 ] && [ "$down" -le 300 ] || die "--down-seconds must be 10 to 300"

    check_env_file
    take_deploy_lock

    local now_hhmm="${CREDO_NOW_UTC:-$(date -u +%H%M)}"
    credo_in_quiet_window "$now_hhmm" \
        && die "it is $now_hhmm UTC, inside 03:50 to 06:30 UTC (the calendar roll, clearing and the 06:15 backup); run it later"

    # Preflight.
    local pg ex bots health code session bdate rstate lag
    pg=$(credo_container postgres); ex=$(credo_container exchange); bots=$(credo_container bots)
    [ -n "$pg" ] && [ -n "$ex" ] && [ -n "$bots" ] || die "postgres, exchange and bots must all exist; deploy first"
    [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$pg")" = healthy ] || die "postgres is not healthy"
    [ "$(container_state "$ex")" = running ] || die "the exchange is not running"
    [ "$(container_state "$bots")" = running ] || die "the bots are not running, so no matching would show"
    code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$CREDO_HEALTH_URL" || true)
    [ "$code" = 200 ] || die "/health answers ${code:-nothing}, not 200"
    health=$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)
    session=$(grep -o '"session":"[A-Z]*"' <<< "$health" | head -n 1 | sed 's/.*:"\([A-Z]*\)"/\1/')
    [ "$session" = OPEN ] || die "the session is ${session:-unknown}, not OPEN, so the bots do not trade and matching cannot be shown"
    bdate=$(credo_json_num "$health" businessDate)
    # The Reporter commits in batches (every 200 ms under load), so lag 0 is waited for, up to 30 s.
    local w=0
    while :; do
        rstate=$(credo_obj_field "$health" reporter state)
        lag=$(credo_obj_field "$health" reporter outboundLag)
        [ "$rstate" = RUNNING ] && [ "$lag" = 0 ] && break
        [ "$w" -lt 30 ] || die "the Reporter is ${rstate:-absent} with outboundLag ${lag:-?} after 30 s; it must be RUNNING with lag 0 first"
        sleep 1; w=$((w + 1))
        health=$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)
    done
    # Compose must stop postgres alone: a stop that cascaded to the exchange would stop the live venue.
    local plan
    plan=$(credo_compose --dry-run stop postgres 2>&1 || true)
    if grep -qiE 'exchange|bots|migrate' <<< "$plan" || ! grep -qi postgres <<< "$plan"; then
        die "compose stop postgres would not stop postgres alone (dry run: $(tr '\n' ' ' <<< "$plan")); nothing was done"
    fi
    local ex_started bots_started ex_restarts
    ex_started=$(started_at "$ex"); bots_started=$(started_at "$bots")
    ex_restarts=$(docker inspect -f '{{.RestartCount}}' "$ex")
    say "bounce-postgres: session OPEN for $bdate, Reporter RUNNING lag 0, compose dry run stops postgres only"

    # Down.
    trap pg_restore_on_exit EXIT
    trap 'exit 130' INT TERM HUP
    local fails=0 t_stop
    t_stop=$(date +%s)
    PG_STOPPED=1
    timeout 60 bash -c '. "$1"; credo_compose stop postgres' _ "$HERE/credo-lib.sh" > /dev/null 2>&1 \
        || { say "FAILED: compose stop postgres did not finish within 60 s"; exit 1; }
    say "postgres stopped after $(( $(date +%s) - t_stop )) s; watching the venue for $down s"
    local first_seq last_seq h api i=0 ok_api=0 n_api=0 bad_health=0 states=""
    first_seq=$(credo_json_num "$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)" appliedInboundSeq)
    local tend=$(( $(date +%s) + down ))
    while [ "$(date +%s)" -lt "$tend" ]; do
        sleep 5
        i=$((i + 1))
        code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$CREDO_HEALTH_URL" || true)
        h=$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)
        [ "$code" = 200 ] || bad_health=$((bad_health + 1))
        last_seq=$(credo_json_num "$h" appliedInboundSeq)
        api=$(api_probe "$bdate")
        n_api=$((n_api + 1))
        [ "$api" = "503 DATABASE_UNAVAILABLE" ] && ok_api=$((ok_api + 1))
        rstate=$(credo_obj_field "$h" reporter state)
        states="$states $rstate"
        printf '        +%3d s  /health %s  appliedInboundSeq %s  reporter %s  clearing %s  /api %s\n' "$((i * 5))" \
            "${code:-000}" "${last_seq:-?}" "${rstate:-?}" "$(credo_obj_field "$h" clearing state)" "$api"
    done
    if [ "$bad_health" -eq 0 ]; then row PASS "/health 200 at every sample while postgres was down"
    else row FAIL "/health not 200 at $bad_health sample(s) while postgres was down"; fi
    if [ -n "$first_seq" ] && [ -n "$last_seq" ] && [ "$last_seq" -gt "$first_seq" ]; then
        row PASS "matching continued: appliedInboundSeq $first_seq -> $last_seq while postgres was down"
    else
        row FAIL "appliedInboundSeq did not rise while postgres was down (${first_seq:-?} -> ${last_seq:-?})"
    fi
    if [ "$n_api" -gt 0 ] && [ "$ok_api" -eq "$n_api" ]; then row PASS "/api answered 503 DATABASE_UNAVAILABLE at all $n_api samples"
    else row FAIL "/api answered 503 DATABASE_UNAVAILABLE at $ok_api of $n_api samples"; fi
    grep -qE 'BACKING_OFF' <<< "$states" && row PASS "the Reporter went BACKING_OFF" \
        || row INFO "the Reporter did not show BACKING_OFF in the samples (states:$states)"

    # Up again.
    local t_start
    t_start=$(date +%s)
    credo_compose start postgres > /dev/null 2>&1 || { say "FAILED: compose start postgres"; exit 1; }
    PG_STOPPED=0
    while [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$pg" 2>/dev/null)" != healthy ]; do
        [ $(( $(date +%s) - t_start )) -lt 120 ] || { row FAIL "postgres not healthy within 120 s of the start"; exit 1; }
        sleep 2
    done
    row PASS "postgres healthy $(( $(date +%s) - t_start )) s after the start"
    local caught=0
    while :; do
        h=$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)
        rstate=$(credo_obj_field "$h" reporter state); lag=$(credo_obj_field "$h" reporter outboundLag)
        [ "$rstate" = RUNNING ] && [ "$lag" = 0 ] && { caught=1; break; }
        [ $(( $(date +%s) - t_start )) -lt "$catchup" ] || break
        sleep 2
    done
    if [ "$caught" -eq 1 ]; then row PASS "the Reporter RUNNING with outboundLag 0, $(( $(date +%s) - t_start )) s after the start"
    else row FAIL "the Reporter is ${rstate:-?} with outboundLag ${lag:-?} after $catchup s"; fi
    api=$(api_probe "$bdate")
    [ "${api%% *}" = 200 ] && row PASS "/api/reports/daily?date=$bdate 200 again" || row FAIL "/api after the start: $api"
    echo "INFO  clearing $(credo_obj_field "$h" clearing state); database.retention $(grep -o '"database":{"retention":{[^}]*' <<< "$h" | grep -oE '"(outcome|skipReason)":("[^"]*"|null)' | tr '\n' ' ')"
    echo "INFO  journal.retention $(grep -o '"retention":{"enabled":[^}]*' <<< "$h" | head -n 1 | grep -oE '"(outcome|skipReason)":("[^"]*"|null)' | tr '\n' ' ')"
    if [ "$(started_at "$ex")" = "$ex_started" ] && [ "$(docker inspect -f '{{.RestartCount}}' "$ex")" = "$ex_restarts" ] \
        && [ "$(started_at "$bots")" = "$bots_started" ]; then
        row PASS "exchange and bots untouched (same processes, restart count $ex_restarts)"
    else
        row FAIL "the exchange or the bots restarted during the bounce"
    fi
    echo
    if [ "$FAILS" -eq 0 ]; then echo "bounce-postgres: no FAIL"; else echo "bounce-postgres: $FAILS FAIL"; exit 1; fi
}

# --- flood-report (P16-13 acceptance item 11, the bots' side) ---------------------------------------------

cmd_flood_report() {
    local since="${2:-}"
    # A duration such as 95s (the server's clock, so the PC's clock never matters) or unix seconds.
    [ "${1:-}" = "--since" ] && [[ "$since" =~ ^[0-9]+s?$ ]] || die "usage: flood-report --since <seconds>s | <unix seconds>"
    local bots names
    bots=$(credo_container bots)
    [ -n "$bots" ] || die "no bots container"
    names=$(docker logs --since "$since" "$bots" 2>&1 | credo_lost_names | tr '\n' ' ')
    if [ -z "${names// /}" ]; then
        row PASS "no bot lost its connection since the flood began"
    else
        row FAIL "bots lost their connection since the flood began: ${names% }"
    fi
    [ "$FAILS" -eq 0 ] || exit 1
}

# --- check ------------------------------------------------------------------------------------------------

FAILS=0
row() {   # status text
    printf '%-5s %s\n' "$1" "$2"
    [ "$1" = "FAIL" ] && FAILS=$((FAILS + 1))
    return 0
}

json_num() { credo_json_num "$@"; }   # json key -> first number after "key":

# The daemon's start time of a container's current process (RFC 3339, nanoseconds), or empty. Used as
# `docker logs --since`, so lines of an earlier run of the same container are never read.
started_at() {
    [ -n "$1" ] && docker inspect -f '{{.State.StartedAt}}' "$1" 2>/dev/null || true
}

# The log of a container's current process only (stdout and stderr), or nothing.
logs_since_start() {
    local c="$1" since
    [ -n "$c" ] || return 0
    since=$(started_at "$c")
    [ -n "$since" ] || return 0
    docker logs --since "$since" "$c" 2>&1 || true
}

# --- check: the rows through Caddy (P16-11, P16-13) -------------------------------------------------------

# A curl through Caddy. With SITE_CFG set (the private mode's credentials as a curl config line), the config is
# fed on stdin, so the password is never in argv.
SITE_CFG=""
site_curl() {
    if [ -n "$SITE_CFG" ]; then printf '%s\n' "$SITE_CFG" | curl --config - "$@"
    else curl "$@"; fi
}

# The live access mode: the import line of the live Caddyfile, else what the site answers (401 is private, an
# HSTS header is public).
caddy_live_mode() {
    local mode hdr
    mode=$(credo_caddy_mode "$CREDO_CADDYFILE")
    if [ -n "$mode" ]; then echo "$mode file"; return; fi
    hdr=$(curl -s -o /dev/null -m 10 -D - "$CREDO_SITE_URL/health" 2>/dev/null | tr -d '\r' || true)
    if head -n 1 <<< "$hdr" | grep -q ' 401'; then echo "private response"
    elif grep -qi '^strict-transport-security:' <<< "$hdr"; then echo "public response"
    else echo "unknown response"; fi
}

# The served terminal (item 12): index.html and every /assets/ js and css it references, fetched through Caddy,
# byte-identical to the files of the current release; then those files and the whole release scanned for a
# backend host or an Alpaca name, and for the Alpaca key values themselves (never printed).
bundle_rows() {
    local dir="$CREDO_WEB_DIR/current" tmpd code assets a i=0 bad="" names="" hits bare rc=0
    tmpd=$(mktemp -d)
    code=$(site_curl -s -m 20 -o "$tmpd/index.html" -w '%{http_code}' "$CREDO_SITE_URL/" || true)
    if [ "$code" != 200 ]; then row FAIL "bundle: $CREDO_SITE_URL/ answered ${code:-nothing}"; rm -rf "$tmpd"; return; fi
    assets=$(credo_index_assets < "$tmpd/index.html")
    if [ -z "$assets" ]; then row FAIL "bundle: the served index.html references no /assets/ js or css"; rm -rf "$tmpd"; return; fi
    cmp -s "$tmpd/index.html" "$dir/index.html" || bad="$bad index.html"
    while IFS= read -r a; do
        i=$((i + 1))
        code=$(site_curl -s -m 30 -o "$tmpd/asset$i" -w '%{http_code}' "$CREDO_SITE_URL$a" || true)
        if [ "$code" != 200 ] || ! cmp -s "$tmpd/asset$i" "$dir$a"; then bad="$bad $a($code)"; fi
        names="$names ${a#/assets/}"
    done <<< "$assets"
    if [ -z "$bad" ]; then
        row PASS "bundle: index.html and $i asset(s) served through Caddy are byte-identical to $dir (${names# })"
    else
        row FAIL "bundle: served files differ from $dir or did not answer 200:$bad"
    fi
    hits=$(grep -rlaE 'ALPACA_|APCA-|localhost:[0-9]|127\.0\.0\.1' "$tmpd" "$dir/" 2>/dev/null | sed "s|^$tmpd/|served:|; s|^$dir/|release:|" | tr '\n' ' ' || true)
    if [ -z "$hits" ]; then
        row PASS "bundle: no ALPACA_, APCA-, localhost:<port> or 127.0.0.1 in the served files or in any file of the release"
    else
        row FAIL "bundle: forbidden string in: ${hits% }"
    fi
    bare=$(cat "$tmpd/index.html" "$tmpd"/asset* | grep -aoE 'https?://localhost([^:0-9]|$)' | wc -l | tr -d ' ')
    echo "INFO  bundle: $bare bare http://localhost string(s) in the served files: React Router's URL-parsing base, not a backend host"
    credo_scan_secret_values "$tmpd" "$dir/" || rc=$?
    case "$rc" in
        0) row FAIL "bundle: an Alpaca key value from credo.env appears in the served files or the release (found)" ;;
        1) row PASS "bundle: neither Alpaca key value from credo.env appears in the served files or the release (not found)" ;;
        *) row SKIP "bundle: no Alpaca key value set in credo.env to search for" ;;
    esac
    rm -rf "$tmpd"
}

caddy_rows() {   # password flag, /health body
    local password="$1" health="$2" mode how code hsts bdate body
    read -r mode how < <(caddy_live_mode)
    echo "INFO  Caddy access mode: $mode (from the $how)"
    hsts=$(curl -s -o /dev/null -m 10 -D - "$CREDO_SITE_URL/health" 2>/dev/null | tr -d '\r' \
        | sed -n -E 's/^[Ss]trict-[Tt]ransport-[Ss]ecurity:[[:space:]]*//p' | head -n 1 || true)
    code=$(curl -s -o /dev/null -m 10 -w '%{http_code}' "$CREDO_SITE_URL/health" || true)
    SITE_CFG=""
    case "$mode" in
        private)
            [ "$code" = 401 ] && row PASS "$CREDO_SITE_URL/health 401 without the password" \
                || row FAIL "$CREDO_SITE_URL/health $code without the password (want 401 in private mode)"
            [ -z "$hsts" ] && row PASS "no HSTS header in private mode" || row FAIL "HSTS header '$hsts' in private mode"
            if [ "$password" -ne 1 ]; then
                row SKIP "through Caddy with the password (add --password)"
                return
            fi
            local pw
            read -r -s -p "Site password for credo (not shown, not stored): " pw; echo
            pw=${pw//\\/\\\\}
            pw=${pw//\"/\\\"}
            SITE_CFG="user = \"credo:$pw\""
            unset pw
            code=$(site_curl -s -o /dev/null -m 10 -w '%{http_code}' "$CREDO_SITE_URL/health" || true)
            [ "$code" = 200 ] && row PASS "$CREDO_SITE_URL/health 200 with the password" \
                || row FAIL "$CREDO_SITE_URL/health $code with the password"
            ;;
        public)
            [ "$code" = 200 ] && row PASS "$CREDO_SITE_URL/health 200 without a password (public mode)" \
                || row FAIL "$CREDO_SITE_URL/health $code without a password (want 200 in public mode)"
            [ "$hsts" = "$HSTS_EXPECTED" ] && row PASS "HSTS: $hsts" \
                || row FAIL "HSTS header '${hsts:-none}', want '$HSTS_EXPECTED'"
            [ "$password" -eq 1 ] && echo "INFO  --password is not needed in public mode; not asked"
            ;;
        *)
            row FAIL "Caddy access mode unknown: $CREDO_CADDYFILE has no import private|public line, and $CREDO_SITE_URL/health answered $code${hsts:+ with HSTS}"
            return
            ;;
    esac
    code=$(site_curl -s -o /dev/null -m 10 -w '%{http_code}' "$CREDO_SITE_URL/market/ignition" || true)
    [ "$code" = 200 ] && row PASS "$CREDO_SITE_URL/market/ignition 200" || row FAIL "$CREDO_SITE_URL/market/ignition $code"
    bdate=$(credo_json_num "$health" businessDate)
    if [ -z "$bdate" ]; then
        row SKIP "/api through Caddy: /health has no business date yet"
    else
        body=$(site_curl -s -m 20 -w '\n%{http_code}' "$CREDO_SITE_URL/api/reports/daily?date=$bdate" || true)
        code=$(tail -n 1 <<< "$body")
        if [ "$code" = 200 ] && grep -q '"symbols"' <<< "$body"; then row PASS "$CREDO_SITE_URL/api/reports/daily?date=$bdate 200"
        else row FAIL "$CREDO_SITE_URL/api/reports/daily?date=$bdate $code"; fi
    fi
    bundle_rows
    SITE_CFG=""
}

cmd_check() {
    local password=0
    [ "${1:-}" = "--password" ] && password=1
    check_env_file
    local tag health code
    tag=$(credo_env_get IMAGE_TAG)
    echo "check of credo-exchange:${tag:-<unset>} at $(date -u +%Y-%m-%dT%H:%M:%SZ)"

    # migrate
    local c state
    c=$(credo_container migrate)
    if [ -n "$c" ]; then
        state=$(docker inspect -f '{{.State.Status}} {{.State.ExitCode}}' "$c")
        [ "$state" = "exited 0" ] && row PASS "migrate exited 0" || row FAIL "migrate: $state"
    else
        row FAIL "migrate: no container"
    fi

    # services
    local svc want got image
    for svc in postgres exchange bots; do
        c=$(credo_container "$svc")
        if [ -z "$c" ]; then row FAIL "$svc: no container"; continue; fi
        if [ "$svc" = bots ]; then want=running; got=$(docker inspect -f '{{.State.Status}}' "$c")
        else want=healthy; got=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c"); fi
        image=$(docker inspect -f '{{.Config.Image}}' "$c")
        if [ "$got" != "$want" ]; then row FAIL "$svc: $got (want $want)"
        elif [ "$svc" != postgres ] && [ "$image" != "credo-exchange:$tag" ]; then row FAIL "$svc runs $image, credo.env names $tag"
        else row PASS "$svc $got${svc:+ ($image)}"; fi
    done

    # /health and ignition on the host port
    code=$(curl -s -o /dev/null -m 5 -w '%{http_code}' "$CREDO_HEALTH_URL" || true)
    [ "$code" = 200 ] && row PASS "/health 200 on $CREDO_HEALTH_URL" || row FAIL "/health $code on $CREDO_HEALTH_URL"
    health=$(curl -s -m 5 "$CREDO_HEALTH_URL" || true)
    local base="${CREDO_HEALTH_URL%/health}" ign px date
    ign=$(curl -s -m 10 -w '\n%{http_code}' "$base/market/ignition" || true)
    code=$(tail -n 1 <<< "$ign")
    px=$(json_num "$ign" openPx); date=$(json_num "$ign" date)
    if [ "$code" = 200 ] && [ -n "$px" ] && [ -n "$date" ]; then row PASS "/market/ignition 200: openPx $px, date $date"
    else row FAIL "/market/ignition $code${px:+ openPx $px}"; fi

    # through Caddy, following the live mode (private: basic_auth, no HSTS; public: open, HSTS)
    if [ -z "$CREDO_SITE_URL" ]; then
        row SKIP "through Caddy (no CREDO_SITE_URL)"
    else
        caddy_rows "$password" "$health"
    fi

    # logs of the exchange's current process only (--since its StartedAt): an in-place restart keeps the
    # container, and with it every earlier run's lines (Session G)
    local ex logs since open_line open_date h_date h_session
    ex=$(credo_container exchange)
    since=$(started_at "$ex")
    logs=$(logs_since_start "$ex")
    grep -q "Recovered in [0-9]* ms" <<< "$logs" \
        && row PASS "exchange log since ${since:-?}: recovery ($(grep -o 'Recovered in [0-9]* ms: [^,]*, replayed [0-9]* commands' <<< "$logs" | tail -n 1))" \
        || row FAIL "exchange log since ${since:-?}: no recovery line"
    # The newest session-open line (a roll's "Session open for business date N" or a start's "is open
    # (recovered)"), compared with the business date /health reports. The date is read from that line
    # only: other lines name business dates too (clearing's "Cleared business date N" follows a roll).
    open_line=$(credo_session_open_lines <<< "$logs" | tail -n 1)
    open_date=$(grep -oE '[0-9]{8}' <<< "$open_line" | head -n 1)
    h_date=$(json_num "$health" businessDate)
    h_session=$(grep -o '"session":"[A-Z]*"' <<< "$health" | head -n 1 | sed 's/.*:"\([A-Z]*\)"/\1/')
    if [ "$h_session" != OPEN ]; then
        row SKIP "exchange log: session ${h_session:-unknown} on /health (business date ${h_date:-none}); no open line expected"
    elif [ -z "$open_date" ]; then
        row FAIL "exchange log since ${since:-?}: no session-open line, /health says OPEN for ${h_date:-?}"
    elif [ "$open_date" = "$h_date" ]; then
        row PASS "exchange log: session open ($open_line) = /health business date $h_date"
    else
        row FAIL "exchange log: newest open line is for $open_date ($open_line), /health says $h_date"
    fi

    # access, network, addresses
    local xff conn public exempt gw bots_ip trusted exempt_cfg
    xff=$(json_num "$health" xffFallback)
    conn=$(grep -o '"connections":{"public":[0-9]*,"exempt":[0-9]*' <<< "$health" || true)
    public=$(sed -n 's/.*"public":\([0-9]*\).*/\1/p' <<< "$conn")
    exempt=$(sed -n 's/.*"exempt":\([0-9]*\).*/\1/p' <<< "$conn")
    if [ -z "$xff" ] || [ -z "$public" ]; then row FAIL "access object missing from /health"
    elif [ "$xff" != 0 ]; then row FAIL "access.xffFallback $xff (public connections $public): visitors share one client key"
    elif [ "$public" -lt 1 ]; then row WAIT "access.xffFallback 0 but no public connection: open the site in a browser and re-run"
    else row PASS "access.xffFallback 0 with $public public connection(s)"; fi
    [ "$exempt" = 5 ] && row PASS "access.connections.exempt 5" || row FAIL "access.connections.exempt ${exempt:-missing} (want 5)"
    gw=$(docker network inspect "${CREDO_PROJECT}_credo" -f '{{range .IPAM.Config}}{{.Gateway}}{{end}}' 2>/dev/null || true)
    trusted=$(credo_env_get WS_TRUSTED_PROXIES)
    if [ "$gw" = 172.30.0.1 ] && [ "$gw" = "$trusted" ]; then row PASS "network gateway $gw = WS_TRUSTED_PROXIES"
    else row FAIL "network gateway ${gw:-unknown}, WS_TRUSTED_PROXIES ${trusted:-blank} (want both 172.30.0.1)"; fi
    c=$(credo_container bots)
    bots_ip=$( [ -n "$c" ] && docker inspect -f "{{(index .NetworkSettings.Networks \"${CREDO_PROJECT}_credo\").IPAddress}}" "$c" || true)
    exempt_cfg=$(credo_env_get WS_EXEMPT_ADDRESSES)
    if [ "$bots_ip" = 172.30.0.10 ] && [ "$bots_ip" = "$exempt_cfg" ]; then row PASS "bots at $bots_ip = WS_EXEMPT_ADDRESSES"
    else row FAIL "bots at ${bots_ip:-unknown}, WS_EXEMPT_ADDRESSES ${exempt_cfg:-blank} (want both 172.30.0.10)"; fi

    # disk against df /
    local free df diff tol=$((256 * 1024 * 1024))
    free=$(json_num "$health" freeBytes)
    df=$(df -B1 --output=avail / | tail -n 1 | tr -d ' ')
    if [ -n "$free" ] && [ -n "$df" ]; then
        diff=$(( free > df ? free - df : df - free ))
        [ "$diff" -le "$tol" ] && row PASS "disk.freeBytes $free vs df / $df (differ by $diff, tolerance $tol)" \
            || row FAIL "disk.freeBytes $free vs df / $df (differ by $diff, tolerance $tol)"
    else
        row FAIL "disk.freeBytes or df / missing"
    fi

    # retention objects
    local jr dr
    jr=$(grep -o '"closesSeen":[0-9]*,"lastRunTs":[0-9a-z]*' <<< "$health" || true)
    [ -n "$jr" ] && row PASS "journal.retention present (${jr//\"/})" || row FAIL "journal.retention missing"
    dr=$(grep -o '"database":{"retention":{[^}]*' <<< "$health" | grep -o '"lastRunTs":[0-9a-z]*,"outcome":"[A-Z_]*"' || true)
    [ -n "$dr" ] && grep -q '"purgedThrough"' <<< "$health" && row PASS "database.retention present (${dr//\"/})" \
        || row FAIL "database.retention missing"

    # backup timer
    if systemctl list-timers --all --no-pager 2>/dev/null | grep -q credo-pg-backup.timer \
        && [ "$(systemctl is-enabled credo-pg-backup.timer 2>/dev/null || true)" = enabled ]; then
        row PASS "credo-pg-backup.timer listed and enabled"
    else
        row FAIL "credo-pg-backup.timer not listed or not enabled"
    fi

    # static symlink
    local target
    target=$(readlink "$CREDO_WEB_DIR/current" 2>/dev/null || true)
    if [ "$target" = "$CREDO_WEB_DIR/releases/$tag" ]; then row PASS "current -> $target"
    else row FAIL "current -> ${target:-missing}, want $CREDO_WEB_DIR/releases/$tag"; fi

    # P16-12 evidence: the RESEND reads and the bots' catch-ups of the current containers
    echo
    echo "INFO  last RESEND completions (venue, current process):"
    credo_resend_lines <<< "$logs" | tail -n 5 | sed 's/^/        /'
    echo "INFO  last catch-ups (bots, current process):"
    c=$(credo_container bots)
    logs_since_start "$c" | credo_catchup_lines | tail -n 5 | sed 's/^/        /'
    echo
    if [ "$FAILS" -eq 0 ]; then echo "check: no FAIL"; else echo "check: $FAILS FAIL"; exit 1; fi
}

# --- drill ------------------------------------------------------------------------------------------------

psql_pg() {   # database sql
    docker exec "$(credo_container postgres)" psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -Atc "$2"
}

cmd_drill() {
    local pg dump stamp cut purged q_cr q_ex live_cr rest_cr live_ex rest_ex live_v rest_v
    refuse_during_bench
    pg=$(credo_container postgres)
    [ -n "$pg" ] || die "no postgres container"
    dump=$(ls -1 "$CREDO_BACKUP_DIR"/exchange-*.dump 2>/dev/null | sort | tail -n 1 || true)
    [ -n "$dump" ] || die "no dump in $CREDO_BACKUP_DIR"
    stamp=$(basename "$dump" .dump); stamp=${stamp#exchange-}
    # The dump's start (from its name) minus 5 minutes, so a row the Reporter wrote late is not counted.
    cut=$(( ($(date -u -d "${stamp:0:4}-${stamp:4:2}-${stamp:6:2} ${stamp:9:2}:${stamp:11:2}:${stamp:13:2}" +%s) - 300) * 1000000000 ))
    say "drill on $(basename "$dump"): rows stamped before $(date -u -d "@$((cut / 1000000000))" +%Y-%m-%dT%H:%M:%SZ)"
    psql_pg postgres "DROP DATABASE IF EXISTS exchange_drill" > /dev/null
    trap 'psql_pg postgres "DROP DATABASE IF EXISTS exchange_drill" > /dev/null 2>&1 || true' EXIT
    psql_pg postgres "CREATE DATABASE exchange_drill OWNER exchange" > /dev/null
    docker exec -i "$pg" pg_restore -U postgres -d exchange_drill --no-owner --role=exchange < "$dump"
    purged=$(psql_pg exchange "SELECT coalesce(string_agg(business_date::text, ','), '0') FROM clearing_run WHERE purged_ts IS NOT NULL")
    q_cr="SELECT count(*) FROM clearing_run WHERE closed_ts < $cut"
    q_ex="SELECT count(*) FROM executions WHERE event_ts < $cut AND coalesce(business_date, 0) NOT IN ($purged)"
    live_cr=$(psql_pg exchange "$q_cr"); rest_cr=$(psql_pg exchange_drill "$q_cr")
    live_ex=$(psql_pg exchange "$q_ex"); rest_ex=$(psql_pg exchange_drill "$q_ex")
    live_v=$(psql_pg exchange "SELECT max(version::int) FROM flyway_schema_history WHERE success")
    rest_v=$(psql_pg exchange_drill "SELECT max(version::int) FROM flyway_schema_history WHERE success")
    if [ "$rest_cr" = "$live_cr" ] && [ "$rest_cr" -gt 0 ]; then row PASS "clearing_run before the dump: restored $rest_cr = live $live_cr"
    else row FAIL "clearing_run before the dump: restored $rest_cr, live $live_cr (want equal and non-zero)"; fi
    if [ "$rest_ex" = "$live_ex" ] && [ "$rest_ex" -gt 0 ]; then row PASS "executions before the dump (purged dates left out): restored $rest_ex = live $live_ex"
    else row FAIL "executions before the dump (purged dates left out): restored $rest_ex, live $live_ex (want equal and non-zero)"; fi
    [ "$rest_v" = "$live_v" ] && row PASS "Flyway V$rest_v = V$live_v" || row FAIL "Flyway restored V$rest_v, live V$live_v"
    if [ "$FAILS" -eq 0 ]; then echo "drill: no FAIL"; else echo "drill: $FAILS FAIL"; exit 1; fi
}

case "${1:-}" in
    deploy) shift; cmd_deploy "$@" ;;
    restart-exchange) shift; cmd_restart_exchange "$@" ;;
    crash-exchange) shift; cmd_crash_exchange "$@" ;;
    bounce-postgres) shift; cmd_bounce_postgres "$@" ;;
    check)  shift; cmd_check "$@" ;;
    drill)  shift; cmd_drill "$@" ;;
    flood-report) shift; cmd_flood_report "$@" ;;
    *) echo "usage: credo-server.sh deploy --tag <sha12> --stage <dir> [--rollback] [--force] [--health-timeout s] | restart-exchange [--health-timeout s] [--catchup-timeout s] | crash-exchange --yes [...] | bounce-postgres --yes [...] | check [--password] | drill | flood-report --since <seconds>s" >&2; exit 64 ;;
esac
