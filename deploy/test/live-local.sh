#!/usr/bin/env bash
# live-local.sh: the local live check of the deploy and bench scripts on Docker Desktop (P16-11, P16-12).
# Never shipped, never run against the server. Run from Git Bash at the repo root:
#
#   bash deploy/test/live-local.sh setup <stage dir> <image.tar.gz>   helper image, scratch roots, credo.env
#   bash deploy/test/live-local.sh server <args...>                   credo-server.sh <args> in the helper
#   bash deploy/test/live-local.sh bench <args...>                    credo-bench.sh <args> in the helper
#   bash deploy/test/live-local.sh sh '<command>'                     a shell command in the helper, as david
#   bash deploy/test/live-local.sh fake <tag12> [flyway]              an image labelled as another tag, and its stage
#   bash deploy/test/live-local.sh caddy-mode private|public          the local Caddy in that access mode (P16-13)
#   bash deploy/test/live-local.sh caddy-install-test <mode> [fail-reload]   install-caddy.sh --mode in caddy:2
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
    # --pid=host: the helper sees the Docker Desktop VM's processes, so a container's State.Pid is a PID it can
    # signal, as on the server (crash-exchange).
    docker run --rm -i --pid=host --user david --group-add "$gid" --add-host host.docker.internal:host-gateway \
        -v /var/run/docker.sock:/var/run/docker.sock -v "$ROOT:$ROOT" \
        -e CREDO_DIR="$ROOT/opt/credo" -e CREDO_WEB_DIR="$ROOT/www" -e CREDO_BACKUP_DIR="$ROOT/backups" \
        -e CREDO_HEALTH_URL=http://host.docker.internal:18080/health -e CREDO_SITE_URL=http://host.docker.internal:18081 \
        -e CREDO_CADDYFILE="$ROOT/opt/credo/live/caddy/Caddyfile" \
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
    docker run --rm -v "$ROOT:$ROOT" alpine sh -c "rm -rf $ROOT/* && mkdir -p $ROOT/opt/credo $ROOT/www/releases/placeholder $ROOT/backups $ROOT/bench $ROOT/stages $ROOT/systemd && chown -R 1000:1000 $ROOT"
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
    cmd_caddy_mode private
    echo "setup done"
}

# The local Caddy runs the real repo Caddyfile, rendered by install-caddy.sh --render in the given mode, with
# three local substitutions: the site address becomes :80, the venue is exchange:8080 on the Compose network, and
# the static root is the scratch release tree (mounted at the same path, so the absolute current link resolves).
# The www block is dropped (no second name locally). Reloaded in place when Caddy is already running.
cmd_caddy_mode() {
    local mode="$1" out
    out=$(mktemp)
    if [ "$mode" = private ]; then
        docker run --rm caddy:2 caddy hash-password --plaintext "$LIVE_PASSWORD" | tail -n 1 \
            | bash "$REPO/deploy/install-caddy.sh" --render private --source "$REPO/deploy/Caddyfile" --out "$out" > /dev/null
    else
        bash "$REPO/deploy/install-caddy.sh" --render public --source "$REPO/deploy/Caddyfile" --out "$out" < /dev/null > /dev/null
    fi
    sed -e 's|^davidsexchange.com {|:80 {|' -e 's|reverse_proxy 127.0.0.1:8080|reverse_proxy exchange:8080|' \
        -e "s|/var/www/directroute/current|$ROOT/www/current|" -e '/^www.davidsexchange.com {/,/^}/d' "$out" \
        | in_helper "mkdir -p $ROOT/opt/credo/live/caddy && cat > $ROOT/opt/credo/live/caddy/Caddyfile"
    rm -f "$out"
    if docker ps -q --filter label=com.docker.compose.service=caddy --filter label=com.docker.compose.project=credo | grep -q .; then
        docker exec "$(docker ps -q --filter label=com.docker.compose.service=caddy --filter label=com.docker.compose.project=credo)" \
            caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile > /dev/null
        echo "local caddy reloaded in $mode mode"
    else
        echo "local caddy file written in $mode mode"
    fi
}

