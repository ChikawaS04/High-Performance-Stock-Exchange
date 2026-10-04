# OMS Trading Terminal — Frontend

A React + TypeScript trading terminal for a practice Order Management System. It renders a
live depth ladder, a trade tape, a session price chart, and manual order-entry /
open-orders panels across two routed pages, driven entirely by a single WebSocket
connection to the Java backend — no polling, no REST.

This is the frontend edge of a full-stack portfolio project. The backend is a low-latency,
lock-free matching engine (LMAX Disruptor ring buffers, a hand-rolled FIX tag-value gateway,
a Netty WebSocket server); this app *consumes* its `BOOK`/`EXEC` frames and *produces*
`NEW`/`CANCEL` frames. It adds no trading behaviour of its own.

---

## Architecture

The frontend is a pure protocol edge over one socket. Outbound, an order flows
`React form → NEW/CANCEL JSON (integer cents) → WebSocket`; server-side that JSON is
transcoded to FIX 4.2, framed onto the inbound Disruptor ring, matched by the single-threaded
engine, and the results are published back onto the outbound and snapshot rings, reserialized
to JSON, and pushed to every connected client. Inbound, the app narrows each raw frame in
exactly one place (`protocol/messages.ts`), a pure reducer (`state/reducer.ts`) folds frames
into book/tape/myOrders state plus session aggregates (`sessionVolume`, `sessionOpenCents`, the
session high / low / trade-count accumulators, a client-assigned `msgSeqNum`) and a capped FIX/EXEC inspector log (`inspectorLog`), and a
single `useOrderBook` hook owns the socket lifecycle (connect, capped-backoff reconnect,
dispatch). Every price is an integer number of cents internally and on the wire; dollars
exist only at the render/parse edge, converted with string arithmetic so no floating-point
error ever reaches a price. **`BOOK` frames are the sole authority on book state; `EXEC`
frames are notifications only** — the two never cross-contaminate.

---

## Pages

The terminal is two routed pages behind one socket. `main.tsx` mounts `BrowserRouter`;
`App.tsx` owns the socket and renders the persistent header strip and navbar above
`<Routes>`, so a route change swaps only the body beneath them. `/` redirects to `/trading`.

**That placement is load-bearing.** The single `useOrderBook` instance, the reducer state,
the book, the tape and every session aggregate live above the router, so moving between
pages does not reconnect the socket, reset the book, or lose a single print.

| Route | Page | Contents |
|---|---|---|
| `/trading` | Trading terminal | Depth ladder, depth curve, trade tape, order-entry ticket, open-orders blotter, cancel-by-ID, FIX inspector |
| `/chart` | Price Chart | Session price line (left region), session stats and a second order-entry ticket (right region) |

What does *not* survive navigation is page-local UI state, because leaving a route unmounts
the page: the FIX inspector's open flag, and anything held inside a panel, including a
half-typed ticket. That is a deliberate consequence of keeping the socket above the router
rather than a defect, and it is the one thing to expect when switching pages mid-order.

`App.tsx` stays the sole place a `clOrdId` is minted and the sole caller of the frame
builders. Both pages receive state slices and bound handlers, never the encoders, so the two
order-entry tickets are two consumers of one submit path, not two owners of it. An order
placed on the Price Chart page is indistinguishable on the wire from one placed on the
Trading page, lands in the same blotter, and its fill prints on the same tape, the same
chart and the same session stats.

---

## Screenshots

<!-- Capture on a running stack (backend on :8080, frontend on :5173) and drop the files in docs/img/ -->

- **Populated depth ladder + Live badge** — after placing a resting order
  `![Depth ladder](docs/img/ladder.png)`
- **A fill in the trade tape** — after crossing that order
  `![Trade tape](docs/img/tape.png)`
- **Open orders with a cancellable row**
  `![Open orders](docs/img/open-orders.png)`
- **Short GIF: place → cross → cancel round-trip**
  `![Round-trip demo](docs/img/demo.gif)`
- **Session price line with a reference line in domain** (Price Chart page)
  `![Price chart](docs/img/chart.png)`
- **Side panel: session stats above the panel ticket**
  `![Side panel](docs/img/side-panel.png)`

---

## Running the stack

Bring up the backend first, then the frontend. The frontend connects on load and will sit in
a reconnecting state until the server is up.

### 1. Backend (Java matching engine + WebSocket server)

The server's entry point is `Main` at the root of `src/main/java` (no package). It binds
`ws://localhost:8080/ws` and logs the endpoint plus copy-pasteable sample order JSON on
startup.

