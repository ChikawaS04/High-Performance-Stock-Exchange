/**
 * Price formatting primitives for the OMS frontend.
 *
 * Prices are long units of $0.0001 everywhere (SRS §4, Phase 14): one unit is a
 * hundredth of a cent, 10,000 units to the dollar. Dollars appear only at the
 * render/parse edge, and every conversion here is string-based integer math — no
 * floating-point arithmetic ever touches a price, so a value like 150.25 can never
 * drift to 150.249999….
 */

/** Rendered in place of an absent price (the backend's -1L sentinel). */
export const EMPTY_PRICE = '—'

/**
 * Convert integer units of $0.0001 to a dollar string.
 *
 * A price on the one-cent tick (a multiple of 100 units) renders at exactly two
 * decimal places, as it always has. A sub-penny price — which only a midpoint
 * execution can produce — renders with up to four decimal places, trailing zeros
 * trimmed but never below two. This is how a real tape prints a sub-penny fill.
 *
 * Any negative value is the "no price" sentinel (the backend uses -1L for empty
 * book sides and NA fields) and renders as EMPTY_PRICE — never as a negative
 * dollar amount. Non-integer / non-finite input is likewise rejected to
 * EMPTY_PRICE, since prices on the wire are always whole units.
 *
 *   500     -> "0.05"
 *   1502000 -> "150.20"
 *   1502500 -> "150.25"
 *   1500000 -> "150.00"
 *   1000050 -> "100.005"
 *   1000025 -> "100.0025"
 *   -1      -> "—"
 */
export function formatPrice(px: number): string {
    if (!Number.isInteger(px) || px < 0) {
        return EMPTY_PRICE
    }
    // Zero-pad to at least five digits so there are always four fractional digits
    // to slice off the end: 500 -> "00500" -> "0" + "0500".
    const digits = String(px).padStart(5, '0')
    const whole = digits.slice(0, -4)
    const fraction = digits.slice(-4)
    // Trim trailing zeros, but never below two places: "0500" -> "05", "0050" ->
    // "005", "0025" -> "0025", "0000" -> "00".
    const trimmed = fraction.replace(/0+$/, '').padEnd(2, '0')
    return `${whole}.${trimmed}`
}

/**
 * Group a whole-number quantity with thousands separators (P9-Extras).
 *
 * For counts and sizes, not prices: session volume and the Filled / Rem header
 * counters. String-based, integer-only, and locale-independent by design, in the
 * same spirit as the price formatters here (no float, no toLocaleString), so
 * "1,000" is deterministic regardless of the runtime's locale and is unit-tested
 * without pinning one. A count of zero is a real value and renders "0", never the
 * EMPTY_PRICE sentinel. Input is expected to be a non-negative integer; a negative
 * or fractional value is truncated toward zero and still grouped rather than
 * throwing, so a stray call can never crash a render.
 *
 *   0       -> "0"
 *   999     -> "999"
 *   1000    -> "1,000"
 *   1234567 -> "1,234,567"
 */
