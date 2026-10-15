/**
 * Cumulative depth curve (SRS §3.7 depth chart), P7-5, TWS-charcoal rework (2026-10).
 *
 * A step curve of cumulative depth per side on one shared price axis: bids to the
 * left, asks to the right, the spread showing as the gap between the innermost
 * points, and the mid marked inside that gap. Depth (y) uses a SHARED max across
 * both sides so imbalance reads truthfully, the same choice the ladder makes.
 *
 * Two variants, one geometry:
 *   - "compact" (the inline panel): a deliberately simple read — the two filled
 *     step curves, the dashed mid with its price pill, and the invisible click
 *     bands. No axes, walls, chips, spread or best-bid/ask: that detail is wasted
 *     in a small panel. Click-to-ticket still works.
 *   - "full" (the maximized overlay): the whole design-system "walls" chart — a
 *     cumulative-size axis down the left gutter, a price axis on the well floor,
 *     the mid pill + spread readout, best bid/ask inside the touch, and the
 *     largest resting level per side called out as a liquidity WALL (brighter
 *     column, bright edge, leader to a size/price chip).
 * Default is "full" so the overlay and the existing render tests (which assert the
 * mid marker and the per-level click bands) keep their behaviour.
 *
 * Pure/edge split: buildDepthCurve (in depth.ts) owns the maths and emits domain
 * points (integer units of $0.0001, integer cumulative qty); this component owns
 * only the viewBox scales, the SVG, the axis/wall derivations, and the click
 * mapping. Prices stay integers; only the scales and the mid position touch float,
 * here at the render edge. cumulate() is reused to recover each level's own qty
 * (the step model carries cumulative totals only) for wall detection.
 *
 * Click-to-price: the price axis is partitioned into one hit band per real level
 * (boundaries at the midpoints between adjacent level x-positions), so a click
 * anywhere snaps to the nearest real level on either side and calls onPriceSelect
 * with that price in units. Price only: the ticket owns side and qty.
 *
 * Guards: empty and one-sided books plot no bogus geometry, no mid marker and no
 * NaN; a single distinct price collapses to one centered full-width band; a wall
 * is only called out where a side has at least two levels.
 */

import { buildDepthCurve, cumulate, type CurvePoint, type CumLevel } from "../depth";
import { midpointPx, midpointLabel, formatPrice } from "../format";
import type { BookState } from "../state/reducer";

export type DepthCurveVariant = "compact" | "full";

// The viewBox is the sunken-well interior. The panel card and its "Depth Chart"
// header band are HTML chrome (TradingPage / terminal.css), not drawn here.
const VIEW_W = 720;
const VIEW_H = 320;
const TICK_TARGET = 4; // desired cumulative-axis gridline count (full only)
const CENT = 100; // one-cent tick, in units of $0.0001

export interface DepthCurveProps {
    readonly book: BookState;
    /**
     * Called with a price in units when a level's hit band is clicked, snapped to
     * the nearest real visible level. Price only: side and quantity stay with the
     * ticket. Optional so the panel is inert-but-valid before it is wired.
     */
    readonly onPriceSelect?: (pricePx: number) => void;
    /**
     * Optional cap on visible levels per side; omitted plots the whole book. Held
     * here, deliberately independent of the DepthLadder selector so the two panels
     * never share depth state.
     */
    readonly depth?: number;
    /** Visual density; see the file header. Defaults to the full walls chart. */
    readonly variant?: DepthCurveVariant;
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
        if (last !== undefined && last.pricePx === p.pricePx) {
            if (p.cumQty > last.cumQty) {
                anchors[anchors.length - 1] = p;
            }
        } else {
            anchors.push(p);
        }
    }
    return anchors;
}

/**
 * A "nice" axis step (1/2/5 x 10^k) that yields roughly `target` gridlines over
 * [0, maxVal]. Pure number helper for the cumulative-size axis only.
 */
