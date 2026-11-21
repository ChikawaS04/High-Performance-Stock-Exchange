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
- `docker compose restart exchange` alone: the bots log one WARN each, reconnect with back-off
  (250 ms doubling to 5 s), send `RESEND` from their last seq + 1, catch up, cancel any order the
  venue never acknowledged, and trade again.

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

## Server layout (for P16-10)

- `/opt/credo/`: `compose.yaml`, `credo.env` (owner `david`, mode 600), `postgres-init/`,
  `backup/credo-pg-backup.sh`.
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

Install on the server:

```bash
sudo install -m 755 -o david -g david backup/credo-pg-backup.sh /opt/credo/backup/credo-pg-backup.sh
```

```bash
sudo install -m 644 backup/credo-pg-backup.service backup/credo-pg-backup.timer /etc/systemd/system/
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now credo-pg-backup.timer
```

Restore drill (into a scratch database, then compare and drop):

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
