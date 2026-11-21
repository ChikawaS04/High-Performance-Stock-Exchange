#!/usr/bin/env bash
# live-local.sh: the local live check of the deploy and bench scripts on Docker Desktop (P16-11, P16-12).
# Never shipped, never run against the server. Run from Git Bash at the repo root:
#
#   bash deploy/test/live-local.sh setup <stage dir> <image.tar.gz>   helper image, scratch roots, credo.env
#   bash deploy/test/live-local.sh server <args...>                   credo-server.sh <args> in the helper
#   bash deploy/test/live-local.sh bench <args...>                    credo-bench.sh <args> in the helper
#   bash deploy/test/live-local.sh sh '<command>'                     a shell command in the helper, as david
#   bash deploy/test/live-local.sh fake <tag12> [flyway]              an image labelled as another tag, and its stage
#   bash deploy/test/live-local.sh teardown                           stack, volumes, images and scratch removed
#
# The server scripts run unchanged inside a throwaway Linux helper (bash, GNU coreutils, util-linux, curl, sudo,
# the Docker CLI and Compose) as a non-root user "david" with passwordless sudo, talking to Docker Desktop's
# daemon through its socket. The scratch roots live at the same path inside the Docker Desktop VM and the helper
# (/tmp/credo-live), so Compose bind mounts resolve for the daemon. systemctl is a stub that logs its arguments
# (Docker Desktop has no systemd). The overrides are the documented ones of credo-server.sh: CREDO_DIR,
# CREDO_WEB_DIR, CREDO_BACKUP_DIR, CREDO_HEALTH_URL, CREDO_SITE_URL, CREDO_COMPOSE_EXTRA; plus BENCH_ROOT.
# The local stack adds compose.local.yaml (exchange on 127.0.0.1:18080) and compose.live.yaml: a busybox stub of
# the Alpaca snapshot route and a caddy:2 with basic_auth in front of the venue on 127.0.0.1:18081.
set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT=/tmp/credo-live
HELPER=credo-live-helper
REPO="$(git rev-parse --show-toplevel)"

helper_run() {   # docker run options..., -- command...
    local gid
    gid=$(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock "$HELPER" stat -c %g /var/run/docker.sock)
    docker run --rm -i --user david --group-add "$gid" --add-host host.docker.internal:host-gateway \
        -v /var/run/docker.sock:/var/run/docker.sock -v "$ROOT:$ROOT" \
        -e CREDO_DIR="$ROOT/opt/credo" -e CREDO_WEB_DIR="$ROOT/www" -e CREDO_BACKUP_DIR="$ROOT/backups" \
        -e CREDO_HEALTH_URL=http://host.docker.internal:18080/health -e CREDO_SITE_URL=http://host.docker.internal:18081 \
        -e CREDO_COMPOSE_EXTRA="compose.local.yaml compose.live.yaml" -e BENCH_ROOT="$ROOT/bench" \
        -e CREDO_UNIT_DIR="$ROOT/systemd" -e HOME=/home/david "$@"
}

in_helper() { helper_run "$HELPER" bash -c "$1"; }

build_helper() {
    docker build -q -t "$HELPER" - > /dev/null <<'EOF'
FROM docker:29-cli
RUN apk add --no-cache bash coreutils util-linux findutils grep sed gawk tar gzip curl sudo procps shadow \
 && adduser -D -u 1000 -s /bin/bash david \
 && echo 'david ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/david \
 && mkdir -p /etc/systemd/system \
 && printf '%s\n' '#!/bin/bash' \
   'log=/tmp/credo-live/systemctl.log; echo "$(date -u +%H:%M:%S) systemctl $*" >> "$log"' \
   'flag=/tmp/credo-live/timer-enabled' \
   'case "$1" in' \
   '  enable) [ "$2" = --now ] && touch "$flag" ;;' \
   '  is-enabled) [ -f "$flag" ] && echo enabled || { echo disabled; exit 1; } ;;' \
   '  list-timers) [ -f "$flag" ] && echo "Sun 2026-11-22 06:15:00 UTC 1h left - - credo-pg-backup.timer credo-pg-backup.service" ;;' \
   'esac' > /usr/local/bin/systemctl && chmod 755 /usr/local/bin/systemctl
EOF
}