- **From IntelliJ (recommended):** open the Maven project at the repo root, let it sync
  dependencies, then run `Main` via the green gutter arrow (or right-click → *Run 'Main'*).
- **Port:** defaults to `8080`; override with a single program argument
  (e.g. run config program arguments `9090`, or `<arg>` on the CLI). Unparseable input logs a
  warning and falls back to `8080`.
- **CLI alternative:** if the `exec-maven-plugin` is configured in the pom,
  `mvn compile exec:java -Dexec.mainClass=Main` runs it from a terminal. If that goal isn't
  wired, use the IntelliJ run above (it assembles the classpath — Netty, Jackson, Disruptor,
  SLF4J — for you).

The endpoint the frontend expects: **`ws://localhost:8080/ws`** (no TLS — `ws://`, not
`wss://`; §3.6, demo only).

### 2. Frontend (this app)

Requires **Node 20.19+ or 22.12+** (Vite 8). Check with `node -v` first.

```bash
cd frontend          # run every command from inside frontend/, never the repo root
npm install          # first run only; commits package-lock.json
npm run dev          # → http://localhost:5173
```

Other scripts:

```bash
npm test             # vitest run — the pure + component suites
npm run test:watch   # vitest in watch mode
npm run build        # tsc (strict typecheck) && vite build
```

The socket URL is read from `frontend/.env`:
VITE_WS_URL=ws://localhost:8080/ws

If unset, `useOrderBook` falls back to that same default, so the app works out of the box
against a local backend on the default port. Point `VITE_WS_URL` elsewhere to target a
different host/port.

For the header Open field (P11), the Vite dev server proxies `/alpaca` to Alpaca. Put
your Alpaca market-data credentials in `frontend/.env.local` (gitignored), which the dev
server reads server-side; they are never bundled into the client:

```
ALPACA_KEY_ID=your-key-id
ALPACA_SECRET_KEY=your-secret-key
```

Without them the Open field shows the empty marker and everything else runs unchanged.

---

## Wire contract

Single instrument (`ASML`). **Integer cents in both directions — no floats on the wire.**
No symbol/heartbeat/session fields on outbound server frames (no FIX session management).

### Client → server

```jsonc
// NEW — price is integer cents; server transcodes cents → FIX decimal dollars
{ "type": "NEW", "clOrdId": 1, "side": "BUY", "price": 15000, "qty": 10, "symbol": "ASML" }

// CANCEL — origClOrdId is the resting order being cancelled
{ "type": "CANCEL", "clOrdId": 3, "origClOrdId": 1 }
```

### Server → client

```jsonc
// BOOK — authoritative, wholesale book state; top-10 levels/side, valid prefix only.
// -1 tops and empty arrays on an empty side. bids highest-first, asks lowest-first.
{
  "type": "BOOK",
  "bestBid": 15000,
  "bestAsk": -1,
  "bids": [[15000, 10]],
  "asks": [],
  "timestamp": 123456789
}

// EXEC — all nine fields ALWAYS present; -1 for not-applicable (keys are never omitted).
// execType is the full enum name.
{
  "type": "EXEC",
  "execType": "ORDER_FILLED",
  "orderId": 2,
  "tradeId": 1,
  "price": 15000,
  "filledQuantity": 4,
  "remainingQuantity": 0,
  "aggressorOrderId": 2,
  "passiveOrderId": 1,
  "timestamp": 123456790
}
```

`execType` is one of `ORDER_ACCEPTED`, `ORDER_FILLED`, `ORDER_PARTIALLY_FILLED`,
`ORDER_CANCELLED`, `ORDER_REJECTED`.

### Correlation facts (confirmed against the backend, not assumed)

- **The client owns order IDs.** `clOrdId` is monotonic, numeric, seeded at `Date.now()`, and
  generated in exactly one place. It is echoed back as EXEC `orderId`
  (a `BUY` sent as `clOrdId 1` returns `ORDER_ACCEPTED orderId 1`).
- **On a fill, EXEC `orderId` is the aggressor** (incoming) order; `aggressorOrderId` /
  `passiveOrderId` name both sides; `price` is the **passive resting price**;
  `remainingQuantity` is the aggressor's remaining size.
- **`ORDER_CANCELLED` carries `orderId == origClOrdId`** — the cancelled resting order's id,
  not the cancel request's own `clOrdId`.

---

## Behaviour you should know (honest caveats)

These are real properties of the running system, documented rather than glossed:

