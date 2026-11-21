#!/usr/bin/env bash
# bench.sh: start, follow and fetch the P16-12 measurement run on credo-exchange-01 from Git Bash.
#
#   deploy/bench/bench.sh start [--stop-venue] [--quick] [--only A,B,C,K,D,E,F] [--journal-busy-spin]
#   deploy/bench/bench.sh status <run id>
#   deploy/bench/bench.sh fetch <run id>      results into bench-results/p16-12/<run id>/ (git-ignored)
#   deploy/bench/bench.sh list
#
# start copies credo-bench.sh, credo-lib.sh and `git archive HEAD app/backend` (LF kept) to the server with
# one scp and starts the run with one ssh; the run detaches there and survives a disconnect.
# Options for every command: --target <user@host> (default $CREDO_TARGET, else david@104.204.69.188) and
# --dry-run (local steps run, ssh and scp lines are printed).
set -euo pipefail

TARGET="${CREDO_TARGET:-david@104.204.69.188}"
SSH=/usr/bin/ssh
SCP=/usr/bin/scp
SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=15)
dry=0
args=()
cmd="${1:-}"
[ "$#" -gt 0 ] && shift
while [ "$#" -gt 0 ]; do
    case "$1" in
        --target) TARGET="$2"; shift 2 ;;
        --dry-run) dry=1; shift ;;
        *) args+=("$1"); shift ;;
    esac
done

say() { echo "bench.sh: $*"; }
die() { echo "bench.sh: REFUSED: $*" >&2; exit 2; }
remote() {
    if [ "$dry" -eq 1 ]; then printf 'DRY-RUN:'; printf ' %q' "$@"; echo; else "$@"; fi
}
id_ok() { [[ "${1:-}" =~ ^[0-9A-Za-z._-]+$ ]] || die "a run id is needed (see: bench.sh list)"; }

REPO="$(git rev-parse --show-toplevel)"
cd "$REPO"

case "$cmd" in
start)
    full="$(git rev-parse HEAD)"
    id="$(date -u +%Y%m%dT%H%M%SZ)-${full:0:12}"
    work="$(mktemp -d)"
    trap '[ "$dry" -eq 1 ] && echo "bench.sh: dry run: stage kept at $work/stage" || rm -rf "$work"' EXIT
    mkdir -p "$work/stage"
    git show HEAD:deploy/bench/credo-bench.sh > "$work/stage/credo-bench.sh"
    git show HEAD:deploy/credo-lib.sh > "$work/stage/credo-lib.sh"
    git -c core.autocrlf=false -c core.eol=lf archive --format=tar.gz -o "$work/stage/src.tgz" HEAD app/backend
    printf 'sha=%s\nsubject=%s\nstarted_from=%s\n' "$full" "$(git log -1 --format=%s HEAD)" "$(hostname)" > "$work/stage/manifest"
    for f in "$work/stage/credo-bench.sh" "$work/stage/credo-lib.sh"; do
        [ "$(tr -cd '\r' < "$f" | wc -c)" -eq 0 ] || die "CR line endings in $f"
    done
    say "run $id from ${full:0:12}"
    remote "$SCP" "${SSH_OPTS[@]}" -q -r "$work/stage" "$TARGET:credo-bench-incoming-$id"
    remote "$SSH" "${SSH_OPTS[@]}" "$TARGET" bash "credo-bench-incoming-$id/credo-bench.sh" start "$id" "${args[@]}"
    say "follow:  deploy/bench/bench.sh status $id"
    say "fetch:   deploy/bench/bench.sh fetch $id"
    ;;
status)
    id_ok "${args[0]:-}"
    remote "$SSH" "${SSH_OPTS[@]}" "$TARGET" bash "credo-bench/runs/${args[0]}/credo-bench.sh" status "${args[0]}"
    ;;
fetch)
    id_ok "${args[0]:-}"
    mkdir -p "bench-results/p16-12"
    remote "$SCP" "${SSH_OPTS[@]}" -q -r "$TARGET:credo-bench/runs/${args[0]}/results" "bench-results/p16-12/${args[0]}"
    say "results in bench-results/p16-12/${args[0]}"
    ;;
list)
    remote "$SSH" "${SSH_OPTS[@]}" "$TARGET" ls -1 credo-bench/runs
    ;;
*)
    sed -n '2,13p' "$0"; exit 64 ;;
esac