function niceStep(maxVal: number, target: number): number {
    if (maxVal <= 0) {
        return 1;
    }
    const raw = maxVal / target;
    const mag = Math.pow(10, Math.floor(Math.log10(raw)));
    const norm = raw / mag;
    const unit = norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10;
    return unit * mag;
}

/** Compact whole-quantity label for the axis and wall chips: 6400 -> "6.4k". */
function compactQty(n: number): string {
    if (n >= 1_000_000) {
        const v = n / 1_000_000;
        return `${v >= 100 ? Math.round(v) : Number(v.toFixed(1))}M`;
    }
    if (n >= 1_000) {
        const v = n / 1_000;
        return `${v >= 100 ? Math.round(v) : Number(v.toFixed(1))}k`;
    }
    return String(n);
}

/** The largest single resting level on a side, or null below two levels. */
function wallOf(levels: readonly CumLevel[]): CumLevel | null {
    if (levels.length < 2) {
        return null;
    }
    return levels.reduce((best, lvl) => (lvl.qty > best.qty ? lvl : best), levels[0]);
}

export function DepthCurve({ book, onPriceSelect, depth, variant = "full" }: DepthCurveProps) {
    const full = variant === "full";

    // Plot rectangle inside the viewBox. The full chart reserves a left gutter for
    // the size axis and a top band for the chips + mid pill; the compact chart uses
    // almost the whole box, with only a thin top band for its mid pill.
    const PLOT_LEFT = full ? 34 : 8;
    const PLOT_RIGHT = full ? 712 : VIEW_W - 8;
    const PLOT_TOP = full ? 52 : 28;
    const PLOT_BOTTOM = full ? 300 : VIEW_H - 10;
    const PLOT_W = PLOT_RIGHT - PLOT_LEFT;
    const PLOT_H = PLOT_BOTTOM - PLOT_TOP;
    const PRICE_AXIS_Y = PLOT_BOTTOM + 13; // full only: price labels on the well floor

    const limit = depth ?? Number.POSITIVE_INFINITY;
    const model = buildDepthCurve(book, depth);
    // Per-level qty (for wall detection) comes from the same cumulation the model
    // uses; the step model itself carries cumulative totals only.
    const bidLevels = cumulate(book.bids.slice(0, limit));
    const askLevels = cumulate(book.asks.slice(0, limit));
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
                    preserveAspectRatio="xMidYMid meet"
                    role="img"
                    aria-label="Cumulative depth chart (no depth)"
                    data-testid="depth-curve"
                >
                    <text
                        className="depth-curve__empty"
                        x={VIEW_W / 2}
                        y={VIEW_H / 2}
                        textAnchor="middle"
                        fill="var(--faint)"
                        fontSize="12"
                    >
                        No depth
                    </text>
                </svg>
            </div>
        );
    }

    // Shared price axis across both sides; shared depth max for imbalance truth.
    const prices = anchors.map((a) => a.pricePx);
    const xMin = Math.min(...prices);
    const xMax = Math.max(...prices);
    const maxCum = Math.max(...anchors.map((a) => a.cumQty));
    const step = niceStep(maxCum, TICK_TARGET);
    const axisMax = maxCum > 0 ? Math.ceil(maxCum / step) * step : step;

    const xOf = (pricePx: number): number =>
        xMax === xMin
            ? PLOT_LEFT + PLOT_W / 2
            : PLOT_LEFT + ((pricePx - xMin) / (xMax - xMin)) * PLOT_W;

    const yOf = (cumQty: number): number =>
        axisMax <= 0 ? PLOT_BOTTOM : PLOT_BOTTOM - (cumQty / axisMax) * PLOT_H;

    const polyline = (points: readonly CurvePoint[]): string =>
        points.map((p) => `${xOf(p.pricePx)},${yOf(p.cumQty)}`).join(" ");

    // Filled area: the step line dropped to the baseline at both ends.
    const area = (points: readonly CurvePoint[]): string => {
        if (points.length === 0) {
            return "";
        }
        const first = points[0];
        const last = points[points.length - 1];
        return `${xOf(first.pricePx)},${PLOT_BOTTOM} ${polyline(points)} ${xOf(last.pricePx)},${PLOT_BOTTOM}`;
    };

    // Click bands: one per real level across BOTH sides, tiled by price with
    // boundaries at the midpoints between neighbours, so any x snaps to the nearest
    // real level. Sorted by price (== by x) for contiguous, non-overlapping bands.
    const sorted = [...anchors].sort((a, b) => a.pricePx - b.pricePx);
    const bands = sorted.map((anchor, i) => {
        const x = xOf(anchor.pricePx);
        const left = i === 0 ? PLOT_LEFT : (xOf(sorted[i - 1].pricePx) + x) / 2;
        const right =
            i === sorted.length - 1 ? PLOT_RIGHT : (x + xOf(sorted[i + 1].pricePx)) / 2;
        return { pricePx: anchor.pricePx, left, width: Math.max(0, right - left) };
    });
    const bandByPrice = new Map(bands.map((b) => [b.pricePx, b]));

    // Cumulative-size gridlines / labels at the nice step (full only).
    const ticks: number[] = [];
    if (full && maxCum > 0) {
        for (let t = step; t <= axisMax + step * 1e-6; t += step) {
            ticks.push(t);
        }
    }

    // Price-axis ticks (full only): interior quarter positions snapped to the cent
    // tick for a clean label; the centre one is emphasised.
    const priceAt = (x: number): number =>
        xMin + ((x - PLOT_LEFT) / PLOT_W) * (xMax - xMin);
    const roundCent = (units: number): number => Math.round(units / CENT) * CENT;
    const priceTicks = !full
        ? []
        : xMax === xMin
          ? [{ x: PLOT_LEFT + PLOT_W / 2, px: xMin, center: true }]
          : [0.2, 0.5, 0.8].map((f) => {
                const x = PLOT_LEFT + PLOT_W * f;
                return { x, px: roundCent(priceAt(x)), center: f === 0.5 };
            });

    const mid = midpointPx(book.bestBid, book.bestAsk);
    const midX = mid !== null ? xOf(mid) : null;
    const twoSided = book.bestBid > 0 && book.bestAsk > 0;
    const spreadUnits = twoSided ? book.bestAsk - book.bestBid : 0;

    // Wall call-outs (full only): the largest single level per side (>= 2 levels).
    const bidWall = full ? wallOf(bidLevels) : null;
    const askWall = full ? wallOf(askLevels) : null;
    const wallMark = (wall: CumLevel | null) => {
        if (wall === null) {
            return null;
        }
        const band = bandByPrice.get(wall.pricePx);
        if (band === undefined) {
            return null;
        }
        return {
            band,
            edgeX: xOf(wall.pricePx),
            edgeTop: yOf(wall.cumQty),
            qty: wall.qty,
            pricePx: wall.pricePx,
        };
    };
    const bidMark = wallMark(bidWall);
    const askMark = wallMark(askWall);

    const chip = (
        mark: ReturnType<typeof wallMark>,
        label: string,
        colour: string,
        x: number,
        anchor: "start" | "end",
        leaderFromX: number,
    ) => {
        if (mark === null) {
            return null;
        }
        const textX = anchor === "start" ? x + 8 : x + 100;
        return (
            <g>
                <line
                    x1={leaderFromX}
                    y1={34}
                    x2={mark.edgeX}
                    y2={mark.edgeTop}
                    stroke="var(--faint)"
                    strokeWidth="1"
                    strokeDasharray="2 2"
                />
                <rect
                    x={x}
                    y={8}
                    width={108}
                    height={26}
                    rx={3}
                    fill="var(--well)"
                    stroke="var(--hair)"
                    strokeWidth="1"
                />
                <text
                    x={textX}
                    y={20}
                    textAnchor={anchor}
                    fill={colour}
                    fontSize="7.5"
                    fontWeight="600"
                    letterSpacing="0.6"
                >
                    {label}
                </text>
                <text
                    x={textX}
                    y={31}
                    textAnchor={anchor}
                    className="n"
                    fill="var(--text)"
                    fontSize="8.5"
                >
                    {compactQty(mark.qty)} @ {formatPrice(mark.pricePx)}
                </text>
            </g>
        );
    };

    const midTop = full ? 30 : PLOT_TOP - 4;

    return (
        <div className="depth-curve">
            <svg
                className="depth-curve__svg"
                viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                width="100%"
                preserveAspectRatio="xMidYMid meet"
                role="img"
                aria-label="Cumulative depth chart"
                data-testid="depth-curve"
            >
                {/* cumulative-size gridlines + labels (full) */}
                {ticks.map((t) => (
                    <line
                        key={`grid-${t}`}
                        x1={PLOT_LEFT}
                        y1={yOf(t)}
                        x2={PLOT_RIGHT}
                        y2={yOf(t)}
                        stroke="var(--hair)"
                        strokeWidth="1"
                        opacity="0.4"
                    />
                ))}
                {full ? (
                    <g className="n" fill="var(--text-dim)" fontSize="9" textAnchor="end">
                        {ticks.map((t) => (
                            <text key={`ylab-${t}`} x={PLOT_LEFT - 6} y={yOf(t) + 3}>
                                {compactQty(t)}
                            </text>
                        ))}
                        <text x={PLOT_LEFT - 6} y={PLOT_BOTTOM + 3}>
                            0
                        </text>
                    </g>
                ) : null}

                {/* wall highlight columns, under the areas (full) */}
                {bidMark !== null ? (
                    <rect
                        x={bidMark.band.left}
                        y={PLOT_TOP}
                        width={bidMark.band.width}
                        height={PLOT_H}
                        fill="var(--quote-bid-wash)"
                    />
                ) : null}
                {askMark !== null ? (
                    <rect
                        x={askMark.band.left}
                        y={PLOT_TOP}
                        width={askMark.band.width}
                        height={PLOT_H}
                        fill="var(--quote-ask-wash)"
                    />
                ) : null}

                {/* filled step areas */}
                {bidAnchors.length > 0 ? (
                    <polygon
                        className="depth-curve__area depth-curve__area--bid"
                        points={area(model.bids)}
                        fill="var(--quote-bid-wash)"
                        stroke="none"
                    />
                ) : null}
                {askAnchors.length > 0 ? (
                    <polygon
                        className="depth-curve__area depth-curve__area--ask"
                        points={area(model.asks)}
                        fill="var(--quote-ask-wash)"
                        stroke="none"
                    />
                ) : null}

                {/* step lines */}
                {bidAnchors.length > 0 ? (
                    <polyline
                        className="depth-curve__line depth-curve__line--bid"
                        points={polyline(model.bids)}
                        fill="none"
                        stroke="var(--quote-bid)"
                        strokeWidth={full ? 1 : 1.25}
                        strokeLinejoin="round"
                    />
                ) : null}
                {askAnchors.length > 0 ? (
                    <polyline
                        className="depth-curve__line depth-curve__line--ask"
                        points={polyline(model.asks)}
                        fill="none"
                        stroke="var(--quote-ask)"
                        strokeWidth={full ? 1 : 1.25}
                        strokeLinejoin="round"
                    />
                ) : null}

                {/* bright wall edges (full) */}
                {bidMark !== null ? (
                    <line
                        x1={bidMark.edgeX}
                        y1={bidMark.edgeTop}
                        x2={bidMark.edgeX}
                        y2={PLOT_BOTTOM}
                        stroke="var(--quote-bid)"
                        strokeWidth="1.5"
                    />
                ) : null}
                {askMark !== null ? (
                    <line
                        x1={askMark.edgeX}
                        y1={askMark.edgeTop}
                        x2={askMark.edgeX}
                        y2={PLOT_BOTTOM}
                        stroke="var(--quote-ask)"
                        strokeWidth="1.5"
                    />
                ) : null}

                {/* baseline */}
                <line
                    x1={PLOT_LEFT}
                    y1={PLOT_BOTTOM}
                    x2={PLOT_RIGHT}
                    y2={PLOT_BOTTOM}
                    stroke="var(--hair)"
                    strokeWidth="1"
                />

                {/* price axis (full) */}
                {full ? (
                    <g className="n" fontSize="9" textAnchor="middle">
                        {priceTicks.map((t) => (
                            <text
                                key={`xlab-${t.x}`}
                                x={t.x}
                                y={PRICE_AXIS_Y}
                                fill={t.center ? "var(--chart-ink, #ffffff)" : "var(--text-dim)"}
                            >
                                {formatPrice(t.px)}
                            </text>
                        ))}
                    </g>
                ) : null}

                {/* mid line, pill and (full) spread */}
                {midX !== null ? (
                    <g className="depth-curve__mid" data-testid="depth-curve-mid">
                        <line
                            x1={midX}
                            y1={midTop}
                            x2={midX}
                            y2={PLOT_BOTTOM}
                            stroke="var(--faint)"
                            strokeWidth="1"
                            strokeDasharray="3 3"
                            opacity="0.7"
                        />
                        <rect
                            x={midX - 38}
                            y={6}
                            width={76}
                            height={18}
                            rx={3}
                            fill="var(--well)"
                            stroke="var(--hair)"
                            strokeWidth="1"
                        />
                        <text
                            className="depth-curve__mid-label n"
                            x={midX}
                            y={19}
                            textAnchor="middle"
                            fill="var(--chart-ink, #ffffff)"
                            fontSize="9.5"
                        >
                            {midpointLabel(book.bestBid, book.bestAsk)}
                        </text>
                        {full && spreadUnits > 0 ? (
                            <text
                                className="n"
                                x={midX}
                                y={37}
                                textAnchor="middle"
                                fill="var(--text-dim)"
                                fontSize="8"
                            >
                                SPR {formatPrice(spreadUnits)} · {Math.round(spreadUnits / CENT)}t
                            </text>
                        ) : null}
                    </g>
                ) : null}

                {/* best bid / ask inside the touch (full) */}
                {full && midX !== null ? (
                    <g>
                        <text
                            x={midX - 8}
                            y={PLOT_BOTTOM - 28}
                            textAnchor="end"
                            fill="var(--faint)"
                            fontSize="7"
                            letterSpacing="1"
                        >
                            BID
                        </text>
                        <text
                            className="n"
                            x={midX - 8}
                            y={PLOT_BOTTOM - 16}
                            textAnchor="end"
                            fill="var(--quote-bid)"
                            fontSize="9"
                        >
                            {formatPrice(book.bestBid)}
                        </text>
                        <text
                            x={midX + 8}
                            y={PLOT_BOTTOM - 28}
                            textAnchor="start"
                            fill="var(--faint)"
                            fontSize="7"
                            letterSpacing="1"
                        >
                            ASK
                        </text>
                        <text
                            className="n"
                            x={midX + 8}
                            y={PLOT_BOTTOM - 16}
                            textAnchor="start"
                            fill="var(--quote-ask)"
                            fontSize="9"
                        >
                            {formatPrice(book.bestAsk)}
                        </text>
                    </g>
                ) : null}

                {/* wall chips + leaders (full) */}
                {chip(bidMark, "BID WALL", "var(--quote-bid)", 36, "start", 128)}
                {chip(
                    askMark,
                    "ASK WALL",
                    "var(--quote-ask)",
                    PLOT_RIGHT - 108 - 2,
                    "end",
                    PLOT_RIGHT - 108 - 2 - 8,
                )}

                {/* Transparent click bands, drawn last so they receive the clicks. */}
                {bands.map((b) => (
                    <rect
                        key={b.pricePx}
                        className="depth-curve__hit"
                        data-testid={`curve-hit-${b.pricePx}`}
                        x={b.left}
                        y={PLOT_TOP}
                        width={b.width}
                        height={PLOT_H}
                        fill="transparent"
                        style={{ cursor: onPriceSelect ? "pointer" : "default" }}
                        onClick={() => onPriceSelect?.(b.pricePx)}
                    />
                ))}
            </svg>
        </div>
    );
}
