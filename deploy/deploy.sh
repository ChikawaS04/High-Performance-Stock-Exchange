#!/usr/bin/env bash
# deploy.sh: deploy, roll back and check the venue on credo-exchange-01 from David's PC (P16-11, D13).
# Run in Git Bash (not PowerShell: Windows PowerShell 5 corrupts binary data piped to ssh).
#
#   deploy/deploy.sh                       build HEAD and deploy it
#   deploy/deploy.sh --tag <sha12>         roll back (or forward) to a tag already on the server; no build
#   deploy/deploy.sh --restart-exchange    restart the exchange alone, the bots left running; wait for their
#                                          catch-up and print the RESEND and catch-up timing lines
#   deploy/deploy.sh --check [--password]  the first-deploy checks, PASS or FAIL per item (follows the Caddy mode)
#   deploy/deploy.sh --drill               restore the newest nightly dump into a scratch database and compare
#   deploy/deploy.sh --crash-exchange --yes    SIGKILL the exchange's main process from the host; the restart
#                                          policy brings it back; wait for /health and the bots (P16-13 item 5)
#   deploy/deploy.sh --bounce-postgres --yes   stop postgres, show matching on and /api 503, start it, wait for the
#                                          Reporter (P16-13 item 7)
#   deploy/deploy.sh --probe-ports         from this PC: 22, 80, 443 open; 8080, 5432, 18080 closed (item 9)
#   deploy/deploy.sh --flood               flood one channel through Caddy while a second client keeps getting
#                                          answers; the password is asked without echo (item 11)
#   deploy/deploy.sh --caddy public|private    install the repo Caddyfile in that mode on the server (P16-13)
#
# Options: --force (with --tag: below the compatibility floor), --health-timeout <s> (default 600),
# --catchup-timeout <s> (with --restart-exchange, --crash-exchange and --bounce-postgres, default 600),
# --down-seconds <s> (with --bounce-postgres: how long postgres stays down, default 30), --yes (confirms
# --crash-exchange and --bounce-postgres), --site <url> and --venue <url> (with --flood: local runs only; --venue
# replaces the ssh tunnel and skips the bots' report),
# --target <user@host> (default $CREDO_TARGET, else david@104.204.69.188), --dry-run (every local step runs
# for real, the image is written to a local file, and each ssh and scp line is printed instead of run).
#
# A deploy builds only from `git archive HEAD` (with core.autocrlf=false, so every file keeps its LF), so
# nothing uncommitted reaches the image or the bundle. It refuses a tree with tracked changes outside
# .idea/workspace.xml and app/frontend/DESIGN_SYSTEM.md, and a HEAD that is not on origin/main. The tag is
# the first 12 hex digits of HEAD. All server work is credo-server.sh, copied with scp and run with one ssh.
set -euo pipefail

TARGET="${CREDO_TARGET:-david@104.204.69.188}"
SSH=/usr/bin/ssh
SCP=/usr/bin/scp
# Keepalives, so a long session (a restart-exchange can hold one for about 20 min) is not dropped by an idle
# NAT or firewall in the middle of its report.
SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=15 -o ServerAliveInterval=30 -o ServerAliveCountMax=4)
ALLOWED_DIRTY=(".idea/workspace.xml" "app/frontend/DESIGN_SYSTEM.md")
TAG_RE='^[0-9a-f]{12}$'