- **`BOOK` is authoritative; `EXEC` is a notification.** EXEC and BOOK arrive on independent
  Disruptor consumers with separate sequence counters, so **they can interleave out of order**
  — a BOOK can be written before an EXEC with an earlier timestamp. The client never infers
  book state from EXEC arrival order and never merges EXEC into the book. This is the single
  load-bearing constraint of the frontend.
- **A passive resting order receives no EXEC of its own.** The engine fires one fill event per
  trade, naming only the aggressor; `passiveOrderId` names the resting order but its remaining
  quantity is never reported. The blotter closes this gap client-side (P7-8): on a fill where
  `passiveOrderId` matches one of your own orders, the row's remaining quantity is decremented
  locally by that fill's `filledQuantity`, and the row transitions to PARTIALLY_FILLED or
  FILLED accordingly. This is a documented client-side derivation, not a server-reported value;
  EXEC stays the sole authority for the aggressor side of every fill. Per-passive EXEC
  reporting would still be a server change and remains out of scope.
- **Session change is measured against the session's first trade, never a previous close.**
  There is no daily close on the wire (single session, no persistence across runs), so the
  header's session-change figure, set once by the session's first fill and held in reducer
  state, is labelled *session change*, not daily change, and never presented as one. It resets
  only when the app reloads (a fresh in-memory session), not on a reconnect.
- **A freshly (re)connected client sees an empty ladder until the next order event.** The
  server pushes a BOOK only per inbound event; there is no snapshot-on-connect message
  (deliberately out of scope). Place any order and the ladder repopulates. On a socket drop the
  client clears the book (a stale ladder is worse than an empty one) and reconnects with
  exponential backoff.
- **`timestamp` is epoch nanoseconds.** Frames are stamped by `EpochNanoClock`, which anchors
  once at server startup (capturing `System.currentTimeMillis()` and `System.nanoTime()`
  together) and thereafter serves `anchorEpochNanos + (System.nanoTime() - anchorNanos)`. The
  value is true Unix epoch nanoseconds and renders directly as wall-clock time. Because the
  transform is affine, inter-event deltas stay exact. The one caveat is anchor drift: the
  anchor is captured once, so the clock does not follow wall-clock adjustments (NTP step or
  slew) made after startup, and over a long-running session its absolute value can drift from
  true wall-clock by the accumulated correction. Relative ordering and deltas are unaffected.
- **The system is FIX inbound, JSON outbound, and the FIX inspector says so plainly.** The
  server parses inbound FIX (`35=D` NEW, `35=F` CANCEL) but builds no outbound FIX; execution
  reports leave the server as JSON `EXEC` frames, never as a `35=8` (ExecutionReport) tag-value
  message. The inspector's IN/OUT badges mean order-flow direction at the matching system: a
  FIX packet is inbound to the engine, an EXEC report is outbound from it, not the browser
  WebSocket direction, where both arrive as inbound WebSocket messages. Inbound entries show
  the real echoed FIX bytes the server parsed (P7-2); outbound entries show the actual EXEC
  JSON, labelled as such, never a fabricated FIX message.
- **The Open field is the instrument's official daily open, pulled once from Alpaca.**
  On app load a dedicated hook fetches the snapshot open (`dailyBar.o`) once, converts it
  to integer cents at the edge (reusing `dollarsToCents`), and shows it in the header Open
  field. It is a distinct quantity from Chg: Chg is the session change against the engine's
  first trade and is not re-anchored to the market open, so the header never conflates the
  two opens. If Alpaca is unreachable, the credentials are missing, or the value is
  unusable, Open shows the empty marker and Chg is unaffected. The fetch is off the socket
  entirely and never runs on the hot path, and it is fetched once per app open (it does not
  roll across a trading-day boundary without a reload).
- **No server reject feedback for malformed input.** Bad orders are logged and dropped
  server-side with no message back to the client, so the UI validates price/quantity locally
  before sending (`> 0`, `≤ 2` decimal places, positive integer qty). `ORDER_REJECTED` can
  still arrive (e.g. cancelling an unknown order) and is handled if it does.
