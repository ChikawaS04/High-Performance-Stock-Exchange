#!/usr/bin/env bash
# check-files.sh: static checks of everything deploy/ ships (P16-11). Run from anywhere in the repo:
#
#   bash deploy/test/check-files.sh
#
#  1. bash -n on every shell script under deploy/ and app/backend/docker/;
#  2. shellcheck on them when it is installed (says so when it is not; never installs it);
#  3. no CR in any file under deploy/ or app/backend/docker/, in the working tree and in an extract of
#     `git -c core.autocrlf=false archive HEAD` (what deploy.sh ships);
#  4. the P16-10 files: deploy/Caddyfile holds REPLACE_WITH_HASH, and neither it nor install-caddy.sh holds a
#     bcrypt string ($2a$, $2b$, $2y$).
# CRs are counted with tr, never grep: Git Bash's grep cannot be trusted with a CR pattern. Both detectors are
# first proven on planted files (a CRLF file, a bcrypt-shaped string), so a pass is not a broken detector.
# Exit 1 on any failure.
set -uo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 2
fails=0
ok()   { echo "PASS  $*"; }
bad()  { echo "FAIL  $*"; fails=$((fails + 1)); }
crs()  { tr -cd '\r' < "$1" | wc -c; }
has_bcrypt() {
    grep -qF -e '$2a$' -e '$2b$' -e '$2y$' "$1" || grep -qE '\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}' "$1"
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Positive controls.
printf 'a\r\nb\r\n' > "$tmp/crlf.txt"
printf 'a\nb\n' > "$tmp/lf.txt"
printf 'credo %s\n' '$2a$14$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0' > "$tmp/hash.txt"
if [ "$(crs "$tmp/crlf.txt")" -eq 2 ] && [ "$(crs "$tmp/lf.txt")" -eq 0 ] && has_bcrypt "$tmp/hash.txt" \
    && ! has_bcrypt "$tmp/lf.txt"; then
    ok "detectors find a planted CRLF file and a planted bcrypt string, and nothing in an LF file"
else
    bad "a detector does not work on its planted file; the checks below cannot be trusted"
fi

mapfile -t scripts < <(git ls-files --cached --others --exclude-standard -- 'deploy/*.sh' 'deploy/**/*.sh' 'app/backend/docker/*.sh' | sort -u)
for s in "${scripts[@]}"; do
    if bash -n "$s" 2> "$tmp/err"; then ok "bash -n $s"; else bad "bash -n $s: $(cat "$tmp/err")"; fi
done

if command -v shellcheck > /dev/null 2>&1; then
    for s in "${scripts[@]}"; do
        if shellcheck -x -S warning "$s"; then ok "shellcheck $s"; else bad "shellcheck $s"; fi
    done
else
    echo "SKIP  shellcheck is not installed"
fi

mapfile -t shipped < <(git ls-files --cached --others --exclude-standard -- deploy app/backend/docker | sort -u)
cr=()
for f in "${shipped[@]}"; do
    [ -f "$f" ] && [ "$(crs "$f")" -ne 0 ] && cr+=("$f")
done
[ "${#cr[@]}" -eq 0 ] && ok "no CR in the working tree (${#shipped[@]} files)" || bad "CR in the working tree: ${cr[*]}"

mkdir -p "$tmp/archive"
git -c core.autocrlf=false -c core.eol=lf archive --format=tar HEAD -- deploy app/backend/docker | tar -x -C "$tmp/archive"
cra=()
n=0
while IFS= read -r -d '' f; do
    n=$((n + 1))
    [ "$(crs "$f")" -ne 0 ] && cra+=("${f#"$tmp/archive/"}")
done < <(find "$tmp/archive" -type f -print0)
[ "${#cra[@]}" -eq 0 ] && ok "no CR in git archive HEAD of deploy/ and app/backend/docker/ ($n files, core.autocrlf=false)" \
    || bad "CR in the archive of HEAD: ${cra[*]}"

if [ -f deploy/Caddyfile ]; then
    n=$(grep -c 'REPLACE_WITH_HASH' deploy/Caddyfile || true)
    [ "$n" -ge 1 ] && ok "deploy/Caddyfile holds REPLACE_WITH_HASH ($n)" || bad "deploy/Caddyfile has no REPLACE_WITH_HASH"
else
    bad "deploy/Caddyfile is missing"
fi
for f in deploy/Caddyfile deploy/install-caddy.sh; do
    [ -f "$f" ] || { bad "$f is missing"; continue; }
    if has_bcrypt "$f"; then bad "$f contains a bcrypt string"; else ok "$f holds no bcrypt string"; fi
done

echo
[ "$fails" -eq 0 ] && { echo "check-files: all passed"; exit 0; }
echo "check-files: $fails failed"; exit 1
