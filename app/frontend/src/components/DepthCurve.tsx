/**
 * Cumulative depth curve (SRS §3.7 depth chart), P7-5.
 *
 * A step curve of cumulative depth per side on one shared price axis: bids to the
 * left, asks to the right, the spread showing as the gap between the innermost
 * points, and the mid marked inside that gap. Depth (y) uses a SHARED max across
 * both sides so imbalance reads truthfully, the same choice the ladder makes.
 *
 * Pure/edge split: buildDepthCurve (in depth.ts) owns the maths and emits domain
 * points (integer cents, integer cumulative qty); this component owns only the
 * viewBox scales, the SVG, and the click mapping. Cents stay integers; only the
 * scales and the half-cent mid position touch float, here at the render edge.
 *
 * Click-to-price: the price axis is partitioned into one hit band per real level
 * (boundaries at the midpoints between adjacent level x-positions), so a click
 * anywhere snaps to the nearest real level on either side and calls onPriceSelect
 * with that cent price. Price only: the ticket owns side and qty, and how a
 * clicked price populates the ticket is P7-7's job, so this step just delivers
 * the callback. Not wired into App yet; that lands with the P7-10 layout.
 *
 * Guards: empty and one-sided books plot no bogus geometry, no mid marker and no
 * NaN; a single distinct price collapses to one centered full-width band.
 */

import { buildDepthCurve, type CurvePoint } from "../depth";
import { midpointCents, midpointLabel } from "../format";
import type { BookState } from "../state/reducer";

const VIEW_W = 320;
const VIEW_H = 160;
const PAD_L = 8;
const PAD_R = 8;
const PAD_T = 12;
const PAD_B = 16;
const PLOT_W = VIEW_W - PAD_L - PAD_R;
const PLOT_H = VIEW_H - PAD_T - PAD_B;
const PLOT_LEFT = PAD_L;
const PLOT_RIGHT = PAD_L + PLOT_W;
const PLOT_TOP = PAD_T;
const PLOT_BOTTOM = PAD_T + PLOT_H;

export interface DepthCurveProps {
    readonly book: BookState;
    /**
     * Called with a cent price when a level's hit band is clicked, snapped to the
     * nearest real visible level. Price only: side and quantity stay with the
     * ticket, and wiring this into order entry is P7-7 (this step delivers the
     * callback). Optional so the panel is inert-but-valid before it is wired.
     */
    readonly onPriceSelect?: (priceCents: number) => void;
    /**
     * Optional cap on visible levels per side; omitted plots the whole book. Held
     * here, deliberately independent of the DepthLadder 8/10/14 selector so the two
     * panels never share depth state.
     */
    readonly depth?: number;
}

/**
 * Collapse step corners to one anchor per real level (its own cumulative total),
 * preserving touch-outward order. Same-price corners are adjacent, so a single
 * pass suffices. Anchors drive the shared price domain and the click bands.
 */
function levelAnchors(points: readonly CurvePoint[]): CurvePoint[] {
    const anchors: CurvePoint[] = [];
    for (const p of points) {
        const last = anchors[anchors.length - 1];
        if (last !== undefined && last.priceCents === p.priceCents) {
            if (p.cumQty > last.cumQty) {
                anchors[anchors.length - 1] = p;
            }
        } else {
            anchors.push(p);
        }
    }
    return anchors;
}

