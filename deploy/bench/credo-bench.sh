#!/usr/bin/env bash
# credo-bench.sh: the P16-12 measurement run on credo-exchange-01. bench.sh copies it to the server (beside
# credo-lib.sh and the source archive of HEAD) and starts it with one ssh command; the run then detaches, so
# it survives an SSH disconnect. Results stay on the server until bench.sh fetches them.
#
#   credo-bench.sh start <run id> [--stop-venue] [--quick] [--only A,B,...] [--journal-busy-spin]
#   credo-bench.sh status <run id>
#
# Blocks (see deploy/bench/README.md): A engine throughput, B pipeline throughput, C journaled end-to-end
# latency, K the same with a cpuset, D the Archive delete blip, E the full-window RESEND read, F one database
# retention pass on a restored scratch copy. Every Maven cell runs in maven:3.9-eclipse-temurin-21 as the
# calling user, against the source copy, with a memory bound, /dev/shm for the Aeron directory, and under a
# per-cell timeout; its scratch Archive is deleted when it ends.
#
# Refuses while the exchange or the bots run, unless --stop-venue: then it stops bots and exchange itself and
# starts them again at the end (also after a failure), waiting for /health 200 under a bound.
set -euo pipefail
umask 022

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../credo-lib.sh
. "$HERE/credo-lib.sh"
export CREDO_DIR CREDO_COMPOSE_EXTRA

BENCH_ROOT="${BENCH_ROOT:-$HOME/credo-bench}"
BENCH_MEMORY="${BENCH_MEMORY:-6g}"
BENCH_IMAGE="${BENCH_IMAGE:-maven:3.9-eclipse-temurin-21}"
CELL_TIMEOUT="${CELL_TIMEOUT:-1200}"
RUN_TIMEOUT="${RUN_TIMEOUT:-14400}"
CREDO_HEALTH_URL="${CREDO_HEALTH_URL:-http://127.0.0.1:8080/health}"
CREDO_BACKUP_DIR="${CREDO_BACKUP_DIR:-/srv/credo/backups}"
VENUE_TIMEOUT=600
MIN_FREE=$((15 * 1024 * 1024 * 1024))
ADD_EXPORTS="--add-exports java.base/jdk.internal.misc=ALL-UNNAMED"
CONTAINER=credo-bench-cell

say() { echo "credo-bench: $(date -u +%H:%M:%S) $*"; }
die() { echo "credo-bench: REFUSED: $*" >&2; exit 2; }

[ "$(id -u)" -ne 0 ] || die "run as david, not root"

venue_running() { credo_service_active exchange || credo_service_active bots; }

free_bytes() { df -B1 --output=avail "$1" | tail -n 1 | tr -d ' '; }

# --- start: checks, then detach ---------------------------------------------------------------------------

cmd_start() {
    local id="$1"; shift
    [[ "$id" =~ ^[0-9A-Za-z._-]+$ ]] || die "bad run id"
    local stop_venue=0 a
    for a in "$@"; do [ "$a" = --stop-venue ] && stop_venue=1; done
    mkdir -p "$BENCH_ROOT/runs" "$BENCH_ROOT/m2"
    exec 9> "$BENCH_ROOT/.lock"
    flock -n 9 || die "another benchmark run holds $BENCH_ROOT/.lock"
    flock -u 9
    if venue_running && [ "$stop_venue" -eq 0 ]; then
        die "the exchange or the bots are running; stop them (docker compose stop bots exchange) or add --stop-venue"
    fi
    [ "$(free_bytes "$BENCH_ROOT")" -ge "$MIN_FREE" ] || die "less than 15 GiB free on the filesystem of $BENCH_ROOT"
    local run="$BENCH_ROOT/runs/$id"
    [ ! -e "$run" ] || die "$run exists"
    mv -T "$HERE" "$run"
    mkdir -p "$run/results"
    say "run $id starts detached; follow it with: deploy/bench/bench.sh status $id"
    setsid nohup bash "$run/credo-bench.sh" run "$id" "$@" > "$run/results/runner.log" 2>&1 < /dev/null &
    echo "started run $id (pid $!)"
}

