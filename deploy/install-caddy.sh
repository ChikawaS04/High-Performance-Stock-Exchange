#!/usr/bin/env bash
# install-caddy.sh: finishes P16-10 on credo-exchange-01. Run as: sudo bash /tmp/install-caddy.sh
# Asks for the site password, hashes it, puts the hash into /etc/caddy/Caddyfile, validates,
# reloads Caddy and runs the checks. Rolls back to Caddyfile.pre-p16-10 on any failure.
# Never prints the password or the hash.
set -euo pipefail

CF=/etc/caddy/Caddyfile
BACKUP=/etc/caddy/Caddyfile.pre-p16-10
WEB=/var/www/directroute
TMP=$(mktemp /tmp/Caddyfile.XXXXXX)
trap 'rm -f "$TMP"' EXIT

fail() { echo "FAILED: $*"; exit 1; }
rollback() {
  echo "Rolling back to $BACKUP"
  cp "$BACKUP" "$CF"
  systemctl reload caddy || true
  fail "$1"
}

[ "$(id -u)" -eq 0 ] || fail "run with sudo"
[ -s "$BACKUP" ] || fail "$BACKUP is missing or empty; stop and ask in the planning chat"
grep -q 'REPLACE_WITH_HASH' "$CF" || fail "$CF has no REPLACE_WITH_HASH; stop and ask in the planning chat"

# Placeholder page and current link (B1); created only if missing.
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
echo "OK  placeholder: $(readlink "$WEB/current")"

# Password and hash.
echo
echo "Choose the site password (username: credo). Use a NEW one. Nothing is shown as you type."
read -r -s -p "Password: " PW1; echo
read -r -s -p "Again:    " PW2; echo
[ -n "$PW1" ] || fail "empty password"
[ "$PW1" = "$PW2" ] || fail "the two passwords differ; run the script again"
HASH=$(printf '%s\n' "$PW1" | caddy hash-password 2>/dev/null | tail -n 1)
unset PW1 PW2
[[ "$HASH" =~ ^\$2a\$14\$[./A-Za-z0-9]{53}$ ]] || fail "caddy hash-password did not return a bcrypt hash; nothing was changed"
echo "OK  hash made"

# New file, validated before it goes live.
sed "s|REPLACE_WITH_HASH|${HASH}|" "$CF" > "$TMP"
unset HASH
grep -q 'REPLACE_WITH_HASH' "$TMP" && fail "placeholder still present; nothing was changed"
caddy validate --config "$TMP" --adapter caddyfile >/dev/null 2>&1 || fail "caddy validate failed; nothing was changed"
echo "OK  new Caddyfile validates"

install -m 644 -o root -g root "$TMP" "$CF"
systemctl reload caddy || rollback "reload failed"
sleep 2
systemctl is-active --quiet caddy || rollback "caddy is not active after reload"
echo "OK  caddy reloaded"

# Checks.
echo
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
SITE=$(code https://davidsexchange.com/)
WWW=$(code https://www.davidsexchange.com/)
WWWLOC=$(curl -sI https://www.davidsexchange.com/ | tr -d '\r' | awk 'tolower($1)=="location:"{print $2}')
CERT=$(echo | openssl s_client -connect davidsexchange.com:443 -servername davidsexchange.com 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null)
UDP=$(ss -ulnp | grep -c ':443 .*caddy' || true)
ERRS=$(journalctl -u caddy --since "-2 min" --no-pager | grep -ci '"level":"error"' || true)
echo "Site without password (expect 401):  $SITE"
echo "www redirect (expect 301):           $WWW -> $WWWLOC"
echo "Certificate:                         $CERT"
echo "Caddy on UDP 443 (expect 1 or more): $UDP"
echo "Caddy errors in the last 2 minutes:  $ERRS"
echo
echo "Now open https://davidsexchange.com in your browser and log in as credo."
echo "Store the password in your password manager."