mode=deploy tag="" force=0 dry=0 password=0 bound=600 catchup=600 yes=0 down=30 site="" venue="" caddy_mode=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --tag) mode=rollback; tag="${2:-}"; shift 2 ;;
        --restart-exchange) mode=restart; shift ;;
        --crash-exchange) mode=crash; shift ;;
        --bounce-postgres) mode=bounce; shift ;;
        --probe-ports) mode=probe; shift ;;
        --flood) mode=flood; shift ;;
        --caddy) mode=caddy; caddy_mode="${2:-}"; shift 2 ;;
        --yes) yes=1; shift ;;
        --down-seconds) down="${2:-}"; shift 2 ;;
        --site) site="${2:-}"; shift 2 ;;
        --venue) venue="${2:-}"; shift 2 ;;
        --catchup-timeout) catchup="${2:-}"; shift 2 ;;
        --check) mode=check; shift ;;
        --drill) mode=drill; shift ;;
        --password) password=1; shift ;;
        --force) force=1; shift ;;
        --health-timeout) bound="${2:-}"; shift 2 ;;
        --target) TARGET="${2:-}"; shift 2 ;;
        --dry-run) dry=1; shift ;;
        -h|--help) sed -n '2,31p' "$0"; exit 0 ;;
        *) echo "deploy.sh: unknown option $1 (see --help)" >&2; exit 64 ;;
    esac
done

say() { echo "deploy.sh: $*"; }
die() { echo "deploy.sh: REFUSED: $*" >&2; exit 2; }

# Prints the command in dry-run mode, runs it otherwise.
remote() {
    if [ "$dry" -eq 1 ]; then
        printf 'DRY-RUN:'; printf ' %q' "$@"; echo
    else
        "$@"
    fi
}

REPO="$(git rev-parse --show-toplevel)"
cd "$REPO"
[ "$dry" -eq 1 ] || { [ -x "$SSH" ] && [ -x "$SCP" ]; } || die "Git Bash's $SSH and $SCP are needed (run this in Git Bash)"
case "$mode" in rollback) [[ "$tag" =~ $TAG_RE ]] || die "--tag needs the 12 hex digit tag printed by a deploy" ;; esac
[[ "$bound" =~ ^[0-9]+$ ]] || die "--health-timeout must be a number of seconds"
[[ "$catchup" =~ ^[0-9]+$ ]] || die "--catchup-timeout must be a number of seconds"
[[ "$down" =~ ^[0-9]+$ ]] || die "--down-seconds must be a number of seconds"
case "$mode" in
    crash|bounce) [ "$yes" -eq 1 ] || die "--$( [ "$mode" = crash ] && echo crash-exchange || echo bounce-postgres) acts on the live venue; add --yes to confirm" ;;
    caddy) [ "$caddy_mode" = public ] || [ "$caddy_mode" = private ] || die "--caddy needs public or private" ;;
esac

# --- probe-ports (P16-13 item 9): from this PC, with bash's /dev/tcp and a timeout; nothing runs on the server.
if [ "$mode" = probe ]; then
    host="${TARGET#*@}"
    fails=0
    for spec in 22:open 80:open 443:open 8080:closed 5432:closed 18080:closed; do
        port=${spec%:*} want=${spec#*:}
        probe="timeout 5 bash -c 'exec 3<>/dev/tcp/$host/$port'"
        if [ "$dry" -eq 1 ]; then echo "DRY-RUN: $probe   (want $want)"; continue; fi
        if timeout 5 bash -c "exec 3<>/dev/tcp/$host/$port" 2>/dev/null; then got=open
        else rc=$?; if [ "$rc" -eq 124 ]; then got="closed (no answer in 5 s)"; else got="closed (refused)"; fi; fi
        if [ "${got%% *}" = "$want" ]; then echo "PASS  $host:$port $got"; else echo "FAIL  $host:$port $got (want $want)"; fails=$((fails + 1)); fi
    done
    [ "$dry" -eq 1 ] && { say "dry run: no port was probed"; exit 0; }
    echo "probe-ports: TCP only (443/udp for HTTP/3 is not probed); $fails FAIL"
    [ "$fails" -eq 0 ] || exit 1
    exit 0
fi

WORK="$(mktemp -d)"
cleanup() {
    if [ "$dry" -eq 1 ]; then say "dry run: work folder kept at $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT
STAGE="$WORK/stage"
mkdir -p "$STAGE"
REMOTE_STAGE="/tmp/credo-$mode-$(date -u +%Y%m%d%H%M%S)-$$"

# The server scripts, always from HEAD (git show writes the committed bytes, LF).
git show HEAD:deploy/credo-server.sh > "$STAGE/credo-server.sh"
git show HEAD:deploy/credo-lib.sh > "$STAGE/credo-lib.sh"

# Counts CRs with tr: Git Bash's grep cannot be trusted with a CR pattern (in a script it can match every line).
no_cr() {
    local bad="" f
    while IFS= read -r -d '' f; do
        case "$f" in *.tgz|*.tar.gz) continue ;; esac
        [ "$(tr -cd '\r' < "$f" | wc -c)" -eq 0 ] || bad="$bad $f"
    done < <(find "$1" -type f -print0)
    [ -z "$bad" ] || die "files with CR line endings would reach the server:$bad"
}

run_on_server() {   # extra ssh options..., then -- then the remote command words
    no_cr "$STAGE"
    remote "$SCP" "${SSH_OPTS[@]}" -q -r "$STAGE" "$TARGET:$REMOTE_STAGE"
    remote "$SSH" "${SSH_OPTS[@]}" "$@"
}

case "$mode" in
check)
    cmd="bash $REMOTE_STAGE/credo-server.sh check$( [ "$password" -eq 1 ] && echo ' --password'); rc=\$?; rm -rf $REMOTE_STAGE; exit \$rc"
    if [ "$password" -eq 1 ]; then run_on_server -t "$TARGET" "$cmd"; else run_on_server "$TARGET" "$cmd"; fi
    exit 0 ;;