export function formatQty(n: number): string {
    if (!Number.isFinite(n)) return String(n)
    const negative = n < 0
    const digits = String(Math.trunc(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ',')
    return negative ? `-${digits}` : digits
}

/**
 * Midpoint of two prices as a dollar string, integer math only (no float on the
 * price path). The mid is (bid + ask) / 2. Both tops lie on the one-cent tick, so
 * their sum is even and the mid is an exact integer number of units; when the
 * spread is an odd number of ticks the mid lands on a half-cent, which formatPrice
 * renders exactly (e.g. 1500000/1502500 gives "150.125"). EMPTY_PRICE unless both
 * sides are real, guarding the -1 empty-book sentinel the same way every other
 * formatter here does.
 *
 * Moved here in P7-4 (it began as a private helper in Header.tsx) so the header
 * instrument row and the depth-ladder divider share one definition rather than
 * each keeping its own copy of the midpoint logic.
 *
 *   1500000, 1505000 -> "150.25"
 *   1500000, 1502500 -> "150.125"
 *   -1,      1502500 -> "—"
 */
export function midpointLabel(bestBid: number, bestAsk: number): string {
    if (bestBid <= 0 || bestAsk <= 0) {
        return EMPTY_PRICE
    }
    return formatPrice((bestBid + bestAsk) / 2)
}

/**
 * Numeric midpoint of two prices, for POSITIONING only, never an order price.
 * midpointLabel above is the display string; a plotted mid marker needs a number
 * for its coordinate, so this is computed rather than parsed back out of that
 * string. The mid is (bid + ask) / 2. Both tops are on the one-cent tick, so their
 * sum is even and the mid is an EXACT integer number of units (a multiple of 50) —
 * no fractional coordinate as there was under the cent scale. Returns null unless both sides
 * are real, guarding the -1 sentinel exactly as midpointLabel does, so a one-sided
 * or empty book plots no marker.
 *
 *   1500000, 1505000 -> 1502500
 *   1500000, 1502500 -> 1501250
 *   -1,      1502500 -> null
 */
export function midpointPx(bestBid: number, bestAsk: number): number | null {
    if (bestBid <= 0 || bestAsk <= 0) {
        return null
    }
    return (bestBid + bestAsk) / 2
}

/**
 * Parse a dollar string to integer units of $0.0001, mirroring the backend
 * parsePrice policy (Phase 3): strictly positive, at most two decimal places, no
 * float. At most two decimals means every parsed price is a whole number of ticks,
 * i.e. a multiple of 100 units, so the ticket can never submit an off-tick price.
 *
 * Returns null on anything the backend would reject, so bad input is caught
 * locally before send (Phase 5 decision 6 — the server exposes no reject
 * feedback path):
 *
 *   "150"     -> 1500000
 *   "150.2"   -> 1502000
 *   "0.05"    -> 500
 *   "150.25"  -> 1502500
 *   "150.255" -> null   (> 2 decimals)
 *   "150."    -> null   (dangling dot)
 *   ".5"      -> null   (no integer part)
 *   "0"       -> null   (not > 0)
 *   "-1"      -> null   (sign not permitted by the grammar)
 *   ""        -> null
 *   "abc"     -> null
 */
export function parsePrice(input: string): number | null {
    const trimmed = input.trim()

    // Grammar: one or more digits, optionally a dot and one or two digits.
    // The integer part is mandatory (".5" is rejected, matching the backend's
    // empty-integer-part rejection); no sign, no exponent, no separators.
    const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(trimmed)
    if (match === null) {
        return null
    }

    const wholePart = match[1]
    const fractionPart = (match[2] ?? '').padEnd(2, '0')

    // String concatenation plus the two tick zeros, not "* 10000" — keeps the
    // whole path in integer land. "150" + "25" + "00" -> 1502500 units.
    const units = Number(wholePart + fractionPart + '00')

    // Reject zero / non-positive and any pathological non-safe-integer result.
    if (!Number.isSafeInteger(units) || units <= 0) {
        return null
    }

    return units
}

/** Clock display precision: milliseconds (default) or full nanoseconds. */
export type ClockPrecision = 'ms' | 'ns'

/**
 * Epoch-nanos to a wall-clock time string in local time.
 *
 * At 'ms' (default) this is HH:MM:SS.mmm, byte-identical to the P7-3 header
 * clock whose logic moved here in P7-6 so the header and the trade tape share
 * one formatter instead of duplicating it. At 'ns' it extends to nine fractional
 * digits (HH:MM:SS.nnnnnnnnn); the sub-second part is taken by exact BigInt
 * integer math from the value in state, and H:M:S comes from Date so local time
 * and midnight rollover are handled once.
 *
 * Precision limit, stated rather than hidden: a real epoch-nanos value (~1.75e18
 * in 2026) exceeds Number.MAX_SAFE_INTEGER, so it arrives already rounded by the
 * JSON-number transport (an IEEE-754 double, ULP ~256 ns near 2026). Milliseconds
 * are exact; digits below roughly a microsecond reflect that transport rounding,
 * not engine truth. True nanosecond fidelity would need a string-encoded wire
 * timestamp, which is out of P7-6 scope. EMPTY_PRICE when no frame has arrived.
 */
export function formatClockNanos(nanos: number, precision: ClockPrecision = 'ms'): string {
    if (nanos <= 0) return EMPTY_PRICE
    const ms = Math.floor(nanos / 1_000_000)
    const d = new Date(ms)
    const pad = (n: number, w = 2): string => String(n).padStart(w, '0')
    const hms = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
    if (precision === 'ms') {
        return `${hms}.${pad(d.getMilliseconds(), 3)}`
    }
    // Nanoseconds within the second, exact integer math on the value in state.
    const withinSecond = Number(BigInt(Math.trunc(nanos)) % 1_000_000_000n)
    return `${hms}.${pad(withinSecond, 9)}`
}
