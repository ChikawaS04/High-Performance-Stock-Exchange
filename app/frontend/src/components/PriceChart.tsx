/**
 * Session price chart (P12).
 *
 * A line of the session's trade prints over time: time on x (oldest left, newest
 * right), price on y. Mirrors DepthCurve's pure/edge split: buildPriceSeries (in
 * priceSeries.ts) owns the domain points and bounds in integer units; this
 * component owns only the viewBox, the scales, and the SVG. Prices stay integer
 * units; only the scales touch float, here at the render edge.
 *
 * Reference lines (P12-3): a session-open line (state.sessionOpenPx, the same
 * anchor as the header Chg) drawn by default when set and in domain, and the
 * Alpaca market-open line (openPx, P11) drawn only when it falls inside the
 * price domain. A reference never widens the domain (D5): the domain is set by the
 * actual prints, and a reference out of range is simply not drawn. Guards: an
 * empty tape plots a quiet "No trades yet" state (no path, no NaN); a single print
 * plots only the last-point marker; a
 * single distinct price (cMin === cMax) centers the flat line on y; a single
 * timestamp (tMin === tMax) centers on x. Degenerate axes collapse their two edge
 * labels to one centered label so they do not overprint.
 */

import { buildPriceSeries } from "../priceSeries";
import { formatPrice, formatClockNanos } from "../format";
import type { TapeEntry } from "../state/reducer";

const VIEW_W = 480;
const VIEW_H = 200;
const PAD_L = 8;
const PAD_R = 52; // room for the y price labels at the right edge
const PAD_T = 12;
const PAD_B = 18; // room for the x time labels
const PLOT_W = VIEW_W - PAD_L - PAD_R;
const PLOT_H = VIEW_H - PAD_T - PAD_B;
const PLOT_LEFT = PAD_L;
const PLOT_RIGHT = PAD_L + PLOT_W;
const PLOT_TOP = PAD_T;
const PLOT_BOTTOM = PAD_T + PLOT_H;

export interface PriceChartProps {
    readonly tape: readonly TapeEntry[];
    /** Engine first-trade anchor (state.sessionOpenPx); drawn when > 0 and in domain. */
    readonly sessionOpenPx: number;
    /** Alpaca market open in units (P11); drawn only when inside the price domain. */
    readonly openPx?: number;
}