# install-caddy.sh --mode in a caddy:2 container: a stand-in for the server's Caddy (local CA certificates for the
# real names, mapped to 127.0.0.1, so nothing reaches the internet; systemctl and journalctl stubs). $1 is the mode,
# $2 "fail-reload" makes the reload fail once, to show the rollback.
cmd_caddy_install_test() {
    local mode="$1" fail="${2:-}" name=credo-live-caddytest work
    work=$(mktemp -d)
    { printf '{\n\tlocal_certs\n\tskip_install_trust\n}\n\n'; cat "$REPO/deploy/Caddyfile"; } > "$work/Caddyfile"
    cp "$REPO/deploy/install-caddy.sh" "$work/"
    docker rm -f "$name" > /dev/null 2>&1 || true
    MSYS_NO_PATHCONV=1 docker run -d --name "$name" --add-host davidsexchange.com:127.0.0.1 \
        --add-host www.davidsexchange.com:127.0.0.1 caddy:2 sh -c 'sleep 600' > /dev/null
    docker cp "$(cygpath -w "$work" 2>/dev/null || echo "$work")/." "$name:/work/"
    rm -rf "$work"
    docker exec -i "$name" sh -s "$mode" "$fail" <<'EOS'
set -e
mode="$1"; fail="$2"
apk add -q --no-cache bash curl openssl iproute2 coreutils ca-certificates > /dev/null
adduser -D david 2> /dev/null || true
mkdir -p /var/www/directroute/releases/t1
echo '<!doctype html><title>t1</title>' > /var/www/directroute/releases/t1/index.html
ln -sfn /var/www/directroute/releases/t1 /var/www/directroute/current
# The "live" file before the switch: the private render with a throwaway hash, like the server today.
printf '%s\n' "$(caddy hash-password --plaintext live-check-old | tail -n 1)" \
    | bash /work/install-caddy.sh --render private --source /work/Caddyfile --out /etc/caddy/Caddyfile > /dev/null
caddy start --config /etc/caddy/Caddyfile --adapter caddyfile > /dev/null 2>&1
for i in $(seq 1 30); do [ -f /data/caddy/pki/authorities/local/root.crt ] && break; sleep 1; done
cp /data/caddy/pki/authorities/local/root.crt /usr/local/share/ca-certificates/caddy-local.crt && update-ca-certificates > /dev/null 2>&1
cat > /usr/local/bin/systemctl <<'EOF'
#!/bin/bash
case "$1" in
  reload) if [ -f /tmp/fail-reload ]; then rm -f /tmp/fail-reload; exit 1; fi; caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile ;;
  is-active) pgrep caddy > /dev/null ;;
esac
EOF
printf '#!/bin/bash\nexit 0\n' > /usr/local/bin/journalctl
chmod 755 /usr/local/bin/systemctl /usr/local/bin/journalctl
[ "$fail" = fail-reload ] && touch /tmp/fail-reload
before=$(sha256sum /etc/caddy/Caddyfile | cut -c1-12)
rc=0
if [ "$mode" = private ]; then
    printf 'test-pw-1\ntest-pw-1\n' | bash /work/install-caddy.sh --mode private --source /work/Caddyfile || rc=$?
else
    bash /work/install-caddy.sh --mode public --source /work/Caddyfile || rc=$?
fi
echo "exit $rc; live file $before -> $(sha256sum /etc/caddy/Caddyfile | cut -c1-12); backups: $(ls /etc/caddy | grep -c 'Caddyfile.bak-')"
echo "live import line: $(grep -E '^[[:space:]]*import (private|public)' /etc/caddy/Caddyfile | tr -d '\t')"
EOS
    docker rm -f "$name" > /dev/null
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
    caddy-mode) shift; cmd_caddy_mode "$@" ;;
    caddy-install-test) shift; cmd_caddy_install_test "$@" ;;
    teardown) cmd_teardown ;;
    *) sed -n '2,14p' "$0"; exit 64 ;;
esac
