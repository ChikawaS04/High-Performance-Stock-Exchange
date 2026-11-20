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

- `/opt/credo/`: `compose.yaml`, `credo.env` (owner `david`, mode 600), `postgres-init/`.
- `/srv/credo/data`: the exchange's journal and snapshots, bind-mounted at `/data`. The container
  runs as uid 10001, so: `sudo chown 10001:10001 /srv/credo/data && sudo chmod 700 /srv/credo/data`.
- The exchange publishes only `127.0.0.1:8080`. Connections from the host (Caddy) arrive inside
  the container from the Compose network's gateway (`172.30.0.1` with the default subnet on
  Docker Desktop); P16-10 confirms the address on the server for `X-Forwarded-For` trust (D15).
- The bots have the static address `BOTS_IPV4` (default `172.30.0.10`) for P16-9's exemption.

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
