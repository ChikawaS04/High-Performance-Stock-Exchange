#!/usr/bin/env bash
# credo-server.sh: the server half of the deploy flow (P16-11, D13). deploy.sh copies it to the server with
# scp, beside credo-lib.sh, and runs it with one ssh command. It is never pasted into a terminal.
#
#   credo-server.sh deploy --tag <12 hex> --stage <dir> [--rollback] [--force] [--health-timeout <s>]
#   credo-server.sh check [--password]
#   credo-server.sh drill
#
# deploy: preflight (nothing changes until it passes), the tag's bundle and static release, the bundle's
# files installed into /opt/credo, IMAGE_TAG set in credo.env, bots then exchange stopped, compose up under
# one bounded deadline with the /health wait, then the static symlink switch, the history line and pruning.
# A rollback (--rollback) uses the bundle and release already on the server for that tag.
# check: one PASS, FAIL, WAIT or SKIP line per item of the first-deploy checks; exit 1 on any FAIL.
# drill: restores the newest nightly dump into a scratch database and compares it with the live one (P16-6).
#
# Paths, overridable only so the local live check can run this file unchanged (production uses the defaults):
#   CREDO_DIR (/opt/credo), CREDO_WEB_DIR (/var/www/directroute), CREDO_UNIT_DIR (/etc/systemd/system),
#   CREDO_BACKUP_DIR (/srv/credo/backups), CREDO_HEALTH_URL (http://127.0.0.1:8080/health),
#   CREDO_SITE_URL (https://davidsexchange.com; empty skips the Caddy rows), CREDO_COMPOSE_EXTRA (empty).
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

previous_good() {   # the last tag recorded ok that is not $1
    [ -f "$HISTORY" ] || return 0
    awk -v t="$1" '$3 == "ok" && $2 != t { p = $2 } END { print p }' "$HISTORY"
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

    mkdir -p "$CREDO_DIR"
    exec 9> "$CREDO_DIR/.deploy.lock"
    flock -n 9 || die "another deploy holds $CREDO_DIR/.deploy.lock"

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
    local previous
    # The previous tag is the last one recorded ok other than this one: credo.env may name a failed attempt.
    previous=$(previous_good "$tag")
    [ -n "$previous" ] || { previous=$(credo_env_get IMAGE_TAG); [ "$previous" != "$tag" ] || previous=""; }

    # S1, S2: the tag's bundle and release, then its files in place (the running stack reads them only at up).
    if [ "$rollback" -eq 0 ]; then
        stage_bundle "$tag" "$stage"
        stage_release "$tag" "$stage"
    fi
    install_bundle "$bundle"
    say "installed bundle $tag into $CREDO_DIR"

    # S3: credo.env names the new tag (mode 600, owner kept).
    set_image_tag "$tag"
    say "IMAGE_TAG set to $tag (previous: ${previous:-none})"

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
            say "no previous tag (first deploy): fix the cause and deploy again"
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
    say "previous tag: ${previous:-none}$( [ -n "$previous" ] && [ "$previous" != "$tag" ] && echo " (roll back with: $(rollback_cmd "$previous"))")"
}

# --- check ------------------------------------------------------------------------------------------------

FAILS=0
row() {   # status text
    printf '%-5s %s\n' "$1" "$2"
    [ "$1" = "FAIL" ] && FAILS=$((FAILS + 1))
    return 0
}

json_num() {   # json key -> first number after "key":
    grep -o "\"$2\":-\?[0-9]*" <<< "$1" | head -n 1 | sed 's/.*://'
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

    # through Caddy
    if [ -z "$CREDO_SITE_URL" ]; then
        row SKIP "through Caddy (no CREDO_SITE_URL)"
    else
        code=$(curl -s -o /dev/null -m 10 -w '%{http_code}' "$CREDO_SITE_URL/health" || true)
        [ "$code" = 401 ] && row PASS "$CREDO_SITE_URL/health 401 without the password" \
            || row FAIL "$CREDO_SITE_URL/health $code without the password (want 401)"
        if [ "$password" -eq 1 ]; then
            local pw cfg
            read -r -s -p "Site password for credo (not shown, not stored): " pw; echo
            pw=${pw//\\/\\\\}
            pw=${pw//\"/\\\"}
            cfg="user = \"credo:$pw\""
            unset pw
            code=$(printf '%s\n' "$cfg" | curl -s -o /dev/null -m 10 -w '%{http_code}' --config - "$CREDO_SITE_URL/health" || true)
            [ "$code" = 200 ] && row PASS "$CREDO_SITE_URL/health 200 with the password" \
                || row FAIL "$CREDO_SITE_URL/health $code with the password"
            code=$(printf '%s\n' "$cfg" | curl -s -o /dev/null -m 10 -w '%{http_code}' --config - "$CREDO_SITE_URL/market/ignition" || true)
            [ "$code" = 200 ] && row PASS "$CREDO_SITE_URL/market/ignition 200 with the password" \
                || row FAIL "$CREDO_SITE_URL/market/ignition $code with the password"
            unset cfg
        else
            row SKIP "through Caddy with the password (add --password)"
        fi
    fi

    # logs of the current exchange container
    local ex logs
    ex=$(credo_container exchange)
    logs=$( [ -n "$ex" ] && docker logs "$ex" 2>&1 || true)
    grep -q "Recovered in [0-9]* ms" <<< "$logs" && row PASS "exchange log: recovery ($(grep -o 'Recovered in [0-9]* ms' <<< "$logs" | tail -n 1))" \
        || row FAIL "exchange log: no recovery line"
    grep -qE "Session open for business date [0-9]+|Session for business date [0-9]+ is open \(recovered\)" <<< "$logs" \
        && row PASS "exchange log: session open ($(grep -oE 'business date [0-9]+' <<< "$logs" | tail -n 1))" \
        || row FAIL "exchange log: no session-open line"

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
    echo "INFO  last RESEND completions (venue):"
    grep -oE "RESEND fromSeq [0-9]+ to [0-9]+: [0-9]+ frames to [^ ]+ in [0-9]+ ms( \(read [0-9]+ events, [0-9]+ bytes from position [0-9]+\))?" \
        <<< "$logs" | tail -n 5 | sed 's/^/        /' || true
    echo "INFO  last catch-ups (bots):"
    c=$(credo_container bots)
    { [ -n "$c" ] && docker logs "$c" 2>&1 | grep -oE "[A-Za-z]+ caught up through seq [0-9]+ in [0-9]+ ms \(RESEND from seq [0-9]+, [0-9]+ request\(s\)(, offline [0-9]+ ms)?\)" | tail -n 5 | sed 's/^/        /'; } || true
    echo
    if [ "$FAILS" -eq 0 ]; then echo "check: no FAIL"; else echo "check: $FAILS FAIL"; exit 1; fi
}

# --- drill ------------------------------------------------------------------------------------------------

psql_pg() {   # database sql
    docker exec "$(credo_container postgres)" psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -Atc "$2"
}

cmd_drill() {
    local pg dump stamp cut purged q_cr q_ex live_cr rest_cr live_ex rest_ex live_v rest_v
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
    check)  shift; cmd_check "$@" ;;
    drill)  shift; cmd_drill "$@" ;;
    *) echo "usage: credo-server.sh deploy --tag <sha12> --stage <dir> [--rollback] [--force] [--health-timeout s] | check [--password] | drill" >&2; exit 64 ;;
esac
