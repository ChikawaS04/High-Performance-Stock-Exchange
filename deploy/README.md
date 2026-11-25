# deploy/

The venue's container stack (Phase 16). One image, `credo-exchange:<git short sha>`, built from
`app/backend/Dockerfile`, runs four services: `postgres`, the one-shot `migrate`, `exchange` and
`bots`. Caddy and the static terminal run natively on the host (D1, D11).

| File | What it is |
|------|------------|
| `compose.yaml` | The stack as it runs on `credo-exchange-01` (`/opt/credo`) |
| `compose.local.yaml` | Docker Desktop override: smaller limits and heap, the exchange on `127.0.0.1:18080`, the journal on a named volume |
| `credo.env.example` | Every setting, no secret. Copy to `credo.env`, mode 600 |
| `postgres-init/01-exchange-role.sh` | Creates the login role `exchange` and the database `exchange` |
| `backup/` | Nightly `pg_dump` script, systemd service and timer (P16-6) |
| `Caddyfile`, `install-caddy.sh` | Production Caddy config (hash placeholder only) and its one-time installer (P16-10) |
| `deploy.sh` | Deploy, roll back, restart the exchange alone, check and drill from David's PC, in Git Bash (P16-11, Session G) |
| `credo-server.sh`, `credo-lib.sh` | The server half: copied up by `deploy.sh` with scp and run with one ssh, never pasted |
| `test/check-files.sh` | Static checks: `bash -n`, shellcheck if installed, no CR, the Caddyfile placeholder, and `credo-lib.sh`'s helpers on planted input |
| `bench/` | The P16-12 measurement runner (its own README) |

## Deploy, rollback and checks (P16-11)

Everything runs from Git Bash on the PC (not PowerShell, which corrupts binary data piped to ssh). The target
is `david@104.204.69.188` (override with `--target` or `CREDO_TARGET`); ssh runs with `BatchMode=yes` and
`StrictHostKeyChecking=yes`, so a missing key or an unknown host key refuses instead of prompting.

```bash
deploy/deploy.sh
```

Builds HEAD and deploys it. It refuses a tree with tracked changes (except `.idea/workspace.xml` and
`app/frontend/DESIGN_SYSTEM.md`) and a HEAD that is not on `origin/main`. It builds only from
`git -c core.autocrlf=false archive HEAD`, so nothing uncommitted reaches the image or the bundle, and every file
keeps its committed LF. The tag is the first 12 hex digits of HEAD. Steps on the PC: `npm ci`, `npm run build`
(the dist check must pass), `docker build` with the labels `credo.sha`, `credo.flyway` (newest migration) and
`credo.snapshot` (snapshot schema version), then `docker save | gzip | ssh docker load` unless the server already
has that image, one `scp` of the stage and one `ssh` that runs `credo-server.sh`.

On the server, in order (nothing changes until the preflight passes):

1. Lock (`/opt/credo/.deploy.lock`); refuse as root; `credo.env` present, owned by david, mode 600; every key of
   this tag's `credo.env.example` present in it (key names only are printed); the image present with a matching
   `credo.sha`; the compatibility floor (below).
2. The tag's bundle (`compose.yaml`, `credo.env.example`, `postgres-init/`, `backup/`) to
   `/opt/credo/bundles/<tag>/`, and the static release extracted to `releases/.incoming-<tag>` and renamed to
   `releases/<tag>` (files readable by Caddy whatever the umask).
3. The bundle's files installed into `/opt/credo`; the backup script to `/opt/credo/backup/`; the two units to
   `/etc/systemd/system` only when they changed (then `daemon-reload`); `enable --now` of the timer only, so an
   install never runs a dump by itself.
4. `IMAGE_TAG` set in `credo.env` (rewritten under umask 077, so mode 600 and owner david are kept).
5. `docker compose stop bots`, then `stop exchange`: the maker cancels its ladder while the old venue is up, and
   migrations never run under a live venue. (A plain `up -d` recreates the exchange before the bots.)
