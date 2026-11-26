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
#     bcrypt string ($2a$, $2b$, $2y$);
#  5. credo-lib.sh's pure helpers on planted input: the log-line filters, the previous-tag and IMAGE_TAG messages,
#     the /health field readers, the asset, Caddy-mode, time-window and secret-value helpers, and the lock probe
#     (where flock exists; Git Bash has none);
#  6. the two Caddy access modes rendered by install-caddy.sh --render, and the HSTS value kept in step;
#  7. flood-client.mjs never staged for the server, and syntax-checked with node when it is installed.
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

# 6. The two Caddy access modes (P16-13): both rendered by install-caddy.sh --render from the repo copy, which
#    must keep exactly one `import private` and one placeholder. caddy validate runs in the live check (it needs
#    the caddy binary); here the renders are checked as text.
n=$(grep -cE '^[[:space:]]*import[[:space:]]+(private|public)[[:space:]]*$' deploy/Caddyfile || true)
[ "$n" -eq 1 ] && grep -qE '^[[:space:]]*import[[:space:]]+private[[:space:]]*$' deploy/Caddyfile \
    && ok "deploy/Caddyfile has one access import line, and it is 'import private'" \
    || bad "deploy/Caddyfile must have exactly one access import line, 'import private'"
n=$(grep -c 'REPLACE_WITH_HASH' deploy/Caddyfile || true)
[ "$n" -eq 1 ] && ok "deploy/Caddyfile holds REPLACE_WITH_HASH exactly once" || bad "deploy/Caddyfile holds REPLACE_WITH_HASH $n times (want 1)"
planted='$2a$14$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0'
if printf '%s\n' "$planted" | bash deploy/install-caddy.sh --render private --source deploy/Caddyfile --out "$tmp/private.caddy" > /dev/null \
    && bash deploy/install-caddy.sh --render public --source deploy/Caddyfile --out "$tmp/public.caddy" < /dev/null > /dev/null; then
    grep -qE '^[[:space:]]*import[[:space:]]+private[[:space:]]*$' "$tmp/private.caddy" && has_bcrypt "$tmp/private.caddy" \
        && ! grep -q REPLACE_WITH_HASH "$tmp/private.caddy" && ! grep -qE '^[[:space:]]*import[[:space:]]+public' "$tmp/private.caddy" \
        && ok "private render: imports private, holds the planted hash, no placeholder, no public import" \
        || bad "private render is wrong"
    grep -qE '^[[:space:]]*import[[:space:]]+public[[:space:]]*$' "$tmp/public.caddy" && ! has_bcrypt "$tmp/public.caddy" \
        && ! grep -qE '^[[:space:]]*import[[:space:]]+private' "$tmp/public.caddy" \
        && ok "public render: imports public, no import of private, no hash" || bad "public render is wrong"
    [ "$(diff "$tmp/public.caddy" deploy/Caddyfile | grep -c '^[<>]')" -eq 2 ] \
        && ok "public render differs from the repo copy in the import line only" || bad "public render changes more than the import line"
    [ "$(crs "$tmp/private.caddy")" -eq 0 ] && [ "$(crs "$tmp/public.caddy")" -eq 0 ] && ok "renders hold no CR" || bad "a render holds a CR"
else
    bad "install-caddy.sh --render failed"
fi
printf '%s\n' 'not-a-hash' | bash deploy/install-caddy.sh --render private --source deploy/Caddyfile --out "$tmp/bad.caddy" > /dev/null 2>&1 \
    && bad "--render private accepted a value that is not a bcrypt hash" || ok "--render private refuses a value that is not a bcrypt hash"
printf 'x {\n\timport private\n}\n' > "$tmp/foreign.caddy"
bash deploy/install-caddy.sh --render public --source "$tmp/foreign.caddy" --out "$tmp/bad.caddy" > /dev/null 2>&1 \
    && bad "--render accepted a source without the snippets and placeholder" || ok "--render refuses a source that is not the repo layout"