- **The price chart is bounded to the last 200 prints, and a reference line never widens the
  domain.** The chart plots `state.tape`, which the reducer caps at `TAPE_CAP` (200), so it
  is a chart of the most recent 200 trades rather than of the whole session; past the cap the
  left edge advances as old prints age out. Two dashed reference lines can appear: the
  engine's first-trade anchor (the same value the header Chg uses) and the Alpaca market
  open. Each draws only when its price falls inside the domain the prints themselves set, and
  is silently omitted otherwise. That guard does real work rather than defending against a
  rounding edge: the synthetic book trades near $100 while the instrument's real market open
  sits near $1740, so the market-open line is usually out of domain and correctly absent. An
  empty tape plots a quiet "No trades yet" frame and a single print plots its marker with no
  line, so no degenerate case produces a NaN path.
- **Side panel session stats are connect-anchored, and are not bounded by the tape cap the
  chart is.** The Price Chart page's side panel shows session high, session low, trade count,
  and the last print (price and size). High, low and the count are accumulated in the reducer
  on each fill, never folded from the trade tape, precisely because the tape is capped at
  200: a tape-derived high would silently mean "high of the last 200 prints" and would fall
  as old prints aged out. They are connect-anchored, meaning they accumulate from the moment
  this browser session connected rather than from the true venue session open, so connecting
  mid-session undercounts all three. That is the same honest caveat already carried for
  session volume and the first-trade anchor, and it has the same clean fix: the engine
  publishing session stats, or a snapshot on connect, which is a server change. They survive
  navigation, because the socket and reducer sit above the router, and they survive a
  reconnect blip, because the connection branch clears only the book and keeps the
  aggregates. A full page reload starts a fresh session and resets them. The ticket below the
  stats is the same `OrderEntry` component and the same submit path as the Trading page; only
  its density differs, and that is CSS.

---

## Manual end-to-end checklist

Run against the live backend (`Main` on `:8080`) with the frontend on `:5173`. This is the
Phase 5 acceptance gate. (The pure-logic and component-render suites should already be green
as a precondition; they cover the reducer and rendering, but the round-trip below is what
closes the phase. The Phase 7 acceptance run, covering the FIX inspector and the depth curve,
is tracked separately in `Build_Guide_Phase7.md`.)

1. **Start both.** Backend up on `:8080`, `npm run dev` up on `:5173`, browser open on
   `http://localhost:5173`.
2. **Connect.** The connection badge shows **Live**.
3. **Place a resting order** — e.g. `BUY 10 @ 150.00`.
    - Depth ladder populates with a bid level `150.00 × 10`.
    - Open Orders shows a row: your `clOrdId`, `BUY`, `150.00`, remaining `10`, status
      **OPEN** (from `ORDER_ACCEPTED`, `orderId == clOrdId`).
4. **Cross it** — e.g. `SELL 4 @ 150.00`.
    - Trade tape shows a fill (`4 @ 150.00`).
    - Depth ladder bid reduces `10 → 6`.
    - The aggressor's EXEC status is reflected; the resting row's remaining follows the book.
5. **Confirm out-of-order handling holds.** The visible book always matches the latest **BOOK**
   frame — never reconstructed from EXEC ordering. (Watch the console: a BOOK may land before
   its own EXEC; the ladder stays correct regardless.)
6. **Cancel the resting order** — Cancel the `BUY` row.
    - Row closes on `ORDER_CANCELLED` (`orderId == origClOrdId`).
    - Depth ladder empties that level (`bestBid → —`).
7. **Malformed input is blocked locally.** Try `150.255` (>2 decimals) or qty `0` — the order
   is rejected in the UI with a visible reason and **no frame is sent** (nothing appears in
   Open Orders, nothing on the server).
8. **Disconnect / reconnect.** Stop the backend, then restart it.
    - Badge transitions to **Reconnecting**, then back to **Live** when the server returns.
    - The ladder **clears** on disconnect and stays empty until you place the next order, which
      repopulates it (the known no-snapshot-on-connect limitation).

If every step behaves as above, the round-trip is proven end to end and Phase 5 is complete.

---

## Known limitations

- **No snapshot-on-connect.** A fresh client sees an empty ladder until the next order event
  (out of scope; §5.6).
- **No per-passive EXEC from the server.** The engine reports one fill event per trade, naming
  only the aggressor; the passive side's remaining quantity is never reported by the server.
  The blotter derives it client-side from `passiveOrderId` (P7-8), a documented local
  derivation, not a server value.
- **Timestamp anchor drift.** `timestamp` is epoch nanoseconds from a clock anchored once at
  startup, so it does not track wall-clock adjustments (NTP) made afterward; its absolute value
  can drift from true wall-clock over a long session. Relative ordering and inter-event deltas
  stay exact.
- **No reject feedback path.** Malformed input is guarded client-side; the server silently
  drops bad frames.
