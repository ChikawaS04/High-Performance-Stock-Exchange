// flood-client.mjs: the P16-13 acceptance item 11 run, from David's PC (never shipped to the server).
// Started by `deploy/deploy.sh --flood`, which reads the site password without echo and pipes it here as the
// first line of stdin (an empty line when the site is public); it is never in argv, the environment or a file.
//
//   node deploy/flood-client.mjs --site <https://host> --venue <http://127.0.0.1:port> [--rate 50]
//        [--max-seconds 20] [--xff 192.0.2.77]
//
// Two public clients with different client keys (P16-9):
//  - the flooder connects through Caddy (`--site`, with the password when one is given), so the venue keys it
//    by the PC's public address, and sends {"type":"RESEND","fromSeq":1} at --rate frames a second until the
//    venue closes it or --max-seconds pass;
//  - the steady client connects to the venue directly (`--venue`, an ssh tunnel to 127.0.0.1:8080 on the
//    server), arriving from the trusted proxy with X-Forwarded-For --xff, so it has its own client key and
//    shares only the venue-wide public bucket; it sends one RESEND a second for the flood plus 5 s.
// Public RESEND is off in production (RESEND_PUBLIC_MAX_PENDING=0), so every admitted RESEND is answered
// RESEND_REJECTED BUSY to its sender only, never journaled and never near the book: the answers count the
// admitted frames. /health on --venue gives the venue's own counters before and after, and is polled every
// second for the exempt (bots') connections, which must never drop.
// Needs Node 22 or later (a global WebSocket that accepts headers). Exit 0 on PASS, 1 on FAIL, 2 on a refusal.

const args = parseArgs(process.argv.slice(2));
const SITE = args.site;
const VENUE = args.venue;
const RATE = num(args.rate, 50);
const MAX_SECONDS = num(args["max-seconds"], 20);
const XFF = args.xff ?? "192.0.2.77";
// P16-9 defaults: channel and client 10/s with a burst of 30; a channel over its limit for 5 s is closed.
const BURST = 30;
const LIMIT_RATE = 10;
const SLACK = 5;

if (typeof WebSocket !== "function") refuse("this Node has no global WebSocket; use Node 22 or later");
if (!SITE || !VENUE) refuse("--site and --venue are required");

