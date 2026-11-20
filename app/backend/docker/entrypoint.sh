#!/usr/bin/env bash
# Entrypoint of the credo-exchange image (P16-5, D4). The first argument picks the process:
#
#   exchange   the venue (Main)
#   migrate    apply pending Flyway migrations and exit (db.MigrateCommand)
#   bots       the five-bot simulation (participant.ParticipantSimMain); further arguments are
#              passed to it as flags
#
# It ends in exec, so java is PID 1 and receives Docker's SIGTERM directly. The Agrona flag is
# here, not in JAVA_OPTS, so it can never be lost; JAVA_OPTS carries every other JVM option and is
# split on spaces on purpose.
set -euo pipefail

mode="${1:-exchange}"
if [ "$#" -gt 0 ]; then
    shift
fi

case "$mode" in
    exchange) main="Main" ;;
    migrate)  main="db.MigrateCommand" ;;
    bots)     main="participant.ParticipantSimMain" ;;
    *)
        echo "usage: entrypoint.sh exchange|migrate|bots [bot flags...]; got '$mode'" >&2
        exit 64
        ;;
esac

# shellcheck disable=SC2086
exec java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED ${JAVA_OPTS:-} \
    -cp '/app/app.jar:/app/lib/*' "$main" "$@"
