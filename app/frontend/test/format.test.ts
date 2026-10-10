import { describe, it, expect } from 'vitest'
import {
    formatPrice,
    parsePrice,
    EMPTY_PRICE,
    formatClockNanos,
    formatQty,
    midpointPx,
    midpointLabel,
} from '../src/format'

describe('formatPrice', () => {
    it('formats sub-dollar values with a leading zero', () => {
        expect(formatPrice(500)).toBe('0.05')
        expect(formatPrice(100)).toBe('0.01')
        expect(formatPrice(9900)).toBe('0.99')
    })

    it('formats whole and fractional dollar values to two places', () => {
        expect(formatPrice(1500000)).toBe('150.00')
        expect(formatPrice(1502000)).toBe('150.20')
        expect(formatPrice(1502500)).toBe('150.25')
        expect(formatPrice(10000)).toBe('1.00')
    })

    it('renders the -1 sentinel (and any negative) as the empty marker', () => {
        expect(EMPTY_PRICE).toBe('—')
        expect(formatPrice(-1)).toBe(EMPTY_PRICE)
        expect(formatPrice(-999900)).toBe(EMPTY_PRICE)
    })

    it('rejects non-integer / non-finite units to the empty marker', () => {
        expect(formatPrice(1500050.5)).toBe(EMPTY_PRICE)
        expect(formatPrice(Number.NaN)).toBe(EMPTY_PRICE)
        expect(formatPrice(Number.POSITIVE_INFINITY)).toBe(EMPTY_PRICE)
    })

    it('round-trips through parsePrice for valid on-tick prices', () => {
        for (const px of [100, 500, 10000, 1502000, 1502500, 99999900]) {
            expect(parsePrice(formatPrice(px))).toBe(px)
        }
    })
})

describe('formatPrice — sub-penny and tick (P14-3)', () => {
    it('renders sub-penny midpoint prices up to four places, trailing zeros trimmed to two', () => {
        expect(formatPrice(1_000_050)).toBe('100.005')
        expect(formatPrice(1_000_025)).toBe('100.0025')
        expect(formatPrice(1_000_500)).toBe('100.05')
    })

    it('renders on-tick prices unchanged at exactly two places', () => {
        expect(formatPrice(1_502_500)).toBe('150.25')
        expect(formatPrice(1_500_000)).toBe('150.00')
        expect(formatPrice(1_000_000)).toBe('100.00')
    })
})

describe('formatQty', () => {
    it('leaves values below a thousand ungrouped', () => {
        expect(formatQty(0)).toBe('0')
        expect(formatQty(7)).toBe('7')
        expect(formatQty(999)).toBe('999')
    })

    it('groups thousands with commas', () => {
        expect(formatQty(1000)).toBe('1,000')
        expect(formatQty(12000)).toBe('12,000')
        expect(formatQty(1234567)).toBe('1,234,567')
        expect(formatQty(1000000)).toBe('1,000,000')
    })

    it('truncates a stray fractional value toward zero before grouping', () => {
        expect(formatQty(1000.9)).toBe('1,000')
    })
})

describe('midpointLabel', () => {
    it('renders an even-tick mid to two places', () => {
        expect(midpointLabel(1500000, 1505000)).toBe('150.25')
        expect(midpointLabel(300, 500)).toBe('0.04')
    })

    it('renders a sub-penny mid exactly, no float drift', () => {
        expect(midpointLabel(1500000, 1502500)).toBe('150.125')
        expect(midpointLabel(100, 200)).toBe('0.015')
    })

    it('blanks to the empty marker when either side is the -1 sentinel', () => {
        expect(midpointLabel(-1, 1502500)).toBe(EMPTY_PRICE)
        expect(midpointLabel(1500000, -1)).toBe(EMPTY_PRICE)
        expect(midpointLabel(-1, -1)).toBe(EMPTY_PRICE)
    })
})

describe('midpointPx', () => {
    it('returns the exact integer mid for an even-tick book', () => {
        expect(midpointPx(1500000, 1505000)).toBe(1502500)
        expect(midpointPx(300, 500)).toBe(400)
    })

    it('returns the exact integer mid for an odd-cent-spread book (no fractional coordinate)', () => {
        expect(midpointPx(1500000, 1502500)).toBe(1501250)
        expect(midpointPx(100, 200)).toBe(150)
    })

    it('returns null when either side is the -1 sentinel', () => {
        expect(midpointPx(-1, 1502500)).toBeNull()
        expect(midpointPx(1500000, -1)).toBeNull()
        expect(midpointPx(-1, -1)).toBeNull()
    })
})