const password = await readFirstLine();
const wsUrl = (base) => base.replace(/^http/, "ws").replace(/\/+$/, "") + "/ws";
const health = async () => {
    const r = await fetch(VENUE.replace(/\/+$/, "") + "/health", { signal: AbortSignal.timeout(5000) });
    return r.json();
};
const access = (h) => ({
    exempt: h?.access?.connections?.exempt,
    publicConnections: h?.access?.connections?.public,
    channel: h?.access?.framesDropped?.channel ?? 0,
    client: h?.access?.framesDropped?.client ?? 0,
    public: h?.access?.framesDropped?.public ?? 0,
    closed1008: h?.access?.closed1008 ?? 0,
});
const isAnswer = (data) => {
    try {
        const m = JSON.parse(String(data));
        return m.type === "RESEND_REJECTED";
    } catch {
        return false;
    }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const resend = JSON.stringify({ type: "RESEND", fromSeq: 1 });

let before;
try {
    before = access(await health());
} catch (e) {
    refuse(`/health on ${VENUE} did not answer: ${e.message}`);
}
if (typeof before.exempt !== "number") refuse("/health has no access object");
console.log(`before: exempt ${before.exempt}, public ${before.publicConnections}, framesDropped channel ${before.channel}`
    + ` client ${before.client} public ${before.public}, closed1008 ${before.closed1008}`);

// The steady client.
const steady = { sent: 0, answered: 0, closed: null };
const steadyWs = new WebSocket(wsUrl(VENUE), { headers: { "X-Forwarded-For": XFF } });
steadyWs.onmessage = (e) => { if (isAnswer(e.data)) steady.answered++; };
steadyWs.onclose = (e) => { steady.closed = e.code; };
await opened(steadyWs, "steady client");
const steadyTimer = setInterval(() => {
    if (steadyWs.readyState === WebSocket.OPEN) { steadyWs.send(resend); steady.sent++; }
}, 1000);

// The exempt watch.
let exemptMin = before.exempt;
let exemptSamples = 0;
const watch = setInterval(async () => {
    try {
        const a = access(await health());
        exemptSamples++;
        if (typeof a.exempt === "number") exemptMin = Math.min(exemptMin, a.exempt);
    } catch { /* a missed sample is not a drop */ }
}, 1000);

// The flooder.
const headers = password ? { Authorization: "Basic " + Buffer.from(`credo:${password}`).toString("base64") } : {};
const flood = { sent: 0, admitted: 0, code: null, reason: "", ms: null };
const floodWs = new WebSocket(wsUrl(SITE), { headers });
floodWs.onmessage = (e) => { if (isAnswer(e.data)) flood.admitted++; };
await opened(floodWs, "flooder (through Caddy)");
const t0 = Date.now();
const closedPromise = new Promise((resolve) => {
    floodWs.onclose = (e) => { flood.code = e.code; flood.reason = e.reason; flood.ms = Date.now() - t0; resolve(); };
});
const floodTimer = setInterval(() => {
    if (floodWs.readyState === WebSocket.OPEN) { floodWs.send(resend); flood.sent++; }
}, Math.max(1, Math.round(1000 / RATE)));
await Promise.race([closedPromise, sleep(MAX_SECONDS * 1000)]);
clearInterval(floodTimer);
if (flood.code === null) {
    floodWs.close(1000);
    await Promise.race([closedPromise, sleep(2000)]);
}
const elapsed = (flood.ms ?? Date.now() - t0) / 1000;

// The steady client runs 5 s more, then waits for its last answer.
await sleep(5000);
clearInterval(steadyTimer);
await sleep(1500);
const steadyClosedEarly = steady.closed !== null;
steadyWs.close(1000);
clearInterval(watch);

let after;
try {
    after = access(await health());
} catch (e) {
    after = null;
}

const dropped = flood.sent - flood.admitted;
const bound = BURST + Math.ceil(LIMIT_RATE * elapsed) + SLACK;
console.log(`flooder: ${flood.sent} sent (${(flood.sent / Math.max(elapsed, 0.001)).toFixed(0)}/s achieved, ${RATE}/s asked),`
    + ` ${flood.admitted} admitted (answered), ${dropped} dropped;`
    + ` closed ${flood.code ?? "never"}${flood.reason ? ` (${flood.reason})` : ""} after ${elapsed.toFixed(2)} s`);
console.log(`steady client (X-Forwarded-For ${XFF}): ${steady.sent} sent, ${steady.answered} answered,`
    + ` ${steadyClosedEarly ? `closed by the venue with ${steady.closed}` : "never closed by the venue"}`);
if (after) {
    console.log(`after: exempt ${after.exempt}; deltas framesDropped channel +${after.channel - before.channel}`
        + ` client +${after.client - before.client} public +${after.public - before.public}, closed1008`
        + ` +${after.closed1008 - before.closed1008}`);
}
console.log(`exempt connections: ${before.exempt} before, lowest ${exemptMin} in ${exemptSamples} one-second samples,`
    + ` ${after?.exempt ?? "?"} after (exempt frames are never metered, P16-9, so a lost bot shows as a lost connection)`);

let fails = 0;
const check = (ok, text) => { console.log(`${ok ? "PASS" : "FAIL"}  ${text}`); if (!ok) fails++; };
check(flood.code === 1008, `the flooder was closed with 1008 (got ${flood.code ?? "no close"})`);
check(flood.admitted <= bound, `admitted ${flood.admitted} within the bucket's bound ${bound} (burst ${BURST} + ${LIMIT_RATE}/s x ${elapsed.toFixed(1)} s + ${SLACK})`);
check(dropped > 0, `${dropped} frames dropped`);
check(after !== null && after.channel > before.channel, "the venue counted channel drops");
check(after !== null && after.closed1008 > before.closed1008, "the venue counted the 1008 close");
check(steady.sent > 0 && steady.answered === steady.sent && !steadyClosedEarly,
    `the steady client got an answer to every frame (${steady.answered} of ${steady.sent}) and stayed open`);
check(exemptMin >= before.exempt && after !== null && after.exempt === before.exempt,
    `no exempt (bot) connection was lost (${before.exempt} throughout)`);
console.log(fails === 0 ? "flood: no FAIL" : `flood: ${fails} FAIL`);
process.exit(fails === 0 ? 0 : 1);

function parseArgs(list) {
    const out = {};
    for (let i = 0; i < list.length; i++) {
        if (!list[i].startsWith("--")) refuse(`unexpected argument ${list[i]}`);
        out[list[i].slice(2)] = list[i + 1];
        i++;
    }
    return out;
}

function num(v, d) {
    if (v === undefined) return d;
    const n = Number(v);
    if (!Number.isFinite(n) || n <= 0) refuse(`not a positive number: ${v}`);
    return n;
}

function refuse(text) {
    console.error(`flood-client: REFUSED: ${text}`);
    process.exit(2);
}

function opened(ws, name) {
    return new Promise((resolve) => {
        const timer = setTimeout(() => refuse(`${name} did not connect within 10 s`), 10000);
        ws.onopen = () => { clearTimeout(timer); resolve(); };
        ws.onerror = () => { if (ws.readyState !== WebSocket.OPEN) { clearTimeout(timer); refuse(`${name} could not connect`); } };
    });
}

async function readFirstLine() {
    let text = "";
    for await (const chunk of process.stdin) {
        text += chunk;
        if (text.includes("\n")) break;
    }
    const nl = text.indexOf("\n");
    return (nl >= 0 ? text.slice(0, nl) : text).replace(/\r$/, "");
}