hsts_cf=$(sed -n -E 's/^[[:space:]]*header Strict-Transport-Security "([^"]*)"$/\1/p' deploy/Caddyfile)
hsts_srv=$(sed -n -E 's/^HSTS_EXPECTED="([^"]*)"$/\1/p' deploy/credo-server.sh)
hsts_inst=$(sed -n -E 's/^EXPECT_HSTS="([^"]*)"$/\1/p' deploy/install-caddy.sh)
[ -n "$hsts_cf" ] && [ "$hsts_cf" = "$hsts_srv" ] && [ "$hsts_cf" = "$hsts_inst" ] \
    && ok "HSTS value '$hsts_cf' is the same in the Caddyfile, credo-server.sh and install-caddy.sh" \
    || bad "HSTS values differ: Caddyfile '$hsts_cf', credo-server.sh '$hsts_srv', install-caddy.sh '$hsts_inst'"

# 7. The flood client runs on the PC only: deploy.sh stages named files, and none of them is flood-client.mjs.
if grep -nE 'STAGE|scp' deploy/deploy.sh | grep -q 'flood-client'; then bad "deploy.sh stages or copies flood-client.mjs"
else ok "flood-client.mjs is never staged or copied to the server"; fi
if command -v node > /dev/null 2>&1; then
    node --check deploy/flood-client.mjs 2> "$tmp/err" && ok "node --check deploy/flood-client.mjs" || bad "node --check flood-client.mjs: $(cat "$tmp/err")"
else
    echo "SKIP  node is not installed; flood-client.mjs not syntax-checked"
fi

