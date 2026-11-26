#!/usr/bin/env bash
# install-caddy.sh: installs deploy/Caddyfile as /etc/caddy/Caddyfile in one of two access modes (P16-10, P16-13).
#
#   sudo bash install-caddy.sh --mode private|public [--source <Caddyfile>]
#   bash install-caddy.sh --render private|public --source <Caddyfile> --out <file>
#
# --mode (run as root on credo-exchange-01; deploy/deploy.sh --caddy public|private copies this file and the repo
# Caddyfile up and runs it): renders the repo Caddyfile (default: the Caddyfile beside this script), validates the
# result with caddy validate, backs up the live file to /etc/caddy/Caddyfile.bak-<UTC stamp>, installs it,
# reloads Caddy, checks it is active, and rolls back to that backup on any failure. Then the checks: the site
# without a password (401 private, 200 public), the HSTS header (absent private, present public), the www
# redirect, the certificate, Caddy on UDP 443 and Caddy's errors.
#   private: the site behind basic_auth (user credo); asks for the password twice without echo and hashes it.
#   public:  no password, HSTS on. Going private again is the same command with --mode private.
# --render (no root, nothing installed): writes the rendered file only; private reads a bcrypt hash from stdin.
# Used by deploy/test/check-files.sh and the local live check.
#
# The repo Caddyfile is never edited: it keeps `import private` and REPLACE_WITH_HASH. Never prints the password
# or the hash.
set -euo pipefail

CF=/etc/caddy/Caddyfile
WEB=/var/www/directroute
EXPECT_HSTS="max-age=86400; includeSubDomains"

fail() { echo "FAILED: $*"; exit 1; }

mode="" render="" src="" out=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --mode) mode="${2:-}"; shift 2 ;;
        --render) render="${2:-}"; shift 2 ;;
        --source) src="${2:-}"; shift 2 ;;
        --out) out="${2:-}"; shift 2 ;;
        *) fail "unknown option $1 (usage: --mode private|public [--source F] | --render private|public --source F --out G)" ;;
    esac
done

# Renders the source Caddyfile $1 in mode $2 into $3; for private, $4 is the bcrypt hash. Refuses a source that is
# not the repo layout (one `import private` line, one placeholder, both snippets).
render_file() {
    local src="$1" m="$2" dst="$3" hash="${4:-}"
    [ -r "$src" ] || fail "$src is missing"
    [ "$(grep -cE '^[[:space:]]*import[[:space:]]+private[[:space:]]*$' "$src" || true)" -eq 1 ] \
        || fail "$src must hold exactly one 'import private' line (the repo deploy/Caddyfile)"
    [ "$(grep -c 'REPLACE_WITH_HASH' "$src" || true)" -eq 1 ] || fail "$src must hold REPLACE_WITH_HASH exactly once"
    grep -qE '^\(private\) \{' "$src" && grep -qE '^\(public\) \{' "$src" || fail "$src lacks the (private) or (public) snippet"
    case "$m" in
        public)
            sed -E 's/^([[:space:]]*)import[[:space:]]+private[[:space:]]*$/\1import public/' "$src" > "$dst"
            [ "$(grep -cE '^[[:space:]]*import[[:space:]]+public[[:space:]]*$' "$dst" || true)" -eq 1 ] \
                && ! grep -qE '^[[:space:]]*import[[:space:]]+private' "$dst" || fail "the public render is wrong; nothing was changed"
            ;;
        private)
            [[ "$hash" =~ ^\$2a\$14\$[./A-Za-z0-9]{53}$ ]] || fail "not a bcrypt hash; nothing was changed"
            sed "s|REPLACE_WITH_HASH|${hash}|" "$src" > "$dst"
            ! grep -q 'REPLACE_WITH_HASH' "$dst" || fail "placeholder still present; nothing was changed"
            ;;
        *) fail "the mode must be private or public" ;;
    esac
}

# --- render only ----------------------------------------------------------------------------------------------
if [ -n "$render" ]; then
    [ -n "$src" ] && [ -n "$out" ] || fail "--render needs --source and --out"
    hash=""
    if [ "$render" = private ]; then IFS= read -r hash || true; fi
    render_file "$src" "$render" "$out" "$hash"
    unset hash
    echo "OK  rendered $render mode to $out"
    exit 0
fi