6. `docker compose up -d` and the `/health` 200 wait, under one deadline (`--health-timeout`, default 600 s).
7. Only after 200: `current` switched with `ln -sfn` to `current.tmp` and `mv -T` (absolute target).
8. A line in `/opt/credo/deploy-history`; pruning; the previous tag printed.

Messages: step 4 prints `IMAGE_TAG unchanged (<tag>)` when `credo.env` already named the tag, otherwise
`IMAGE_TAG set to <tag> (was <old>)`. The previous tag is always the last tag recorded `ok` other than the one
being deployed (never `credo.env`'s old value, which can name a failed attempt or the same tag); with none,
the script says `previous tag: none (no tag other than <tag> is recorded ok)`. The rollback line printed after
a failure follows the same rule.

Every server mode except `--check` refuses while a benchmark run holds `~/credo-bench/.lock` (see
`bench/README.md`); deploy, `--tag` and `--restart-exchange` also hold `/opt/credo/.deploy.lock` for their
whole run and refuse while another holds it.

Every Compose call unsets, for that call, every key named in `credo.env` or `credo.env.example`, because the
calling shell beats `--env-file` (P16-5).

**On a timeout** the script prints the services' states, the last 60 lines of the exchange log and the exact
rollback command, leaves `current` on the old release and exits 1. Nothing is rolled back automatically.

```bash
deploy/deploy.sh --tag <previous tag>
```

Rollback (or forward) to a tag still on the server: no build and no shipping. It installs that tag's own bundle
(its compose file and its example's key set), not the newest one.

**Compatibility floor.** Each attempt that reaches `compose up` records the image's `credo.flyway` and
`credo.snapshot` in the history. A tag below the highest of either is refused unless `--force`, naming the floor
and the tag that set it: Flyway ignores migrations newer than its own (`*:future`, pinned by `MigratorTest`), so an
older image would start on a newer schema, and a newer snapshot cannot be undone.

**Pruning.** The newest 5 tags recorded `ok` are kept (failed attempts never take a slot), plus the current and
the previous tag and `placeholder`; other `credo-exchange:<tag>` images, releases and bundles are removed.

```bash
deploy/deploy.sh --restart-exchange
```

Restarts the exchange alone with `docker compose restart exchange`, under the deploy lock and the same Compose
wrapper, with the bots left running. `restart` names only the exchange: it never recreates the container, never
runs `migrate` and never touches the bots, whose process keeps running, loses its connection and reconnects.
Docker sends SIGTERM, waits up to the 60 s stop timeout (`stop_grace_period`) while the venue writes its shutdown
snapshot, and starts the same container only after the old process has exited.

- **Refuses** (exit 2, nothing changed) when the exchange or the bots are not running (paused counts as not
  running), when `/health` does not answer 200, when no bot is connected (`access.connections.exempt` 0), when
  another deploy holds the lock, or while a benchmark run holds its lock.
- **Waits** for `/health` 200 under `--health-timeout` (default 600 s, over the restart and the wait), checks the
  container and image are unchanged, and prints the old process's exit code (143 is graceful; anything else is
  a WARN, since 137 means it was killed after the stop timeout and wrote no snapshot), its `Snapshot written` and
  `Shutdown complete.` lines, and the new process's `Using snapshot` and `Recovered in` lines.
- **Then waits** for every connected bot's catch-up line under `--catchup-timeout` (default 600 s from `/health`
  200, a progress line every 30 s). The venue serves RESENDs one at a time, 4 exempt slots, so the fifth bot is
  refused BUSY and retries every 5 s; with a full-window read of R s per bot, all five are done in about
  5R + 30 s (65 s at R = 7, 160 s at R = 26). A bot that never catches up gives
  `FAILED: n of 5 bots caught up within 600 s; not caught up: <names>`, the bots' last 20 WARN lines, and exit 1;
  nothing else is restarted, and the bots keep retrying on their own.