drill)
    run_on_server "$TARGET" "bash $REMOTE_STAGE/credo-server.sh drill; rc=\$?; rm -rf $REMOTE_STAGE; exit \$rc"
    exit 0 ;;
restart)
    say "restarting the exchange alone on $TARGET (bots left running)"
    run_on_server "$TARGET" "bash $REMOTE_STAGE/credo-server.sh restart-exchange --health-timeout $bound --catchup-timeout $catchup; rc=\$?; rm -rf $REMOTE_STAGE; exit \$rc"
    exit 0 ;;
crash)
    say "SIGKILL to the exchange's main process on $TARGET; the restart policy must bring it back (bots left running)"
    run_on_server "$TARGET" "bash $REMOTE_STAGE/credo-server.sh crash-exchange --yes --health-timeout $bound --catchup-timeout $catchup; rc=\$?; rm -rf $REMOTE_STAGE; exit \$rc"
    exit 0 ;;
bounce)
    say "stopping postgres on $TARGET for $down s, then starting it again"
    run_on_server "$TARGET" "bash $REMOTE_STAGE/credo-server.sh bounce-postgres --yes --down-seconds $down --catchup-timeout $catchup; rc=\$?; rm -rf $REMOTE_STAGE; exit \$rc"
    exit 0 ;;
caddy)
    # The repo Caddyfile and its installer, from HEAD; run with sudo through a terminal (sudo may ask for the
    # server password; the private mode also asks for the site password, both without echo).
    git show HEAD:deploy/Caddyfile > "$STAGE/Caddyfile"
    git show HEAD:deploy/install-caddy.sh > "$STAGE/install-caddy.sh"
    say "installing the Caddyfile in $caddy_mode mode on $TARGET"
    run_on_server -t "$TARGET" "sudo bash $REMOTE_STAGE/install-caddy.sh --mode $caddy_mode --source $REMOTE_STAGE/Caddyfile; rc=\$?; rm -rf $REMOTE_STAGE; exit \$rc"
    exit 0 ;;