cmd_status() {
    local run="$BENCH_ROOT/runs/$1"
    [ -d "$run" ] || die "no run $1"
    cat "$run/results/status" 2>/dev/null || echo "no status yet"
    echo "--- runner.log (last 8 lines) ---"
    tail -n 8 "$run/results/runner.log" 2>/dev/null || true
}

# --- run --------------------------------------------------------------------------------------------------

WATCHDOG="" RUN="" RESULTS="" STARTED="" PHASE="" CELL="" DONE=0 FAILED=0 VENUE_NOTE="" STOPPED_VENUE=0

status() {
    printf 'run=%s\nphase=%s\ncell=%s\nstarted=%s\nupdated=%s\ncells_done=%s\ncells_failed=%s\nvenue=%s\n' \
        "$(basename "$RUN")" "$PHASE" "$CELL" "$STARTED" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$DONE" "$FAILED" \
        "${VENUE_NOTE:-untouched}" > "$RESULTS/status.tmp"
    mv -f "$RESULTS/status.tmp" "$RESULTS/status"
}

restart_venue() {
    [ "$STOPPED_VENUE" -eq 1 ] || return 0
    say "starting the venue again (bound $VENUE_TIMEOUT s)"
    if timeout "$VENUE_TIMEOUT" bash -c '. "$1"; credo_compose up -d' _ "$HERE/credo-lib.sh" \
        && credo_wait_200 "$CREDO_HEALTH_URL" "$VENUE_TIMEOUT"; then
        VENUE_NOTE="restarted, /health 200 at $(date -u +%H:%M:%SZ)"
    else
        VENUE_NOTE="RESTART FAILED at $(date -u +%H:%M:%SZ): start it by hand (deploy/deploy.sh --tag <current>)"
    fi
    say "venue: $VENUE_NOTE"
}

on_exit() {
    local rc=$?
    if [ -n "$WATCHDOG" ]; then pkill -P "$WATCHDOG" 2>/dev/null || true; kill "$WATCHDOG" 2>/dev/null || true; fi
    docker kill "$CONTAINER" > /dev/null 2>&1 || true
    drop_bench_db
    restart_venue
    PHASE="finished (exit $rc)"; CELL=""
    status
}

# One container per cell. Arguments: cell name, extra docker options (a string, may be empty), then the command.
cell() {
    local name="$1" extra="$2"; shift 2
    CELL="$name"; status
    say "cell $name"
    mkdir -p "$RUN/scratch/$name"
    local rc=0
    # shellcheck disable=SC2086
    timeout --signal=TERM "$CELL_TIMEOUT" docker run --rm --name "$CONTAINER" --user "$(id -u):$(id -g)" \
        --memory "$BENCH_MEMORY" --shm-size 512m $extra \
        -e MAVEN_CONFIG=/var/maven/.m2 -e HOME=/var/maven \
        -v "$BENCH_ROOT/m2:/var/maven/.m2" -v "$RUN/src/app/backend:/work" -v "$RESULTS:/results" \
        -v "$RUN/scratch/$name:/scratch" -w /work "$BENCH_IMAGE" "$@" > "$RESULTS/$name.log" 2>&1 || rc=$?
    if [ "$rc" -eq 124 ]; then
        docker kill "$CONTAINER" > /dev/null 2>&1 || true
        say "cell $name TIMED OUT after $CELL_TIMEOUT s"
    fi
    rm -rf "${RUN:?}/scratch/$name"
    if [ "$rc" -eq 0 ]; then DONE=$((DONE + 1)); else FAILED=$((FAILED + 1)); say "cell $name failed (exit $rc)"; fi
    status
    return 0
}

MVN_OFFLINE="mvn -B -q -o -Duser.home=/var/maven"

# JMH through its own main, on the test classpath built once.
jmh() {   # name, extra docker options, JMH arguments...
    local name="$1" extra="$2"; shift 2
    cell "$name" "$extra" bash -c 'exec java '"$ADD_EXPORTS"' -cp "target/test-classes:target/classes:$(cat target/cp.txt)" \
        org.openjdk.jmh.Main "$@" -rf json -rff /results/'"$name"'.json \
        -jvmArgsAppend "'"$ADD_EXPORTS"' -Dbench.dir=/scratch -Dbench.aeronDir=/dev/shm"' _ "$@"
}