# 5. The pure helpers of credo-lib.sh (Session G): the log-line filters on planted lines in the formats pinned by
#    ResendLogTest, CatchUpLogTest, ResendService and SessionController, with a logger prefix, and the
#    previous-tag and IMAGE_TAG messages on planted histories.
# shellcheck source=../credo-lib.sh
( CREDO_DIR="$tmp/credo" BENCH_ROOT="$tmp/bench"; . deploy/credo-lib.sh
  eq() { if [ "$2" = "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1: got [$2], want [$3]"; fi; }
  p='2026-11-25 19:11:02.123 INFO  [resend] net.ResendService - '
  q='2026-11-25 19:11:03.456 INFO  [bots] participant.ParticipantClient - '
  log="${p}RESEND fromSeq 2864 to 3004: 141 frames to /172.30.0.10:41234 in 87 ms (read 141 events, 22560 bytes from position 458752)
${p}RESEND fromSeq 1 to 5: 5 frames to peer in 2 ms
${p}RESEND fromSeq 3494 from /172.30.0.10:4100 rejected: 4 resends already pending
${p}RESEND fromSeq 3494 from /172.30.0.10:4100: already caught up at seq 3493; nothing to resend
${q}MAKER caught up through seq 3350 in 412 ms (RESEND from seq 3178, 2 request(s), offline 6150 ms); trading resumes
${q}NOISE caught up through seq 3350 in 12 ms (RESEND from seq 3351, 1 request(s)); trading resumes
${q}MAKER lost its connection to ws://exchange:8080/ws (closed 1006); reconnecting
${q}INST lost its connection to ws://exchange:8080/ws (closed 1006); reconnecting
${q}MAKER lost its connection to ws://exchange:8080/ws (again); reconnecting
${p}Opening the session for business date 20261125 (ASML, tick 100, lot 1, scale 10000)
${p}Session open for business date 20261125
${p}Cleared business date 20261124: 812 fill(s), volume 1000, 5 obligation(s); balances and positions
${p}Session for business date 20261126 is open (recovered); trading resumes"
  eq "credo_resend_lines finds the 2 completions" "$(credo_resend_lines <<< "$log" | wc -l | tr -d ' ')" 2
  eq "credo_resend_lines keeps the read figures" "$(credo_resend_lines <<< "$log" | head -n 1)" \
     "RESEND fromSeq 2864 to 3004: 141 frames to /172.30.0.10:41234 in 87 ms (read 141 events, 22560 bytes from position 458752)"
  eq "credo_resend_busy_lines finds the 1 BUSY refusal" "$(credo_resend_busy_lines <<< "$log" | wc -l | tr -d ' ')" 1
  eq "credo_catchup_lines finds 2 catch-ups, names first" "$(credo_catchup_lines <<< "$log" | awk '{ print $1 }' | tr '\n' ' ')" "MAKER NOISE "
  eq "credo_catchup_lines keeps a line with no offline part" "$(credo_catchup_lines <<< "$log" | tail -n 1)" \
     "NOISE caught up through seq 3350 in 12 ms (RESEND from seq 3351, 1 request(s))"
  eq "credo_lost_names gives each bot once" "$(credo_lost_names <<< "$log" | tr '\n' ' ')" "INST MAKER "
  eq "credo_session_open_lines skips the Opening and Cleared lines" "$(credo_session_open_lines <<< "$log" | tr '\n' '|')" \
     "Session open for business date 20261125|Session for business date 20261126 is open (recovered)|"
  eq "filters print nothing and succeed on an empty log" "$(credo_resend_lines <<< ""; credo_catchup_lines <<< ""; echo rc=$?)" "rc=0"

  h="$tmp/history"
  : > "$h"
  eq "previous ok: no history file" "$(credo_previous_ok "$tmp/none" aaaaaaaaaaaa)" ""
  printf '%s\n' "2026-11-21T19:07:00Z bc8a03a54d09 attempt flyway=9 snapshot=2" "2026-11-21T19:08:00Z bc8a03a54d09 ok flyway=9 snapshot=2" \
      "2026-11-21T19:20:00Z bc8a03a54d09 attempt flyway=9 snapshot=2" "2026-11-21T19:21:00Z bc8a03a54d09 ok flyway=9 snapshot=2" > "$h"
  eq "previous ok: only the target recorded ok gives none" "$(credo_previous_ok "$h" bc8a03a54d09)" ""
  printf '%s\n' "t1 aaaaaaaaaaaa ok flyway=9 snapshot=2" "t2 bbbbbbbbbbbb ok flyway=9 snapshot=2" "t3 bbbbbbbbbbbb ok flyway=9 snapshot=2" > "$h"
  eq "previous ok: A, B, B again with target B gives A" "$(credo_previous_ok "$h" bbbbbbbbbbbb)" aaaaaaaaaaaa
  printf '%s\n' "t1 aaaaaaaaaaaa ok flyway=9 snapshot=2" "t2 cccccccccccc attempt flyway=9 snapshot=2" "t3 cccccccccccc failed flyway=9 snapshot=2" > "$h"
  eq "previous ok: a failed attempt is never named" "$(credo_previous_ok "$h" bbbbbbbbbbbb)" aaaaaaaaaaaa
  eq "previous ok: target C after its failure gives A" "$(credo_previous_ok "$h" cccccccccccc)" aaaaaaaaaaaa
  eq "tag note: same tag" "$(credo_tag_note bc8a03a54d09 bc8a03a54d09)" "IMAGE_TAG unchanged (bc8a03a54d09)"
  eq "tag note: other tag" "$(credo_tag_note aaaaaaaaaaaa bbbbbbbbbbbb)" "IMAGE_TAG set to bbbbbbbbbbbb (was aaaaaaaaaaaa)"
  eq "tag note: unset" "$(credo_tag_note "" bbbbbbbbbbbb)" "IMAGE_TAG set to bbbbbbbbbbbb (was unset)"

  hj='{"status":"UP","journal":{"inbound":{"recordingId":0,"startPosition":0,"recordedPosition":99,"bytesOnDisk":1},"outbound":{"recordingId":1,"startPosition":1048576,"recordedPosition":5242880,"bytesOnDisk":4194304},"retention":{}},"businessDate":20261125}'
  eq "outbound field: startPosition, not the inbound one" "$(credo_outbound_field "$hj" startPosition)" 1048576
  eq "outbound field: recordedPosition" "$(credo_outbound_field "$hj" recordedPosition)" 5242880
  eq "json num: businessDate" "$(credo_json_num "$hj" businessDate)" 20261125

  # P16-13 helpers.
  h2='{"running":true,"session":"OPEN","businessDate":20261125,"reporter":{"state":"BACKING_OFF","inboundSeq":7,"outboundSeq":9,"outboundLag":12},"clearing":{"state":"IDLE","lastClearedDate":20261124,"pendingDate":null,"heldBy":null},"access":{"connections":{"public":1,"exempt":5,"clients":1,"idleClients":0},"framesDropped":{"channel":452,"client":0,"public":0},"closed1008":1}}'
  eq "obj field: reporter.state (not clearing's)" "$(credo_obj_field "$h2" reporter state)" BACKING_OFF
  eq "obj field: reporter.outboundLag" "$(credo_obj_field "$h2" reporter outboundLag)" 12
  eq "obj field: clearing.state" "$(credo_obj_field "$h2" clearing state)" IDLE
  eq "obj field: clearing.pendingDate null" "$(credo_obj_field "$h2" clearing pendingDate)" null
  eq "obj field: connections.exempt" "$(credo_obj_field "$h2" connections exempt)" 5
  eq "obj field: framesDropped.channel" "$(credo_obj_field "$h2" framesDropped channel)" 452
  eq "obj field: an absent object gives nothing" "$(credo_obj_field "$h2" database state)" ""
  idx='<script type="module" crossorigin src="/assets/index-DMUzTTfU.js"></script>
  <link rel="stylesheet" crossorigin href="/assets/index-Cks-PT2Q.css"><link rel="icon" href="/favicon.svg">'
  eq "index assets: the js and the css, not the icon" "$(credo_index_assets <<< "$idx" | tr '\n' ' ')" "/assets/index-DMUzTTfU.js /assets/index-Cks-PT2Q.css "
  printf '(private) {\n}\nsite {\n\timport public\n}\n' > "$tmp/mode.caddy"
  eq "caddy mode: public" "$(credo_caddy_mode "$tmp/mode.caddy")" public
  eq "caddy mode: the repo copy is private" "$(credo_caddy_mode deploy/Caddyfile)" private
  printf 'site {\n\tbasic_auth {\n\t}\n}\n' > "$tmp/old.caddy"
  eq "caddy mode: the P16-10 layout has none" "$(credo_caddy_mode "$tmp/old.caddy")" ""
  eq "caddy mode: a missing file has none" "$(credo_caddy_mode "$tmp/none.caddy")" ""
  w=""; for t in 0349 0350 0500 0629 0630 2300; do credo_in_quiet_window "$t" && w="$w $t:in" || w="$w $t:out"; done
  eq "quiet window 03:50 to 06:30 UTC" "$w" " 0349:out 0350:in 0500:in 0629:in 0630:out 2300:out"
  mkdir -p "$tmp/credo" "$tmp/scan"
  CREDO_ENV="$tmp/credo/credo.env"
  printf 'ALPACA_KEY_ID=PKTESTKEYID123\nALPACA_SECRET_KEY=\nOTHER=x\n' > "$CREDO_ENV"
  printf 'nothing here\n' > "$tmp/scan/a.js"
  credo_scan_secret_values "$tmp/scan"; eq "secret scan: absent value gives 1" "$?" 1
  printf 'x PKTESTKEYID123 y\n' > "$tmp/scan/b.js"
  out=$(credo_scan_secret_values "$tmp/scan"; echo "rc=$?")
  eq "secret scan: a present value gives 0 and prints nothing" "$out" "rc=0"
  printf 'ALPACA_KEY_ID=\nALPACA_SECRET_KEY=\n' > "$CREDO_ENV"
  credo_scan_secret_values "$tmp/scan"; eq "secret scan: no value set gives 2" "$?" 2
  CREDO_ENV="$tmp/credo/credo.env"

  mkdir -p "$tmp/locks"
  lk="$tmp/locks/l"
  credo_lock_held "$lk" && echo "FAIL  lock: a missing file reads as held" || echo "PASS  lock: a missing file is free"
  if ! command -v flock > /dev/null 2>&1; then
      echo "SKIP  lock probes: flock is not installed here (Git Bash); the live check runs them on Linux"
      exit 0
  fi
  : > "$lk"
  credo_lock_held "$lk" && echo "FAIL  lock: an unheld file reads as held" || echo "PASS  lock: an unheld file is free"
  exec 6> "$lk"; flock -n 6
  ( credo_lock_held "$lk" ) && echo "PASS  lock: a held file reads as held" || echo "FAIL  lock: a held file reads as free"
  exec 6>&-
  credo_lock_held "$lk" && echo "FAIL  lock: still held after release" || echo "PASS  lock: free again after release"
) > "$tmp/lib.out" 2>&1
cat "$tmp/lib.out"
fails=$((fails + $(grep -c '^FAIL' "$tmp/lib.out" || true)))

echo
[ "$fails" -eq 0 ] && { echo "check-files: all passed"; exit 0; }
echo "check-files: $fails failed"; exit 1
