# deploy/bench/

The P16-12 measurement run on `credo-exchange-01`: the P15-13 benchmarks plus three P16-12 measurements, run on
the server in `maven:3.9-eclipse-temurin-21` against the source archive of HEAD, with the venue stopped. Nothing
here changes a default; the defaults are chosen from the results afterwards.

| File | What it is |
|------|------------|
| `bench.sh` | PC side, Git Bash: `start`, `status`, `fetch`, `list` |
| `credo-bench.sh` | Server side: checks, then a detached run (survives an SSH disconnect); sources `../credo-lib.sh` |

## Commands

```bash
deploy/bench/bench.sh start --stop-venue
```

Copies `credo-bench.sh`, `credo-lib.sh` and `git archive HEAD app/backend` (LF kept) with one scp and starts the
run with one ssh. Without `--stop-venue` it refuses while the exchange or the bots run. With it, the run stops the
bots, then the exchange, and starts them again at the end, after a failure or a timeout too, waiting for `/health`
200 (bound 600 s); the result is in the status file. Other options: `--quick` (one tiny cell per block, to prove
the plumbing), `--only A,C,...`, `--journal-busy-spin` (adds the journal busy-spin cells; only once Synteq has
confirmed dedicated cores, D17).

```bash
deploy/bench/bench.sh status <run id>
```

```bash
deploy/bench/bench.sh fetch <run id>
```

Results land in `bench-results/p16-12/<run id>/` (git-ignored): one log (and JMH JSON or text report) per cell,
`manifest.txt` (commit, `uname`, `nproc`, `lscpu`, memory, Docker), `status`, `runner.log`.

## Where it runs

`~/credo-bench/` on the server: `m2/` (the Maven cache, owned by david; the container runs as david),
`runs/<run id>/` (the scripts, `src.tgz`, `src/`, `results/`, a per-cell `scratch/` deleted when the cell ends),
`.lock` (one run at a time). The run refuses with less than 15 GiB free.

Each cell: `docker run --rm --memory 6g --shm-size 512m` (`BENCH_MEMORY` overrides; PostgreSQL keeps running
for block F and has its own 2 GB limit), the Aeron directory on `/dev/shm` as in production, the Archive on the
disk under the cell's scratch folder, a 20 min timeout per cell (`CELL_TIMEOUT`) and 4 h for the run
(`RUN_TIMEOUT`).

## Blocks

| Block | What | Cells | Estimate |
|-------|------|-------|----------|
| A | `MatchingEngineThroughputBenchmark` (engine alone) | -f 3 | 2 min |
| B | `PipelineThroughputBenchmark`, journaled: engine {busy-spin, backoff} x ring {blocking, yielding, busy-spin}, journal backoff; plus the unjournaled reference | -f 3, 10 x 1 s warmup, 10 x 2 s | 15 min |
| C | `JournaledEndToEndLatencyBenchmark`, closed loop (100,000 measured) and open loop at 20k/s (600,000), configurations C1 to C7 below, 3 replicates interleaved | 42 | 40 to 100 min |
| K | C1 and C2 again with `--cpuset-cpus=1-3` | 12 | 12 to 25 min |
| D | Archive delete blip: C2 open loop, 1,200,000 orders (60 s), purge off and on (every 1 s, production 16 MiB segments) | 6 | 8 min |
| E | `ResendReadBenchmark`: outbound recordings of 100 and 400 MiB, 5 reads each from the last seq + 1 | 1 | 8 min |
| F | one database retention pass: the newest nightly dump restored into `exchange_bench`, `retention-once` with `DB_RETENTION_DAYS=0`, then dropped | 1 | 5 to 15 min |

Total about 1.5 to 2.75 hours.

| Config | Engine idle | Journal idle | Archive | Rings |
|--------|-------------|--------------|---------|-------|
| C1 | backoff | backoff | shared | blocking (today's defaults) |
| C2 | busy-spin | backoff | shared | blocking (the handoff's starting layout) |
| C3 | busy-spin | yielding | shared | blocking |
| C4 | backoff | yielding | shared | blocking |
| C5 | busy-spin | backoff | dedicated | blocking |
| C6 | backoff | backoff | dedicated | blocking |
| C7 | busy-spin | backoff | shared | yielding (warns at startup: seven hot threads) |
| C8, C9 | busy-spin / backoff | busy-spin | shared | blocking (only with `--journal-busy-spin`) |

## What each P16-12 measurement reports

- **D, the delete blip** (P16-7 finding 6). A purge thread with its own Archive client purges both recordings up to
  the segment holding the last byte. `purgeSegments` returns before the Archive deletes, so each purge records the
  call and the moment its segment files are gone. The report: purges and files, call time and time to deletion
  (mean, max), and the worst latency of orders due from a purge call to its deletion plus 50 ms, against the run's
  p99.9. Compare with the purge-off runs' percentiles.
- **E, the full-window RESEND read** (P16-9 finding 2). Read time, events and bytes per read, MiB/s, at each size,
  page cache warm. The decisive figure is still the real one on the server once the journal holds the full window
  (after the third close): the venue's `RESEND fromSeq ... (read E events, B bytes from position P)` line and the
  bots' `caught up ... in T ms` line, shown by `deploy/deploy.sh --check` after a restart.
- **F, the purge rate and VACUUM** (P16-8 finding 3). The venue's own lines: per date, rows, ms and rows/s per
  table; `VACUUM (ANALYZE) <table> took T ms` per table; plus row counts before and after. Then the refusal check:
  `retention-once` against the live database name must exit 2.

## retention-once

An entrypoint mode of the image (`clearing.RetentionOnceCommand`): one database retention pass on the calling
thread, then exit 0 (PURGED or NOTHING_TO_PURGE), 1 (skipped or failed) or 2 (refused). It refuses, before
connecting, any database whose name does not end in `_bench` or `_test`, so it can never run against `exchange` or
`exchange_dev`.