# A gated JUnit benchmark through Surefire (the P15-13 way), offline after the prepare step.
junit() {   # name, extra docker options, test class, -D properties...
    local name="$1" extra="$2" test="$3"; shift 3
    cell "$name" "$extra" $MVN_OFFLINE test "-Dtest=$test" -Dsurefire.failIfNoSpecifiedTests=false \
        -Dbench.results=/results -Dbench.dir=/scratch -Dbench.aeronDir=/dev/shm "$@"
}

# Latency configurations: engine idle, journal idle, Archive threading, ring wait.
declare -A CFG=(
    [C1]="backoff backoff shared blocking"
    [C2]="busy-spin backoff shared blocking"
    [C3]="busy-spin yielding shared blocking"
    [C4]="backoff yielding shared blocking"
    [C5]="busy-spin backoff dedicated blocking"
    [C6]="backoff backoff dedicated blocking"
    [C7]="busy-spin backoff shared yielding"
    [C8]="busy-spin busy-spin shared blocking"
    [C9]="backoff busy-spin shared blocking"
)

e2e() {   # name, extra docker options, config, mode, more -D properties...
    local name="$1" extra="$2" cfg="$3" mode="$4"; shift 4
    local engine journal archive ring
    read -r engine journal archive ring <<< "${CFG[$cfg]}"
    local sizes=()
    if [ "$QUICK" -eq 1 ]; then sizes=(-De2e.warmup=2000 -De2e.measure=10000)
    elif [ "$mode" = closed ]; then sizes=(-De2e.warmup=20000 -De2e.measure=100000); fi
    junit "$name" "$extra" JournaledEndToEndLatencyBenchmark -De2e.latency=true "-De2e.mode=$mode" \
        "-De2e.idle=$engine" "-Dbench.journalIdle=$journal" "-De2e.archive=$archive" "-Dbench.ringWait=$ring" \
        "-De2e.tag=$name" "${sizes[@]}" "$@"
}

BENCH_DB=exchange_bench

psql_pg() {
    docker exec "$(credo_container postgres)" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -Atc "$1"
}

drop_bench_db() {
    [ "${F_STARTED:-0}" -eq 1 ] || return 0
    psql_pg "DROP DATABASE IF EXISTS $BENCH_DB" > /dev/null 2>&1 || true
}

block_f() {
    CELL="F-retention"; status
    local log="$RESULTS/F-retention.log" pg dump size tag pw
    pg=$(credo_container postgres)
    if [ -z "$pg" ] || ! credo_service_active postgres; then
        say "block F skipped: the postgres container is not running" | tee "$log"; FAILED=$((FAILED + 1)); return 0
    fi
    dump=$(ls -1 "$CREDO_BACKUP_DIR"/exchange-*.dump 2>/dev/null | sort | tail -n 1 || true)
    if [ -z "$dump" ]; then say "block F skipped: no dump in $CREDO_BACKUP_DIR" | tee "$log"; FAILED=$((FAILED + 1)); return 0; fi
    size=$(stat -c %s "$dump")
    if [ "$(free_bytes /)" -lt $((4 * size + 10 * 1024 * 1024 * 1024)) ]; then
        say "block F skipped: less than 4x the dump plus 10 GiB free" | tee "$log"; FAILED=$((FAILED + 1)); return 0
    fi
    tag=$(credo_env_get IMAGE_TAG)
    F_STARTED=1
    {
        say "restoring $(basename "$dump") ($size bytes) into $BENCH_DB"
        psql_pg "DROP DATABASE IF EXISTS $BENCH_DB"
        psql_pg "CREATE DATABASE $BENCH_DB OWNER exchange"
        local t0; t0=$(date +%s)
        docker exec -i "$pg" pg_restore -U postgres -d "$BENCH_DB" --no-owner --role=exchange < "$dump"
        say "restored in $(( $(date +%s) - t0 )) s; one retention pass with DB_RETENTION_DAYS=0 through credo-exchange:$tag retention-once"
        docker exec "$pg" psql -U postgres -d "$BENCH_DB" -Atc "SELECT 'rows before: orders ' || (SELECT count(*) FROM orders)
            || ', executions ' || (SELECT count(*) FROM executions) || ', order_audit ' || (SELECT count(*) FROM order_audit)
            || '; COMPLETE dates ' || (SELECT count(*) FROM clearing_run WHERE status = 'COMPLETE')"
        # The password goes to the container by name from this process's environment, never in an argument.
        pw=$(sed -n -E 's/^[[:space:]]*DB_PASSWORD=(.*)$/\1/p' "$CREDO_ENV" | tail -n 1)
        DB_PASSWORD="$pw" timeout "$CELL_TIMEOUT" docker run --rm --name "$CONTAINER" --network "${CREDO_PROJECT}_credo" \
            --memory 1g -e "DB_URL=jdbc:postgresql://postgres:5432/$BENCH_DB" -e DB_USER=exchange -e DB_PASSWORD \
            -e DB_RETENTION_DAYS=0 -e JAVA_OPTS=-Xmx512m "credo-exchange:$tag" retention-once || say "retention-once exit $?"
        unset pw
        docker exec "$pg" psql -U postgres -d "$BENCH_DB" -Atc "SELECT 'rows after: orders ' || (SELECT count(*) FROM orders)
            || ', executions ' || (SELECT count(*) FROM executions) || ', order_audit ' || (SELECT count(*) FROM order_audit)"
        say "refusal check: retention-once against the live database name must exit 2"
        local rc=0
        timeout 120 docker run --rm --network "${CREDO_PROJECT}_credo" -e "DB_URL=jdbc:postgresql://postgres:5432/exchange" \
            -e DB_USER=exchange -e DB_PASSWORD=unused "credo-exchange:$tag" retention-once || rc=$?
        say "live database name: exit $rc (want 2)"
        psql_pg "DROP DATABASE IF EXISTS $BENCH_DB"
    } > "$log" 2>&1 || FAILED=$((FAILED + 1))
    F_STARTED=0
    DONE=$((DONE + 1)); status
}