cmd_setup() {
    local stage="$1" image="$2"
    build_helper
    docker run --rm -v "$ROOT:$ROOT" alpine sh -c "rm -rf $ROOT/* && mkdir -p $ROOT/opt/credo $ROOT/www/releases/placeholder $ROOT/backups $ROOT/bench $ROOT/stages && chown -R 1000:1000 $ROOT"
    # The stage and the image arrive by tar over stdin, as scp and docker load would bring them.
    tar -C "$stage" -cf - . | helper_run "$HELPER" bash -c "mkdir -p $ROOT/stages/pristine $ROOT/scripts && tar -xf - -C $ROOT/stages/pristine && cp $ROOT/stages/pristine/credo-*.sh $ROOT/scripts/ && cp -R $ROOT/stages/pristine $ROOT/stages/first"
    helper_run "$HELPER" docker load < "$image"
    # Server facts from P16-10: placeholder release, current as an absolute link, credo.env (david, 600).
    local pg db
    pg=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
    db=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
    in_helper "set -e
        echo '<!doctype html><title>CREDO</title><p>placeholder' > $ROOT/www/releases/placeholder/index.html
        ln -sfn $ROOT/www/releases/placeholder $ROOT/www/current.tmp && mv -T $ROOT/www/current.tmp $ROOT/www/current
        cp $ROOT/stages/pristine/bundle/credo.env.example $ROOT/opt/credo/credo.env.new
        cd $ROOT/opt/credo
        sed -i -e 's|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$pg|' -e 's|^DB_PASSWORD=.*|DB_PASSWORD=$db|' \
            -e 's|^ALPACA_KEY_ID=.*|ALPACA_KEY_ID=live-check-key-id|' -e 's|^ALPACA_SECRET_KEY=.*|ALPACA_SECRET_KEY=live-check-secret|' \
            -e 's|^ALPACA_DATA_URL=.*|ALPACA_DATA_URL=http://alpaca-stub:8080|' credo.env.new
        install -m 600 credo.env.new credo.env && rm credo.env.new"
    printf '%s\n%s\n' "$pg" "$db" | in_helper "cat > $ROOT/secrets.txt; chmod 600 $ROOT/secrets.txt"
    tar -C "$REPO/deploy" -cf - compose.local.yaml -C "$REPO/deploy/test" compose.live.yaml \
        | in_helper "tar -xf - -C $ROOT/opt/credo"
    # The Alpaca stub's answer and the local Caddy (basic_auth with a test hash made here).
    in_helper "mkdir -p $ROOT/opt/credo/live/www/v2/stocks/ASML $ROOT/opt/credo/live/caddy
        printf '%s' '{\"symbol\":\"ASML\",\"dailyBar\":{\"t\":\"2026-11-20T05:00:00Z\",\"o\":1405.25,\"h\":1410,\"l\":1400,\"c\":1406,\"v\":1000}}' > $ROOT/opt/credo/live/www/v2/stocks/ASML/snapshot"
    local hash
    hash=$(docker run --rm caddy:2 caddy hash-password --plaintext "$LIVE_PASSWORD" | tail -n 1)
    printf ':80 {\n\tbasic_auth {\n\t\tcredo %s\n\t}\n\treverse_proxy exchange:8080\n}\n' "$hash" \
        | in_helper "cat > $ROOT/opt/credo/live/caddy/Caddyfile"
    echo "setup done"
}

cmd_fake() {   # tag [flyway]: an image whose labels say it is <tag>, and a stage for it
    local tag="$1" flyway="${2:-}" base
    base=$(docker image ls credo-exchange --format "{{.Tag}}" | head -n 1)
    local labels=(--label "credo.sha=${tag}0000000000000000000000000000")
    [ -n "$flyway" ] && labels+=(--label "credo.flyway=$flyway")
    printf 'FROM credo-exchange:%s\n' "$base" | docker build -q "${labels[@]}" -t "credo-exchange:$tag" - > /dev/null
    in_helper "rm -rf $ROOT/stages/$tag && cp -R $ROOT/stages/pristine $ROOT/stages/$tag && sed -i 's/^tag=.*/tag=$tag/' $ROOT/stages/$tag/manifest"
    echo "fake tag $tag ready (labels: credo.sha ${tag}..., flyway ${flyway:-inherited})"
}

cmd_teardown() {
    in_helper "cd $ROOT/opt/credo 2>/dev/null && . $ROOT/scripts/credo-lib.sh && credo_compose down -v --remove-orphans" || true
    docker ps -aq --filter label=com.docker.compose.project=credo | xargs -r docker rm -f > /dev/null || true
    docker volume ls -q --filter label=com.docker.compose.project=credo | xargs -r docker volume rm > /dev/null || true
    docker network rm credo_credo > /dev/null 2>&1 || true
    docker image ls credo-exchange --format '{{.Repository}}:{{.Tag}}' | xargs -r docker image rm > /dev/null || true
    docker run --rm -v "$ROOT:$ROOT" alpine rm -rf "$ROOT" || true
    docker image rm "$HELPER" > /dev/null 2>&1 || true
    echo "teardown done"
}

case "${1:-}" in
    setup)    shift; cmd_setup "$@" ;;
    server)   shift; helper_run -w "$ROOT" "$HELPER" bash "$ROOT/scripts/credo-server.sh" "$@" ;;
    bench)    shift; helper_run -w "$ROOT" "$HELPER" bash "$ROOT/bench-stage/credo-bench.sh" "$@" ;;
    sh)       shift; in_helper "$1" ;;
    fake)     shift; cmd_fake "$@" ;;
    teardown) cmd_teardown ;;
    *) sed -n '2,12p' "$0"; exit 64 ;;
esac
