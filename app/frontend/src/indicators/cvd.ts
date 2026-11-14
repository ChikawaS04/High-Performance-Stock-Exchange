/**
 * Pure cumulative volume delta (P19-4, Phase 19 D5 as amended at P19-0). No React.
 *
 * Every bar records cvd, the session delta (buyer-initiated minus seller-initiated
 * shares) after its last fill (bars.ts, P19-1), so the line is simply bar.cvd per
 * visible slot. That makes it the true session CVD at any pan offset and after old
 * bars age out, with no running sum carried at render. "Session" is the browser
 * session since the tab loaded, as for bars and VWAP (P19-0 F2).
 *
 * A gap bucket has no bar and draws no point; the line holds flat across it, at the
 * last bar's value until the slot before the next bar, then moves to the next bar.
 *
 * Scale: the domain is [min(0, visible min), max(0, visible max)], so the zero
 * baseline is always in range but not forced to the middle, and a one-way session
 * uses the whole pane. An all-zero view takes [0, 1] so nothing divides by zero.
 */

export interface CvdSample {
    readonly slot: number;
    readonly cvd: number;
}

export interface CvdVertex {
    /** Slot index; the caller maps it to the slot centre. */
    readonly slot: number;
    readonly value: number;
}

/** The line's vertices for visible samples (oldest first), holding flat across gaps. */
export function cvdVertices(samples: readonly CvdSample[]): readonly CvdVertex[] {
    const out: CvdVertex[] = [];
    samples.forEach((s, i) => {
        const prev = i > 0 ? samples[i - 1] : null;
        if (prev !== null && s.slot - prev.slot > 1) out.push({ slot: s.slot - 1, value: prev.cvd });
        out.push({ slot: s.slot, value: s.cvd });
    });
    return out;
}

export interface CvdDomain {
    readonly lo: number;
    readonly hi: number;
}

/** [min(0, min), max(0, max)] over the visible samples; [0, 1] when that is empty or flat at zero. */
export function cvdDomain(samples: readonly CvdSample[]): CvdDomain {
    let lo = 0;
    let hi = 0;
    for (const s of samples) {
        if (s.cvd < lo) lo = s.cvd;
        if (s.cvd > hi) hi = s.cvd;
    }
    if (lo === hi) return { lo: 0, hi: 1 };
    return { lo, hi };
}

/** y of a value in a pane `height` tall at `top`: hi at the top edge, lo at the bottom. */
export function cvdY(value: number, domain: CvdDomain, top: number, height: number): number {
    return top + ((domain.hi - value) / (domain.hi - domain.lo)) * height;
}

/** A signed share count for the pane's axis labels: "+1,200", "-350", "0". */
export function formatDelta(n: number, formatQty: (q: number) => string): string {
    if (n > 0) return `+${formatQty(n)}`;
    return formatQty(n);
}