flood)
    # The client is Node's built-in WebSocket (Node 22 or later), run on this PC; never staged or shipped.
    site="${site:-https://davidsexchange.com}"
    if [ "$dry" -eq 0 ]; then
        command -v node > /dev/null 2>&1 || die "node is not installed (Node 22 or later is needed)"
        [ "$(node -e 'process.stdout.write(typeof WebSocket)')" = function ] || die "this Node ($(node --version)) has no built-in WebSocket; Node 22 or later is needed"
    fi
    tunnel_pid=""
    flood_cleanup() { [ -n "$tunnel_pid" ] && kill "$tunnel_pid" 2> /dev/null; cleanup; }
    trap flood_cleanup EXIT
    if [ -z "$venue" ]; then
        # The steady client and /health go through an ssh tunnel to the venue's host port, so the steady client
        # arrives from the trusted proxy with its own X-Forwarded-For: a second client key (P16-9).
        lport=$((20000 + RANDOM % 20000))
        venue="http://127.0.0.1:$lport"
        if [ "$dry" -eq 1 ]; then
            remote "$SSH" "${SSH_OPTS[@]}" -N -o ExitOnForwardFailure=yes -L "127.0.0.1:$lport:127.0.0.1:8080" "$TARGET"
        else
            "$SSH" "${SSH_OPTS[@]}" -N -o ExitOnForwardFailure=yes -L "127.0.0.1:$lport:127.0.0.1:8080" "$TARGET" &
            tunnel_pid=$!
            for _ in $(seq 1 20); do
                [ "$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$venue/health" 2> /dev/null || true)" = 200 ] && break
                kill -0 "$tunnel_pid" 2> /dev/null || die "the ssh tunnel to $TARGET did not start (is AllowTcpForwarding on?)"
                sleep 1
            done
            [ "$(curl -s -o /dev/null -m 2 -w '%{http_code}' "$venue/health" || true)" = 200 ] || die "/health did not answer through the ssh tunnel within 20 s"
        fi
        local_run=0
    else
        local_run=1
    fi
    say "flood through $site; steady client and /health on $venue. Close the site's tab in your browser first: it shares this PC's client key."
    if [ "$dry" -eq 1 ]; then
        echo "DRY-RUN: (site password read without echo) | node deploy/flood-client.mjs --site $site --venue $venue"
        [ "$local_run" -eq 0 ] && run_on_server "$TARGET" "bash $REMOTE_STAGE/credo-server.sh flood-report --since <elapsed>s; rc=\$?; rm -rf $REMOTE_STAGE; exit \$rc"
        exit 0
    fi
    read -r -s -p "Site password for credo (Enter if the site is public; not shown, not stored): " pw; echo
    t0=$(date +%s)
    rc=0
    printf '%s\n' "$pw" | node "$REPO/deploy/flood-client.mjs" --site "$site" --venue "$venue" || rc=$?
    unset pw
    if [ "$local_run" -eq 0 ]; then
        since=$(( $(date +%s) - t0 + 30 ))
        run_on_server "$TARGET" "bash $REMOTE_STAGE/credo-server.sh flood-report --since ${since}s; rc=\$?; rm -rf $REMOTE_STAGE; exit \$rc" || rc=1
    else
        say "local run: the bots' report (credo-server.sh flood-report) is not run"
    fi
    exit "$rc" ;;
rollback)
    say "rolling to credo-exchange:$tag (no build, no shipping) on $TARGET"
    run_on_server "$TARGET" bash "$REMOTE_STAGE/credo-server.sh" deploy --tag "$tag" --stage "$REMOTE_STAGE" --rollback \
        --health-timeout "$bound" $( [ "$force" -eq 1 ] && echo --force)
    exit 0 ;;
esac

# --- deploy: refuse an unclean or unpushed HEAD -----------------------------------------------------------
dirty=$(git status --porcelain=v1 --untracked-files=no | cut -c4- | sed 's/.* -> //' | while IFS= read -r p; do
    keep=1
    for a in "${ALLOWED_DIRTY[@]}"; do [ "$p" = "$a" ] && keep=0; done
    [ "$keep" -eq 1 ] && echo "$p"
done || true)
[ -z "$dirty" ] || die "tracked files differ from HEAD (commit or stash them): $(echo "$dirty" | tr '\n' ' ')"
git fetch -q origin main || die "git fetch origin main failed"
git merge-base --is-ancestor HEAD origin/main || die "HEAD is not on origin/main; push it first"
full="$(git rev-parse HEAD)"
tag="${full:0:12}"
say "deploying $tag ($(git log -1 --format=%s HEAD)) to $TARGET"