wanted() { [ -z "$ONLY" ] || [[ ",$ONLY," == *",$1,"* ]]; }

cmd_run() {
    local id="$1"; shift
    RUN="$BENCH_ROOT/runs/$id"; RESULTS="$RUN/results"; STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    QUICK=0 ONLY="" JBS=0
    local stop_venue=0
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --stop-venue) stop_venue=1; shift ;;
            --quick) QUICK=1; shift ;;
            --only) ONLY="$2"; shift 2 ;;
            --journal-busy-spin) JBS=1; shift ;;
            *) die "unknown option $1" ;;
        esac
    done
    exec 9> "$BENCH_ROOT/.lock"
    flock -n 9 || die "another benchmark run holds $BENCH_ROOT/.lock"
    PHASE=starting; status
    trap on_exit EXIT
    # Bound the whole run: past RUN_TIMEOUT the watchdog ends it (the EXIT trap still restarts the venue).
    ( exec 9>&-; sleep "$RUN_TIMEOUT"; say "run timeout $RUN_TIMEOUT s reached"; kill -TERM $$ ) & WATCHDOG=$!
    trap 'exit 143' TERM

    if venue_running; then
        [ "$stop_venue" -eq 1 ] || die "the venue started meanwhile"
        say "stopping bots, then exchange"
        STOPPED_VENUE=1; VENUE_NOTE="stopped by the run"
        credo_compose stop bots
        credo_compose stop exchange
    fi

    PHASE=prepare; status
    {
        echo "run $id, started $STARTED"
        cat "$RUN/manifest" 2>/dev/null || true
        echo "options: quick=$QUICK only=${ONLY:-all} journal-busy-spin=$JBS memory=$BENCH_MEMORY cell-timeout=$CELL_TIMEOUT"
        echo "--- uname"; uname -a
        echo "--- nproc"; nproc
        echo "--- lscpu"; lscpu 2>/dev/null || true
        echo "--- free"; free -b 2>/dev/null || true
        echo "--- docker"; docker version --format '{{.Server.Version}}' 2>/dev/null || true
    } > "$RESULTS/manifest.txt"
    mkdir -p "$RUN/src"
    tar -xzf "$RUN/src.tgz" -C "$RUN/src"
    CELL=prepare
    # One quick pure test, so Surefire and its JUnit provider are in the cache for the offline cells.
    cell prepare "" mvn -B -q -Duser.home=/var/maven test -Dtest=SpinBudgetTest -Dsurefire.failIfNoSpecifiedTests=false \
        dependency:build-classpath -Dmdep.outputFile=/work/target/cp.txt -Dmdep.includeScope=test
    [ -f "$RUN/src/app/backend/target/cp.txt" ] || { say "prepare failed; see results/prepare.log"; exit 1; }
    docker run --rm --entrypoint nproc --cpuset-cpus=1-3 "$BENCH_IMAGE" > "$RESULTS/cpuset-nproc.txt" 2>&1 || true

    local reps=3 cfgs=(C1 C2 C3 C4 C5 C6 C7) r c m
    [ "$JBS" -eq 1 ] && cfgs+=(C8 C9)
    if [ "$QUICK" -eq 1 ]; then reps=1; cfgs=(C2); fi

    if wanted A; then
        PHASE="A engine throughput"
        if [ "$QUICK" -eq 1 ]; then jmh A-engine "" 'MatchingEngineThroughputBenchmark' -f 1 -wi 1 -i 1 -w 1s -r 1s
        else jmh A-engine "" 'MatchingEngineThroughputBenchmark' -f 3; fi
    fi
    if wanted B && [ "$QUICK" -eq 0 ]; then
        PHASE="B pipeline throughput"
        jmh B-pipeline-journaled "" 'PipelineThroughputBenchmark' -p pipeline=journaled -p idle=busy-spin,backoff \
            -p ringWait=blocking,yielding,busy-spin -p journalIdle=backoff -f 3 -wi 10 -i 10
        jmh B-pipeline-unjournaled "" 'PipelineThroughputBenchmark' -p pipeline=unjournaled -f 3 -wi 10 -i 10
    fi
    if wanted C; then
        PHASE="C end-to-end latency"
        for r in $(seq 1 "$reps"); do
            for c in "${cfgs[@]}"; do
                for m in closed open; do
                    [ "$QUICK" -eq 1 ] && [ "$m" = closed ] && continue
                    e2e "C-$c-$m-r$r" "" "$c" "$m"
                done
            done
        done
    fi
    if wanted K && [ "$QUICK" -eq 0 ]; then
        PHASE="K latency with cpuset 1-3"
        for r in $(seq 1 "$reps"); do
            for c in C1 C2; do
                for m in closed open; do e2e "K-$c-$m-r$r" "--cpuset-cpus=1-3" "$c" "$m"; done
            done
        done
    fi
    if wanted D; then
        PHASE="D Archive delete blip"
        local measure=-De2e.measure=1200000
        [ "$QUICK" -eq 1 ] && measure=-De2e.measure=10000
        for r in $(seq 1 "$reps"); do
            e2e "D-purge-off-r$r" "" C2 open "$measure"
            e2e "D-purge-on-r$r" "" C2 open "$measure" -De2e.purgeEveryMs=1000
        done
    fi
    if wanted E; then
        PHASE="E full-window RESEND read"
        if [ "$QUICK" -eq 1 ]; then junit E-resend "" ResendReadBenchmark -Dresend.read=true -Dresend.mib=4 -Dresend.reads=1 -Dresend.tag=E
        else junit E-resend "" ResendReadBenchmark -Dresend.read=true -Dresend.mib=100,400 -Dresend.reads=5 -Dresend.tag=E; fi
    fi
    if wanted F; then
        PHASE="F database retention pass"
        block_f
    fi
    pkill -P "$WATCHDOG" 2>/dev/null || true; kill "$WATCHDOG" 2>/dev/null || true; WATCHDOG=""
    PHASE=done; CELL=""; status
    say "run $id done: $DONE cell(s), $FAILED failed"
}

case "${1:-}" in
    start)  shift; [ "$#" -ge 1 ] || die "start needs a run id"; cmd_start "$@" ;;
    run)    shift; cmd_run "$@" ;;
    status) shift; cmd_status "${1:?run id}" ;;
    *) echo "usage: credo-bench.sh start <id> [--stop-venue] [--quick] [--only A,B,C,K,D,E,F] [--journal-busy-spin] | status <id>" >&2; exit 64 ;;
esac
