#!/usr/bin/env bash
# Nightly backup of the venue database (P16-6, D8): pg_dump -Fc through docker exec into
# BACKUP_DIR, newest KEEP_DAYS days kept, every file mode 600. Run by credo-pg-backup.timer.
#
# Settings (environment, all optional):
#   BACKUP_DIR    default /srv/credo/backups
#   KEEP_DAYS     default 14
#   PG_CONTAINER  default credo-postgres-1 (Compose project "credo", service "postgres")
#   PG_DATABASE   default exchange
#
# The dump is written as <name>.part and renamed only when pg_dump succeeded, so a failed or
# interrupted run never leaves a file that looks like a good backup. Exit status non-zero on any
# failure, so systemd records it.
set -euo pipefail
umask 077

BACKUP_DIR="${BACKUP_DIR:-/srv/credo/backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
PG_CONTAINER="${PG_CONTAINER:-credo-postgres-1}"
PG_DATABASE="${PG_DATABASE:-exchange}"

log() {
    echo "credo-pg-backup: $*"
}

case "$KEEP_DAYS" in
    ''|*[!0-9]*|0) log "KEEP_DAYS must be a whole number of days, at least 1, got '$KEEP_DAYS'"; exit 2 ;;
esac

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

stamp="$(date -u +%Y%m%d-%H%M%SZ)"
final="$BACKUP_DIR/${PG_DATABASE}-${stamp}.dump"
part="$final.part"

cleanup() {
    rm -f "$part"
}
trap cleanup EXIT

log "dumping database $PG_DATABASE from container $PG_CONTAINER"
# No -t: without a TTY the binary custom-format dump reaches stdout unmodified.
docker exec "$PG_CONTAINER" pg_dump -U postgres -Fc -d "$PG_DATABASE" > "$part"

if [ ! -s "$part" ]; then
    log "pg_dump produced an empty file; no backup written"
    exit 1
fi

chmod 600 "$part"
mv -f "$part" "$final"
trap - EXIT
log "wrote $final ($(stat -c %s "$final" 2>/dev/null || wc -c < "$final") bytes)"

# Retention: delete finished dumps older than KEEP_DAYS days; never a .part of another run.
removed=0
while IFS= read -r -d '' old; do
    rm -f -- "$old"
    removed=$((removed + 1))
done < <(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${PG_DATABASE}-*.dump" -mtime +"$((KEEP_DAYS - 1))" -print0)
log "retention: $removed dump(s) older than $KEEP_DAYS days removed; $(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${PG_DATABASE}-*.dump" | wc -l) kept"