# --- build from the archive of HEAD -----------------------------------------------------------------------
SRC="$WORK/src"
mkdir -p "$SRC"
git -c core.autocrlf=false -c core.eol=lf archive --format=tar HEAD app/backend app/frontend deploy | tar -x -C "$SRC"
no_cr "$SRC/deploy"
no_cr "$SRC/app/backend/docker"
say "frontend: npm ci and npm run build (with the dist check)"
( cd "$SRC/app/frontend" && npm ci --no-audit --no-fund && npm run build )
[ -f "$SRC/app/frontend/dist/index.html" ] || die "the frontend build produced no dist/index.html"
flyway=$(ls "$SRC/app/backend/src/main/resources/db/migration" | sed -n -E 's/^V([0-9]+)__.*/\1/p' | sort -n | tail -n 1)
snapshot=$(grep -o -E 'version="[0-9]+"' "$SRC/app/backend/src/main/resources/sbe/snapshot-schema.xml" | head -n 1 | tr -dc '0-9')
[ -n "$flyway" ] && [ -n "$snapshot" ] || die "cannot read the migration or snapshot schema version"
say "image credo-exchange:$tag (migrations to V$flyway, snapshot schema $snapshot)"
docker build -q --label "credo.sha=$full" --label "credo.flyway=$flyway" --label "credo.snapshot=$snapshot" \
    -t "credo-exchange:$tag" "$SRC/app/backend" > /dev/null

# --- the stage: bundle and release ------------------------------------------------------------------------
mkdir -p "$STAGE/bundle"
cp "$SRC/deploy/compose.yaml" "$SRC/deploy/credo.env.example" "$STAGE/bundle/"
cp -R "$SRC/deploy/postgres-init" "$SRC/deploy/backup" "$STAGE/bundle/"
tar -czf "$STAGE/release.tgz" -C "$SRC/app/frontend/dist" .
printf 'tag=%s\nsha=%s\nflyway=%s\nsnapshot=%s\n' "$tag" "$full" "$flyway" "$snapshot" > "$STAGE/manifest"
no_cr "$STAGE"

# --- ship the image unless the server has it --------------------------------------------------------------
probe="docker image inspect -f '{{ index .Config.Labels \"credo.sha\" }}' credo-exchange:$tag 2>/dev/null || true"
if [ "$dry" -eq 1 ]; then
    remote "$SSH" "${SSH_OPTS[@]}" "$TARGET" "$probe"
    have=""
else
    have=$("$SSH" "${SSH_OPTS[@]}" "$TARGET" "$probe")
fi
if [ "$have" = "$full" ]; then
    say "the server already has credo-exchange:$tag; no image transfer"
elif [ "$dry" -eq 1 ]; then
    docker save "credo-exchange:$tag" | gzip -1 > "$WORK/image.tar.gz"
    say "dry run: image written to $WORK/image.tar.gz ($(wc -c < "$WORK/image.tar.gz") bytes)"
    printf 'DRY-RUN: docker save credo-exchange:%s | gzip -1 | %q' "$tag" "$SSH"; printf ' %q' "${SSH_OPTS[@]}" "$TARGET" docker load; echo
else
    say "shipping the image (docker save | gzip | ssh docker load)"
    docker save "credo-exchange:$tag" | gzip -1 | "$SSH" "${SSH_OPTS[@]}" "$TARGET" docker load
fi

# --- run the server half ----------------------------------------------------------------------------------
run_on_server "$TARGET" bash "$REMOTE_STAGE/credo-server.sh" deploy --tag "$tag" --stage "$REMOTE_STAGE" \
    --health-timeout "$bound"
[ "$dry" -eq 1 ] && say "dry run: stage at $STAGE"
exit 0