# --- install --------------------------------------------------------------------------------------------------
[ "$mode" = private ] || [ "$mode" = public ] || fail "--mode private or --mode public is required"
[ "$(id -u)" -eq 0 ] || fail "run with sudo"
src="${src:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/Caddyfile}"
[ -s "$CF" ] || fail "$CF is missing or empty; stop and ask in the planning chat"
command -v caddy > /dev/null || fail "caddy is not installed"

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP="$CF.bak-$STAMP"
TMP=$(mktemp /tmp/Caddyfile.XXXXXX)
trap 'rm -f "$TMP"' EXIT

rollback() {
    echo "Rolling back to $BACKUP"
    cp "$BACKUP" "$CF"
    systemctl reload caddy || true
    fail "$1"
}

# Placeholder page and current link; created only if missing (P16-10).
if [ ! -f "$WEB/releases/placeholder/index.html" ]; then
    install -d -o david -g david -m 755 "$WEB/releases/placeholder"
    printf '%s\n' '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>CREDO exchange</title></head><body><p>CREDO exchange: coming soon</p></body></html>' > "$WEB/releases/placeholder/index.html"
    chown david:david "$WEB/releases/placeholder/index.html"
fi
if [ ! -L "$WEB/current" ]; then
    ln -sfn "$WEB/releases/placeholder" "$WEB/current.tmp"
    mv -T "$WEB/current.tmp" "$WEB/current"
    chown -h david:david "$WEB/current"
fi
echo "OK  current: $(readlink "$WEB/current")"

if [ "$mode" = private ]; then
    echo
    echo "Choose the site password (username: credo). Nothing is shown as you type."
    read -r -s -p "Password: " PW1; echo
    read -r -s -p "Again:    " PW2; echo
    [ -n "$PW1" ] || fail "empty password"
    [ "$PW1" = "$PW2" ] || fail "the two passwords differ; run the script again"
    HASH=$(printf '%s\n' "$PW1" | caddy hash-password 2>/dev/null | tail -n 1)
    unset PW1 PW2
    echo "OK  hash made"
    render_file "$src" private "$TMP" "$HASH"
    unset HASH
else
    render_file "$src" public "$TMP"
fi
chmod 644 "$TMP"
caddy validate --config "$TMP" --adapter caddyfile > /dev/null 2>&1 || fail "caddy validate failed on the $mode render; nothing was changed"
echo "OK  the $mode Caddyfile validates"

cp -p "$CF" "$BACKUP"
echo "OK  live Caddyfile backed up to $BACKUP"
install -m 644 -o root -g root "$TMP" "$CF"
systemctl reload caddy || rollback "reload failed"
sleep 2
systemctl is-active --quiet caddy || rollback "caddy is not active after reload"
echo "OK  caddy reloaded in $mode mode"

# Checks.
echo
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
SITE=$(code https://davidsexchange.com/)
HSTS=$(curl -s -o /dev/null -D - https://davidsexchange.com/ | tr -d '\r' | sed -n -E 's/^[Ss]trict-[Tt]ransport-[Ss]ecurity:[[:space:]]*//p' | head -n 1)
WWW=$(code https://www.davidsexchange.com/)
WWWLOC=$(curl -sI https://www.davidsexchange.com/ | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')
CERT=$(echo | openssl s_client -connect davidsexchange.com:443 -servername davidsexchange.com 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null)
UDP=$(ss -ulnp | grep -c ':443 .*caddy' || true)
ERRS=$(journalctl -u caddy --since "-2 min" --no-pager | grep -ci '"level":"error"' || true)
if [ "$mode" = private ]; then
    echo "Site without password (expect 401):  $SITE"
    echo "HSTS (expect none):                  ${HSTS:-none}"
else
    echo "Site without password (expect 200):  $SITE"
    echo "HSTS (expect $EXPECT_HSTS): ${HSTS:-none}"
fi
echo "www redirect (expect 301):           $WWW -> $WWWLOC"
echo "Certificate:                         $CERT"
echo "Caddy on UDP 443 (expect 1 or more): $UDP"
echo "Caddy errors in the last 2 minutes:  $ERRS"
echo
if [ "$mode" = private ]; then
    echo "Open https://davidsexchange.com and log in as credo; store the password in your password manager."
    [ "$SITE" = 401 ] && [ -z "$HSTS" ] || fail "the site does not answer as private; the previous file is $BACKUP"
else
    echo "Open https://davidsexchange.com: no password prompt. Then run deploy/deploy.sh --check."
    [ "$SITE" = 200 ] && [ "$HSTS" = "$EXPECT_HSTS" ] || fail "the site does not answer as public; the previous file is $BACKUP"
fi
echo "install-caddy: $mode mode live"