- **Prints** every RESEND completion line, the BUSY refusals and every catch-up line of the new process (lines
  from `docker logs --since` the exchange's new `StartedAt`, so an earlier run's or restart's lines never count),
  then `journal.outbound` `startPosition` and `recordedPosition` from `/health`, whose difference is what every
  full RESEND reads, and confirms the bots container is the same one, never restarted.
- **Never** writes a history line, switches `current` or prunes.

Use it for the P16-12 full-window RESEND figure once the journal holds three closes, and for P16-13 acceptance
item 4. A deploy cannot give that figure: it stops the bots, which come back fresh and send no RESEND.

```bash
deploy/deploy.sh --check
```

One PASS, FAIL, WAIT or SKIP line per item: migrate exited 0; postgres and exchange healthy and bots running on
the `credo.env` tag; `/health` 200 on 127.0.0.1:8080; `/market/ignition` 200 with `openPx` and `date`;
`/health` through Caddy 401 without the password; recovery and session open in the exchange log, read only
from the current process (`--since` its `StartedAt`), with the newest session-open line's business date equal
to the one `/health` reports (SKIP while `/health` says the session is closed);
`access.xffFallback` 0 with at least one public connection (WAIT when there is none: open the site in a browser
and re-run), 5 exempt connections, the network gateway at 172.30.0.1 equal to `WS_TRUSTED_PROXIES` and the bots at
172.30.0.10 equal to `WS_EXEMPT_ADDRESSES`; `disk.freeBytes` within 256 MiB of `df /`; `journal.retention` and
`database.retention` present; the backup timer listed and enabled; `current` pointing at the tag. Then the last
RESEND completions and bot catch-ups (for P16-12). `--check --password` asks for the site password on the
server (not shown, not stored, never in a command line) and adds `/health` and `/market/ignition` through Caddy.

```bash
deploy/deploy.sh --drill
```

The P16-6 restore drill: the newest dump into `exchange_drill`, compared with the live database on the rows
stamped before the dump began (minus 5 minutes for the Reporter's lag): `clearing_run` by `closed_ts`, and
`executions` by `event_ts` outside purged dates, each equal and non-zero; the Flyway version equal. The scratch
database is dropped afterwards.

`deploy.sh --dry-run` runs every local step for real, writes the image to a local file and prints each ssh and
scp line instead of running it.

A nightly backup can resume the Reporter only while the journal still holds the dump's checkpoint, so keep
`JOURNAL_RETENTION_DAYS` at 1 or more (P16-7).

## Running

Compose is always run with the settings file, which supplies every value by interpolation:

```bash
docker compose --env-file credo.env up -d
```

No service uses `env_file:`. Each lists only its own variables, so the bots never see the
database password or the Alpaca keys.

**The shell's environment wins over `--env-file`.** Compose interpolation takes a variable from
the calling shell before the file. A shell that exports `DB_URL`, `DB_USER` or `DB_PASSWORD` (as
the development PC does, for `exchange_dev`) silently replaces the file's values. Run Compose from
a shell without them, for example:

```bash
env -u DB_URL -u DB_USER -u DB_PASSWORD -u ALPACA_KEY_ID -u ALPACA_SECRET_KEY docker compose --env-file credo.env up -d
```

## Start order, stop order, restarts

- `postgres` healthy, then `migrate` (`db.MigrateCommand`) must exit 0, then `exchange`
  (validate-only start, `DB_MIGRATE_ON_START=false`) must be healthy, then `bots`.
- `migrate` has `restart: "no"`: under `unless-stopped` a successful one-shot would be restarted
  forever. Every other service is `unless-stopped`.
- `docker compose stop` stops `bots` first (they depend on `exchange`): each bot cancels its
  resting orders, waits up to 3 s for the venue to confirm, and exits within 5 s. Then the
  exchange shuts down gracefully, writing a snapshot, and exits 143 (the JVM's SIGTERM code; this
  is normal). Then `postgres`.
- `docker compose restart exchange` alone (`deploy.sh --restart-exchange` runs it and times it): the
  bots log one WARN each, reconnect with back-off (250 ms doubling to 5 s), send `RESEND` from their
  last seq + 1, catch up, cancel any order whose fate is uncertain (one the venue never acknowledged,
  or one whose cancel was in flight; the maker too since Session G), and trade again.

**Host reboot, and PostgreSQL not up yet.** After a reboot the Docker daemon, not Compose,
restarts the containers and ignores `depends_on`, so the exchange can start before PostgreSQL
answers. Measured on Docker Desktop (P16-5): the exchange does **not** restart-loop. Its startup
reference-data read tries the database 3 times, then starts without it ("An open session trades
on; a day that needs opening stays closed until the database answers"). `/health` answers 200
within about 12 s, with the Reporter and clearing in `BACKING_OFF`. Once PostgreSQL answers they
retry on their own: the Reporter was `RUNNING` with `outboundLag` 0 about 3 s later, and clearing
was back to `IDLE`. The bots wait for `/health` and reconnect by themselves.

## PostgreSQL init runs only on an empty volume

`postgres-init/` runs once, the first time the `pgdata` volume is created. It creates the role
`exchange` (no superuser, no createdb, no createrole) with the password `DB_PASSWORD` had **at that
moment**, and the database `exchange` owned by it. Changing `DB_PASSWORD` in `credo.env` later
does not change the role: run `ALTER ROLE exchange PASSWORD '...'` as `postgres` by hand, or the
`migrate` service fails with "password authentication failed for user exchange". (P16-5 hit exactly
this when a shell's `DB_PASSWORD` reached the first `up`.)

## Server layout (P16-10, as built)

- `/opt/credo/` (david 755): `credo.env` (david, 600, never shipped or overwritten by a deploy), and the running
  tag's `compose.yaml`, `credo.env.example`, `postgres-init/` and `backup/credo-pg-backup.sh`, installed by each
  deploy from `bundles/<tag>/`; `deploy-history`; `.deploy.lock`.
- `/var/www/directroute/releases/<tag>/` and `current`, an absolute link to the live release; `placeholder` is
  never pruned.
- `/etc/caddy/Caddyfile`: installed once by `install-caddy.sh` (P16-10); a deploy never touches it.
- `/srv/credo/data`: the exchange's journal and snapshots, bind-mounted at `/data`. The container
  runs as uid 10001, so: `sudo chown 10001:10001 /srv/credo/data && sudo chmod 700 /srv/credo/data`.
- `/srv/credo/backups`: nightly dumps, created by the backup script, mode 700, files 600.
- The exchange publishes only `127.0.0.1:8080`. Connections from the host (Caddy) arrive inside
  the container from the Compose network's gateway (`172.30.0.1` with the default subnet on
  Docker Desktop); P16-10 confirms the address on the server for `X-Forwarded-For` trust (D15).
- The bots have the static address `BOTS_IPV4` (default `172.30.0.10`) for P16-9's exemption.

## Public access limits (P16-9)

- `WS_TRUSTED_PROXIES` (`172.30.0.1`, the Compose gateway Caddy's connections arrive from) is the only
  socket address whose `X-Forwarded-For` is honoured, and only its rightmost entry. The Caddyfile must not
  set `trusted_proxies`, so Caddy replaces any `X-Forwarded-For` a visitor sends.
- `WS_EXEMPT_ADDRESSES` (`172.30.0.10`, the bots) skips every limit, matched on the socket address only.
- Every other WebSocket frame passes a channel, a client and a venue-wide public bucket; a channel over its
  own or its client's limit for 5 s is closed with 1008; connections beyond 200 venue-wide or 10 per client
  are refused 503 at the upgrade. `/health` `access` shows the counts. After a deploy, `access.xffFallback`
  must stay 0: anything else means every visitor shares one client key.
- `RESEND_PUBLIC_MAX_PENDING=0`: public RESEND is off, because the terminal does not use it and every RESEND
  reads the whole retained outbound journal; raise it when the terminal adopts RESEND.

## Disk space and retention (P16-7, P16-8)

- **Journal retention** keeps the current day plus `JOURNAL_RETENTION_DAYS` closed days (P16-7).
- **Database retention** deletes `orders`, `executions` and `order_audit` rows of business dates outside
  the newest `DB_RETENTION_DAYS` COMPLETE dates, after each clearing run and once a day, in batches of
  `DB_RETENTION_BATCH_ROWS`, then runs `VACUUM (ANALYZE)` (never FULL) on the tables it touched.
  Candles, clearing runs and obligations, `daily_summary`, balances and positions are kept. A resting
  GTC order's row is never deleted. `/api/trades` and `/api/reports/statement` answer
  `410 {"error":"PURGED"}` for a purged date; `/api/reports/daily`, `/volume` and `/candles` answer as
  before.
- **What `/health` `disk` measures.** `freeBytes` and `totalBytes` are of the filesystem that holds
  `/data`. On the server `/data` is the bind mount of `/srv/credo/data` on the root disk, which also holds
  `/var/lib/docker` (PostgreSQL's `pgdata` volume, images, container logs), so `freeBytes` falls when any
  of them grows. `dataBytes` is the journal and snapshots only; the exchange container cannot see
  PostgreSQL's size (`docker system df -v` on the host can).
- **Hard stop.** Below `DISK_STOP_FREE_BYTES` (5 GiB) the venue refuses NEW orders before sequencing
  (the client gets an `EXEC ORDER_REJECTED` with no `seq`), still accepts cancels, logs one ERROR (hourly
  while it lasts), and shows `disk.state: STOP`. NEW is accepted again at the stop value plus
  `DISK_STOP_HYSTERESIS_BYTES`. `disk.retentionHeld` names what keeps retention from freeing space (for
  example a FAILED Reporter).
- **If the disk still fills.** Aeron's Archive checks its low storage threshold
  (`JOURNAL_LOW_STORAGE_BYTES`, 1 GiB) only when a recording starts or extends. A running recording
  writes until a write fails; then the recording stops and the engine fail-stops (exit 70) at the journal
  gate. Docker restarts it, and every start at or below 1 GiB free exits 2 before writing anything, so
  the restart loop is harmless. Free space by hand (old dumps in `/srv/credo/backups`, `docker image
  prune`), then the venue starts.

## Backups (P16-6)

`backup/credo-pg-backup.sh` runs `pg_dump -Fc` inside the `postgres` container through
`docker exec`, writes `/srv/credo/backups/exchange-<UTC stamp>.dump` (mode 600, written as `.part`
and renamed only on success), and deletes dumps older than 14 days. The timer runs it daily at
06:15 UTC (after the New York midnight roll and clearing in winter and summer time),
`Persistent=true`, with up to 5 minutes of random delay.

Every deploy installs the script and the units (only when they changed) and enables the timer (P16-11);
nothing needs installing by hand. `deploy/deploy.sh --drill` runs the restore drill below and compares the counts.

Restore drill by hand (into a scratch database, then compare and drop):

```bash
docker exec credo-postgres-1 createdb -U postgres -O exchange exchange_drill
```

```bash
docker exec -i credo-postgres-1 pg_restore -U postgres -d exchange_drill --no-owner --role=exchange < /srv/credo/backups/<newest>.dump
```

```bash
docker exec credo-postgres-1 psql -U postgres -d exchange_drill -Atc "select (select count(*) from executions), (select count(*) from clearing_run)"
```

```bash
docker exec credo-postgres-1 dropdb -U postgres exchange_drill
```

## Local smoke run (Docker Desktop)

```bash
docker build -t credo-exchange:local app/backend
```

```bash
env -u DB_URL -u DB_USER -u DB_PASSWORD docker compose -p credo --env-file <scratch>/credo.local.env -f deploy/compose.yaml -f deploy/compose.local.yaml up -d
```

The scratch env file is a copy of `credo.env.example` with `IMAGE_TAG=local`, throwaway passwords
and, with no Alpaca keys, `BOTS_OPEN_PX` near the real price. The terminal connects through the
Vite proxy with `EXCHANGE_BACKEND_URL=http://localhost:18080 npm run dev`.