export function DepthCurve({ book, onPriceSelect, depth }: DepthCurveProps) {
    const model = buildDepthCurve(book, depth);
    const bidAnchors = levelAnchors(model.bids);
    const askAnchors = levelAnchors(model.asks);
    const anchors = [...bidAnchors, ...askAnchors];

    // Empty book: a quiet frame, nothing to plot, no mid, no bands.
    if (anchors.length === 0) {
        return (
            <div className="depth-curve">
                <svg
                    className="depth-curve__svg"
                    viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                    width="100%"
                    preserveAspectRatio="xMidYMax meet"
                    role="img"
                    aria-label="Cumulative depth chart (no depth)"
                    data-testid="depth-curve"
                >
                    <text
                        className="depth-curve__empty"
                        x={VIEW_W / 2}
                        y={VIEW_H / 2}
                        textAnchor="middle"
                        fill="var(--text-faint)"
                        fontSize="11"
                    >
                        No depth
                    </text>
                </svg>
            </div>
        );
    }

    // Shared price axis across both sides; shared depth max for imbalance truth.
    const prices = anchors.map((a) => a.priceCents);
    const xMin = Math.min(...prices);
    const xMax = Math.max(...prices);
    const maxCum = Math.max(...anchors.map((a) => a.cumQty));

    const xOf = (priceCents: number): number =>
        xMax === xMin
            ? PLOT_LEFT + PLOT_W / 2
            : PLOT_LEFT + ((priceCents - xMin) / (xMax - xMin)) * PLOT_W;

    const yOf = (cumQty: number): number =>
        maxCum <= 0 ? PLOT_BOTTOM : PLOT_BOTTOM - (cumQty / maxCum) * PLOT_H;

    const polyline = (points: readonly CurvePoint[]): string =>
        points.map((p) => `${xOf(p.priceCents)},${yOf(p.cumQty)}`).join(" ");

    // Filled area: the step line dropped to the baseline at both ends.
    const area = (points: readonly CurvePoint[]): string => {
        if (points.length === 0) {
            return "";
        }
        const first = points[0];
        const last = points[points.length - 1];
        return `${xOf(first.priceCents)},${PLOT_BOTTOM} ${polyline(points)} ${xOf(last.priceCents)},${PLOT_BOTTOM}`;
    };

    // Click bands: one per real level across BOTH sides, tiled by price with
    // boundaries at the midpoints between neighbours, so any x snaps to the nearest
    // real level. Sorted by price (== by x) for contiguous, non-overlapping bands.
    const sorted = [...anchors].sort((a, b) => a.priceCents - b.priceCents);
    const bands = sorted.map((anchor, i) => {
        const x = xOf(anchor.priceCents);
        const left = i === 0 ? PLOT_LEFT : (xOf(sorted[i - 1].priceCents) + x) / 2;
        const right =
            i === sorted.length - 1 ? PLOT_RIGHT : (x + xOf(sorted[i + 1].priceCents)) / 2;
        return { priceCents: anchor.priceCents, left, width: Math.max(0, right - left) };
    });

    const mid = midpointCents(book.bestBid, book.bestAsk);
    const midX = mid !== null ? xOf(mid) : null;

    return (
        <div className="depth-curve">
            <svg
                className="depth-curve__svg"
                viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                width="100%"
                preserveAspectRatio="xMidYMax meet"
                role="img"
                aria-label="Cumulative depth chart"
                data-testid="depth-curve"
            >
                {bidAnchors.length > 0 ? (
                    <polygon
                        className="depth-curve__area depth-curve__area--bid"
                        points={area(model.bids)}
                        fill="var(--bid-bar)"
                        stroke="none"
                    />
                ) : null}
                {askAnchors.length > 0 ? (
                    <polygon
                        className="depth-curve__area depth-curve__area--ask"
                        points={area(model.asks)}
                        fill="var(--ask-bar)"
                        stroke="none"
                    />
                ) : null}

                {bidAnchors.length > 0 ? (
                    <polyline
                        className="depth-curve__line depth-curve__line--bid"
                        points={polyline(model.bids)}
                        fill="none"
                        stroke="var(--bid)"
                        strokeWidth="1.5"
                    />
                ) : null}
                {askAnchors.length > 0 ? (
                    <polyline
                        className="depth-curve__line depth-curve__line--ask"
                        points={polyline(model.asks)}
                        fill="none"
                        stroke="var(--ask)"
                        strokeWidth="1.5"
                    />
                ) : null}

                {midX !== null ? (
                    <g className="depth-curve__mid" data-testid="depth-curve-mid">
                        <line
                            x1={midX}
                            y1={PLOT_TOP}
                            x2={midX}
                            y2={PLOT_BOTTOM}
                            stroke="var(--line-strong)"
                            strokeWidth="1"
                            strokeDasharray="3 3"
                        />
                        <text
                            className="depth-curve__mid-label"
                            x={midX}
                            y={PLOT_TOP - 3}
                            textAnchor="middle"
                            fill="var(--text-dim)"
                            fontSize="10"
                        >
                            {midpointLabel(book.bestBid, book.bestAsk)}
                        </text>
                    </g>
                ) : null}

                {/* Transparent click bands, drawn last so they receive the clicks. */}
                {bands.map((b) => (
                    <rect
                        key={b.priceCents}
                        className="depth-curve__hit"
                        data-testid={`curve-hit-${b.priceCents}`}
                        x={b.left}
                        y={PLOT_TOP}
                        width={b.width}
                        height={PLOT_H}
                        fill="transparent"
                        style={{ cursor: onPriceSelect ? "pointer" : "default" }}
                        onClick={() => onPriceSelect?.(b.priceCents)}
                    />
                ))}
            </svg>
        </div>
    );
}