export function PriceChart({ tape, sessionOpenPx, openPx }: PriceChartProps) {
    const series = buildPriceSeries(tape);

    // Empty: a quiet frame, nothing to plot, no NaN. The null-bound checks also
    // narrow the domain values to numbers for the plotting path below.
    if (
        series.points.length === 0 ||
        series.tMin === null ||
        series.tMax === null ||
        series.cMin === null ||
        series.cMax === null
    ) {
        return (
            <div className="price-chart">
                <svg
                    className="price-chart__svg"
                    viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                    width="100%"
                    role="img"
                    aria-label="Session price chart (no trades)"
                    data-testid="price-chart"
                >
                    <text
                        className="price-chart__empty"
                        x={VIEW_W / 2}
                        y={VIEW_H / 2}
                        textAnchor="middle"
                        fill="var(--text-faint)"
                        fontSize="11"
                    >
                        No trades yet
                    </text>
                </svg>
            </div>
        );
    }

    const { points, tMin, tMax, cMin, cMax } = series;

    const xOf = (t: number): number =>
        tMax === tMin
            ? PLOT_LEFT + PLOT_W / 2
            : PLOT_LEFT + ((t - tMin) / (tMax - tMin)) * PLOT_W;

    const yOf = (px: number): number =>
        cMax === cMin
            ? PLOT_TOP + PLOT_H / 2
            : PLOT_BOTTOM - ((px - cMin) / (cMax - cMin)) * PLOT_H;

    const line = points.map((p) => `${xOf(p.t)},${yOf(p.pricePx)}`).join(" ");
    const last = points[points.length - 1];

    // A reference draws only when its price sits inside the plotted domain, so it
    // never widens the domain (D5). When cMin === cMax (all-equal prices), only a
    // reference exactly on that level is in domain and draws, centered with the line.
    const inDomain = (px: number): boolean => px >= cMin && px <= cMax;

    const sessionRef =
        sessionOpenPx > 0 && inDomain(sessionOpenPx) ? sessionOpenPx : null;
    const marketRef =
        openPx !== undefined && openPx > 0 && inDomain(openPx) ? openPx : null;

    return (
        <div className="price-chart">
            <svg
                className="price-chart__svg"
                viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                width="100%"
                role="img"
                aria-label="Session price chart"
                data-testid="price-chart"
            >
                {sessionRef !== null ? (
                    <line
                        className="price-chart__ref price-chart__ref--session"
                        data-testid="price-chart-ref-session"
                        x1={PLOT_LEFT}
                        y1={yOf(sessionRef)}
                        x2={PLOT_RIGHT}
                        y2={yOf(sessionRef)}
                        stroke="var(--line-strong)"
                        strokeWidth="1"
                        strokeDasharray="3 3"
                    />
                ) : null}

                {marketRef !== null ? (
                    <line
                        className="price-chart__ref price-chart__ref--market"
                        data-testid="price-chart-ref-market"
                        x1={PLOT_LEFT}
                        y1={yOf(marketRef)}
                        x2={PLOT_RIGHT}
                        y2={yOf(marketRef)}
                        stroke="var(--warn)"
                        strokeWidth="1"
                        strokeDasharray="2 2"
                    />
                ) : null}

                {points.length >= 2 ? (
                    <polyline
                        className="price-chart__line"
                        data-testid="price-chart-line"
                        points={line}
                        fill="none"
                        stroke="var(--live)"
                        strokeWidth="1.5"
                    />
                ) : null}

                <circle
                    className="price-chart__last"
                    data-testid="price-chart-last"
                    cx={xOf(last.t)}
                    cy={yOf(last.pricePx)}
                    r="2.5"
                    fill="var(--live)"
                />

                {/* y edge labels: high and low, or one centered value when flat. */}
                {cMax !== cMin ? (
                    <>
                        <text
                            className="price-chart__y-label"
                            x={PLOT_RIGHT + 4}
                            y={yOf(cMax)}
                            dominantBaseline="hanging"
                            textAnchor="start"
                        >
                            {formatPrice(cMax)}
                        </text>
                        <text
                            className="price-chart__y-label"
                            x={PLOT_RIGHT + 4}
                            y={yOf(cMin)}
                            dominantBaseline="alphabetic"
                            textAnchor="start"
                        >
                            {formatPrice(cMin)}
                        </text>
                    </>
                ) : (
                    <text
                        className="price-chart__y-label"
                        x={PLOT_RIGHT + 4}
                        y={yOf(cMin)}
                        dominantBaseline="middle"
                        textAnchor="start"
                    >
                        {formatPrice(cMin)}
                    </text>
                )}

                {/* x edge labels: oldest and newest, or one centered when instant. */}
                {tMax !== tMin ? (
                    <>
                        <text
                            className="price-chart__x-label"
                            x={PLOT_LEFT}
                            y={PLOT_BOTTOM + 12}
                            textAnchor="start"
                        >
                            {formatClockNanos(tMin, "ms")}
                        </text>
                        <text
                            className="price-chart__x-label"
                            x={PLOT_RIGHT}
                            y={PLOT_BOTTOM + 12}
                            textAnchor="end"
                        >
                            {formatClockNanos(tMax, "ms")}
                        </text>
                    </>
                ) : (
                    <text
                        className="price-chart__x-label"
                        x={PLOT_LEFT + PLOT_W / 2}
                        y={PLOT_BOTTOM + 12}
                        textAnchor="middle"
                    >
                        {formatClockNanos(tMin, "ms")}
                    </text>
                )}
            </svg>
        </div>
    );
}
