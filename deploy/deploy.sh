#!/usr/bin/env bash
# deploy.sh: deploy, roll back and check the venue on credo-exchange-01 from David's PC (P16-11, D13).
# Run in Git Bash (not PowerShell: Windows PowerShell 5 corrupts binary data piped to ssh).
#
#   deploy/deploy.sh                       build HEAD and deploy it
#   deploy/deploy.sh --tag <sha12>         roll back (or forward) to a tag already on the server; no build
#   deploy/deploy.sh --restart-exchange    restart the exchange alone, the bots left running; wait for their
#                                          catch-up and print the RESEND and catch-up timing lines
#   deploy/deploy.sh --check [--password]  the first-deploy checks, PASS or FAIL per item
#   deploy/deploy.sh --drill               restore the newest nightly dump into a scratch database and compare
#
# Options: --force (with --tag: below the compatibility floor), --health-timeout <s> (default 600),
# --catchup-timeout <s> (with --restart-exchange: the wait for every bot's catch-up, default 600),
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

mode=deploy tag="" force=0 dry=0 password=0 bound=600 catchup=600
while [ "$#" -gt 0 ]; do
    case "$1" in
        --tag) mode=rollback; tag="${2:-}"; shift 2 ;;
        --restart-exchange) mode=restart; shift ;;
        --catchup-timeout) catchup="${2:-}"; shift 2 ;;
        --check) mode=check; shift ;;
        --drill) mode=drill; shift ;;
        --password) password=1; shift ;;
        --force) force=1; shift ;;
        --health-timeout) bound="${2:-}"; shift 2 ;;
        --target) TARGET="${2:-}"; shift 2 ;;
        --dry-run) dry=1; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
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