- **Ignition price is a dev-time integration.** Alpaca credentials are held server-side by
  the Vite dev-server proxy (read from `.env.local`, never `VITE_`-prefixed, so they never
  enter the client bundle), and the client fetches a same-origin relative path (`/alpaca/...`)
  so browser CORS does not arise. This works under `npm run dev` only: a static `vite build`
  has no dev server and therefore no proxy, so a real deployment would need a small backend
  proxy holding the credentials. `VITE_IGNITION_SYMBOL` and the header's `SYMBOL` both
  default to `ASML` and should be kept in step.
- **The chart is capped at the last 200 prints.** It plots the reducer tape, which is capped
  at `TAPE_CAP`, so it is not a whole-session chart. The side panel's high, low and trade
  count are *not* capped, being reducer accumulators, so on a long session the chart and the
  panel can legitimately disagree about the session's extremes. Closing that gap needs a
  dedicated price-history slice independent of the tape.
- **Session stats are connect-anchored.** High, low, trade count, volume and the first-trade
  anchor all accumulate from when this browser session connected, not from the venue's
  session open, so a mid-session connect undercounts them. A reconnect blip preserves them; a
  reload does not.
- **Single instrument, no session management, no auth, no persistence** — all out of scope for
  this practice build.

---

## Project layout

```
app/frontend/
├── index.html
├── package.json
├── tsconfig.json                 # app TS config
├── tsconfig.node.json            # Vite / node-side TS config
├── vite.config.ts                # dev server, Alpaca proxy, vitest config
├── .env                          # VITE_-prefixed only; safe to commit
├── .env.local                    # Alpaca credentials; never VITE_-prefixed, never committed
├── src/
│   ├── main.tsx                  # createRoot + BrowserRouter
│   ├── App.tsx                   # composition root: socket, header strip, navbar, routes
│   ├── format.ts                 # cents <-> dollars, qty, clock, midpoint (render/parse edge)
│   ├── depth.ts                  # pure depth ladder + depth curve maths
│   ├── priceSeries.ts            # pure price-series domain for the chart (P12)
│   ├── sessionStats.ts           # pure session-stat display strings for the panel (P13)
│   ├── pages/
│   │   ├── TradingPage.tsx       # depth, tape, ticket, blotter, FIX inspector
│   │   └── PriceChartPage.tsx    # chart region + side-panel region
│   ├── components/
│   │   ├── Header.tsx            # persistent instrument strip + deriveHeader
│   │   ├── Navbar.tsx            # Trading / Price Chart NavLinks
│   │   ├── ConnectionBadge.tsx
│   │   ├── DepthLadder.tsx
│   │   ├── DepthCurve.tsx
│   │   ├── TradeTape.tsx
│   │   ├── OrderEntry.tsx        # the ticket; mounted by both pages
│   │   ├── OpenOrders.tsx
│   │   ├── CancelTicket.tsx
│   │   ├── FixInspector.tsx
│   │   ├── PriceChart.tsx        # session price line (P12)
│   │   └── SessionStats.tsx      # four-row session block (P13)
│   ├── protocol/
│   │   ├── messages.ts           # the one place raw frames are narrowed
│   │   ├── encode.ts             # NEW / CANCEL builders + nextClOrdId
│   │   └── fix.ts                # FIX tag-value helpers for the inspector
│   ├── state/
│   │   ├── reducer.ts            # pure reducer, AppState, session aggregates
│   │   ├── useOrderBook.ts       # socket lifecycle, capped-backoff reconnect
│   │   └── useIgnitionPrice.ts   # one-shot Alpaca market-open fetch (P11)
│   ├── market/
│   │   ├── alpacaClient.ts
│   │   └── ignition.ts
│   └── styles/
│       └── terminal.css          # the whole theme: base layer + slate retheme layer
└── test/                         # flat; see the naming convention below
```

Two conventions to know before adding to this tree:

- **Pure logic sits beside the component that consumes it, not inside it.** `depth.ts`,
  `priceSeries.ts` and `sessionStats.ts` are plain modules at the `src/` root, each unit
  tested without a DOM, each paired with a component that owns only the markup and the
  scales. New derivations follow that split.
- **Tests are flat and named by kind.** `vite.config.ts` includes `test/**/*.test.{ts,tsx}`
  on a jsdom environment. Pure-logic suites are `X.test.ts`; component renders are
  `X.render.test.tsx` and wire `afterEach(cleanup)` explicitly, because the project
  deliberately does not enable Vitest globals.