describe('formatClockNanos', () => {
    it('blanks when no frame has arrived', () => {
        expect(formatClockNanos(0)).toBe(EMPTY_PRICE)
        expect(formatClockNanos(-1)).toBe(EMPTY_PRICE)
    })

    it('renders HH:MM:SS.mmm shape at millisecond precision (default)', () => {
        // Real epoch magnitude exceeds Number.MAX_SAFE_INTEGER, so assert the shape,
        // exactly as header.test.ts does; milliseconds are exact and the value is real.
        expect(formatClockNanos(1_700_000_000_123_456_789)).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{3}$/)
    })

    it('renders nine fractional digits at nanosecond precision', () => {
        expect(formatClockNanos(1_700_000_000_123_456_789, 'ns')).toMatch(/^\d{2}:\d{2}:\d{2}\.\d{9}$/)
    })

    it('extracts sub-second digits with exact integer math (small exact values)', () => {
        // Below 2^53 the value is exact, so the fractional digits are asserted exactly.
        // The wall-clock H:M:S is timezone-dependent, so only the fraction is pinned.
        expect(formatClockNanos(7, 'ns')).toMatch(/\.000000007$/)
        expect(formatClockNanos(999_999, 'ns')).toMatch(/\.000999999$/)
        expect(formatClockNanos(1_000_000_000 + 123_456_789, 'ns')).toMatch(/\.123456789$/)
    })

    it('rolls the wall clock over local midnight', () => {
        // Anchor to a local Date so the assertion is timezone-independent. A +0.5ms
        // cushion keeps floor(nanos / 1e6) exact despite double rounding at this
        // magnitude, so the displayed second is deterministic.
        const before = new Date(2026, 0, 2, 23, 59, 59, 500).getTime()
        expect(formatClockNanos(before * 1_000_000 + 500_000)).toBe('23:59:59.500')
        expect(formatClockNanos((before + 1000) * 1_000_000 + 500_000)).toBe('00:00:00.500')
    })
})

describe('parsePrice', () => {
    it('parses integer dollars', () => {
        expect(parsePrice('150')).toBe(1500000)
        expect(parsePrice('1')).toBe(10000)
    })

    it('parses one- and two-decimal dollars via string math', () => {
        expect(parsePrice('150.2')).toBe(1502000)
        expect(parsePrice('150.25')).toBe(1502500)
        expect(parsePrice('0.05')).toBe(500)
        expect(parsePrice('0.01')).toBe(100)
        expect(parsePrice('150.00')).toBe(1500000)
    })

    it('trims surrounding whitespace', () => {
        expect(parsePrice('  150.25  ')).toBe(1502500)
    })

    it('rejects more than two decimal places', () => {
        expect(parsePrice('150.255')).toBeNull()
        expect(parsePrice('0.001')).toBeNull()
    })

    it('rejects a dangling dot or a missing integer part', () => {
        expect(parsePrice('150.')).toBeNull()
        expect(parsePrice('.5')).toBeNull()
        expect(parsePrice('.')).toBeNull()
    })

    it('rejects zero and non-positive values', () => {
        expect(parsePrice('0')).toBeNull()
        expect(parsePrice('0.00')).toBeNull()
        expect(parsePrice('-1')).toBeNull()
        expect(parsePrice('-150.25')).toBeNull()
    })

    it('rejects empty and non-numeric input', () => {
        expect(parsePrice('')).toBeNull()
        expect(parsePrice('   ')).toBeNull()
        expect(parsePrice('abc')).toBeNull()
        expect(parsePrice('12.3abc')).toBeNull()
        expect(parsePrice('1e3')).toBeNull()
        expect(parsePrice('1,000')).toBeNull()
    })
})

describe('formatPrice — midpoint peg tape print (P14-10)', () => {
    it('renders a sub-penny midpoint execution price through formatPrice', () => {
        // An odd-tick spread midpoints to a half-cent; a peg fill prints it on the tape.
        expect(formatPrice(1_000_050)).toBe('100.005')
        expect(formatPrice(1_501_250)).toBe('150.125')
    })
})
