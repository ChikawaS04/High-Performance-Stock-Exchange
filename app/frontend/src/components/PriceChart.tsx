/**
 * Session price chart (P12) — Bloomberg-style rework (P12-R).
 *
 * A line of the session's trade prints over time: time on x (oldest left, newest
 * right), price on y. Keeps the pure/edge split from the first build: the domain
 * and the axis maths live in priceSeries.ts (buildPriceSeries, priceAxis, timeAxis)
 * in integer units; this component owns only the viewBox, the scales, the SVG and
 * the panel chrome. Prices stay integer units; only the scales touch float, here at
 * the render edge.
 *
 * What the rework changes (DESIGN_SYSTEM.md §5/§8 card chrome, §9/§10/§11 controls):
 *
 *  - The panel is now a self-contained card: a --head header band over the plot
 *    body, so it reads as one family with the tape and blotter. The chart-page's
 *    loose "Price chart" heading is retired in favour of this band (the region
 *    becomes a transparent shell in terminal.css).
 *
 *  - Bloomberg "GP" plot: a right-hand price axis with round-level gridlines, a
 *    gradient area fill under the line, a dashed last-price level across the plot,
 *    and a last-price tag snapped to the right axis and coloured against the session
 *    open (up green / down red, §2). The time axis is sparse elapsed m:ss ticks:
 *    TapeEntry.timestamp is monotonic nanos with an arbitrary origin, so elapsed
 *    time is the honest label (formatElapsed), replacing the old fake HH:MM:SS.mmm.
 *
 *  - A padded price domain (priceAxis) so a flat / one-cent session reads flat
 *    rather than filling the panel. A reference never widens the domain (D5): the
 *    domain is the prints', and a reference out of range is simply not drawn.
 *
 *  - Header-band controls, all the terminal-wide shared standards (§11): a §9 caret
 *    dropdown ("View in trading", a placeholder wired open/close with no action yet)
 *    immediately left of the §11 settings gear. The gear opens the §10/§11 settings
 *    popup with a Display category: Background (White/Black), Line colour (swatch
 *    set), Area fill (On/Off) and Fill colour. View state is local and display-only.
 *
 * Guards unchanged: an empty tape plots a quiet "No trades yet" state; a single
 * print plots only the last-point marker and tag; a single distinct price
 * (cMin === cMax) is handled by priceAxis padding; a single timestamp (tMin === tMax)
 * centres on x. Test ids (price-chart, -line, -last, -ref-session, -ref-market) and
 * the component's props are preserved.
 */

import { useState } from "react";

import { buildPriceSeries, priceAxis, timeAxis } from "../priceSeries";
import { formatPrice, formatElapsed } from "../format";
import type { TapeEntry } from "../state/reducer";

const VIEW_W = 720;
const VIEW_H = 300;
const PAD_L = 10;
const PAD_R = 64; // room for the right-hand price axis labels and the last tag
const PAD_T = 14;
const PAD_B = 26; // room for the x time labels
const PLOT_W = VIEW_W - PAD_L - PAD_R;
const PLOT_H = VIEW_H - PAD_T - PAD_B;
const PLOT_LEFT = PAD_L;
const PLOT_RIGHT = PAD_L + PLOT_W;
const PLOT_TOP = PAD_T;
const PLOT_BOTTOM = PAD_T + PLOT_H;

type ChartBg = "black" | "white";
/** "line" matches the price line; any other value is a literal fill hex. */
type FillChoice = "line" | string;

interface Swatch {
    readonly name: string;
    readonly hex: string;
}

// A broad line-colour palette (display config, deliberately literal, not semantic).
const LINE_COLORS: readonly Swatch[] = [
    { name: "Amber", hex: "#e0a13c" },
    { name: "Green", hex: "#2bb673" },
    { name: "Red", hex: "#e05555" },
    { name: "Blue", hex: "#3d8bfd" },
    { name: "Cyan", hex: "#35c9d6" },
    { name: "Teal", hex: "#2bb39e" },
    { name: "Violet", hex: "#8a7bff" },
    { name: "Magenta", hex: "#d26edc" },
    { name: "Orange", hex: "#ff8c42" },
    { name: "Yellow", hex: "#e6c84a" },
    { name: "White", hex: "#e8eaed" },
    { name: "Slate", hex: "#9aa3b1" },
];

// A brief fill palette: match the line, or a few presets.
const FILL_CHOICES: readonly { readonly key: FillChoice; readonly name: string }[] = [
    { key: "line", name: "Match line" },
    { key: "#e0a13c", name: "Amber" },
    { key: "#2bb673", name: "Green" },
    { key: "#3d8bfd", name: "Blue" },
];

const UP = "#2bb673";
const DOWN = "#e05555";
const WARN = "#e0a13c"; // Alpaca market-open reference line

const GEAR_PATH =
    "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.82,11.69,4.82,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z";

interface Ink {
    readonly bg: string;
    readonly grid: string;
    readonly gridV: string;
    readonly axis: string;
    readonly ref: string;
}

function inkFor(bg: ChartBg): Ink {
    return bg === "white"
        ? {
              bg: "#ffffff",
              grid: "rgba(0,0,0,0.09)",
              gridV: "rgba(0,0,0,0.05)",
              axis: "#555555",
              ref: "rgba(0,0,0,0.45)",
          }
        : {
              bg: "#000000",
              grid: "rgba(255,255,255,0.07)",
              gridV: "rgba(255,255,255,0.04)",
              axis: "#9a9a9a",
              ref: "rgba(255,255,255,0.5)",
          };
}

export interface PriceChartProps {
    readonly tape: readonly TapeEntry[];
    /** Engine first-trade anchor (state.sessionOpenPx); drawn when > 0 and in domain. */
    readonly sessionOpenPx: number;
    /** Alpaca market open in units (P11); drawn only when inside the price domain. */
    readonly openPx?: number;
}

export function PriceChart({ tape, sessionOpenPx, openPx }: PriceChartProps) {
    const [bg, setBg] = useState<ChartBg>("black");
    const [lineColor, setLineColor] = useState<string>("#e0a13c");
    const [gradientOn, setGradientOn] = useState(true);
    const [fill, setFill] = useState<FillChoice>("line");
    const [settingsOpen, setSettingsOpen] = useState(false);
    const [displayOpen, setDisplayOpen] = useState(true);
    const [viewOpen, setViewOpen] = useState(false);

    const ink = inkFor(bg);
    const fillColor = fill === "line" ? lineColor : fill;

    const band = (
        <div className="price-chart__band">
            <span className="price-chart__title">Price chart</span>
            <div className="price-chart__controls">
                <div className="price-chart__view">
                    <button
                        type="button"
                        className="price-chart__viewcaret"
                        aria-haspopup="menu"
                        aria-expanded={viewOpen}
                        aria-label="Chart views"
                        data-testid="pc-view-caret"
                        onClick={() => {
                            setViewOpen((o) => !o);
                            setSettingsOpen(false);
                        }}
                    >
                        <span aria-hidden="true">{"▾"}</span>
                    </button>

                    {viewOpen && (
                        <>
                            <div
                                className="price-chart__view-backdrop"
                                data-testid="pc-view-backdrop"
                                onClick={() => setViewOpen(false)}
                            />
                            <div
                                className="price-chart__viewmenu"
                                role="menu"
                                aria-label="Chart views"
                                data-testid="pc-view-menu"
                            >
                                {/* Placeholder: no behaviour yet (wired in a later step). */}
                                <button
                                    type="button"
                                    role="menuitem"
                                    className="price-chart__viewopt"
                                    data-testid="pc-view-trading"
                                    onClick={() => setViewOpen(false)}
                                >
                                    View in trading
                                </button>
                            </div>
                        </>
                    )}
                </div>

                <button
                    type="button"
                    className="term-gear"
                    aria-label="Chart settings"
                    aria-haspopup="dialog"
                    aria-expanded={settingsOpen}
                    data-testid="pc-settings-gear"
                    onClick={() => {
                        setSettingsOpen((o) => !o);
                        setViewOpen(false);
                    }}
                >
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                        <path fill="currentColor" d={GEAR_PATH} />
                    </svg>
                </button>

                {settingsOpen && (
                    <>
                        <div
                            className="settings-pop__backdrop"
                            data-testid="pc-settings-backdrop"
                            onClick={() => setSettingsOpen(false)}
                        />
                        <div
                            className="settings-pop settings-pop--chart"
                            role="dialog"
                            aria-label="Chart settings"
                            data-testid="pc-settings"
                        >
                            <div className="settings-pop__head">
                                <span className="settings-pop__title">Settings</span>
                                <button
                                    type="button"
                                    className="settings-pop__x"
                                    aria-label="Close"
                                    data-testid="pc-settings-close"
                                    onClick={() => setSettingsOpen(false)}
                                >
                                    {"×"}
                                </button>
                            </div>

                            <div className="settings-pop__body">
                                <button
                                    type="button"
                                    className="settings-pop__cat"
                                    aria-expanded={displayOpen}
                                    aria-controls="pc-settings-display"
                                    onClick={() => setDisplayOpen((o) => !o)}
                                >
                                    <span>Display</span>
                                    <span className="settings-pop__caret" aria-hidden="true">{"▸"}</span>
                                </button>

                                {displayOpen && (
                                    <div className="settings-pop__panel" id="pc-settings-display">
                                        <div className="settings-pop__row">
                                            <span className="settings-pop__name">Background</span>
                                            <span className="settings-toggle" role="group" aria-label="Chart background">
                                                <button
                                                    type="button"
                                                    className="settings-toggle__opt settings-toggle__opt--sans"
                                                    aria-pressed={bg === "black"}
                                                    data-testid="pc-bg-black"
                                                    onClick={() => setBg("black")}
                                                >
                                                    Black
                                                </button>
                                                <button
                                                    type="button"
                                                    className="settings-toggle__opt settings-toggle__opt--sans"
                                                    aria-pressed={bg === "white"}
                                                    data-testid="pc-bg-white"
                                                    onClick={() => setBg("white")}
                                                >
                                                    White
                                                </button>
                                            </span>
                                        </div>

                                        <div className="settings-pop__row settings-pop__row--stack">
                                            <span className="settings-pop__name">Line color</span>
                                            <span className="settings-swatches" role="radiogroup" aria-label="Line color">
                                                {LINE_COLORS.map((c) => (
                                                    <button
                                                        key={c.hex}
                                                        type="button"
                                                        role="radio"
                                                        aria-checked={lineColor === c.hex}
                                                        aria-label={c.name}
                                                        title={c.name}
                                                        className={
                                                            lineColor === c.hex
                                                                ? "settings-swatch settings-swatch--on"
                                                                : "settings-swatch"
                                                        }
                                                        style={{ background: c.hex }}
                                                        data-testid={`pc-line-${c.hex}`}
                                                        onClick={() => setLineColor(c.hex)}
                                                    />
                                                ))}
                                            </span>
                                        </div>

                                        <div className="settings-pop__row">
                                            <span className="settings-pop__name">Area fill</span>
                                            <span className="settings-toggle" role="group" aria-label="Area fill">
                                                <button
                                                    type="button"
                                                    className="settings-toggle__opt settings-toggle__opt--sans"
                                                    aria-pressed={gradientOn}
                                                    data-testid="pc-grad-on"
                                                    onClick={() => setGradientOn(true)}
                                                >
                                                    On
                                                </button>
                                                <button
                                                    type="button"
                                                    className="settings-toggle__opt settings-toggle__opt--sans"
                                                    aria-pressed={!gradientOn}
                                                    data-testid="pc-grad-off"
                                                    onClick={() => setGradientOn(false)}
                                                >
                                                    Off
                                                </button>
                                            </span>
                                        </div>

                                        <div
                                            className={
                                                gradientOn
                                                    ? "settings-pop__row settings-pop__row--stack"
                                                    : "settings-pop__row settings-pop__row--stack settings-pop__row--disabled"
                                            }
                                        >
                                            <span className="settings-pop__name">Fill color</span>
                                            <span className="settings-swatches" role="radiogroup" aria-label="Fill color">
                                                {FILL_CHOICES.map((fc) => {
                                                    const sw = fc.key === "line" ? lineColor : fc.key;
                                                    const on = fill === fc.key;
                                                    return (
                                                        <button
                                                            key={fc.key}
                                                            type="button"
                                                            role="radio"
                                                            aria-checked={on}
                                                            aria-label={fc.name}
                                                            title={fc.name}
                                                            className={
                                                                on
                                                                    ? "settings-swatch settings-swatch--on"
                                                                    : "settings-swatch"
                                                            }
                                                            style={{ background: sw }}
                                                            data-testid={`pc-fill-${fc.key}`}
                                                            onClick={() => setFill(fc.key)}
                                                        />
                                                    );
                                                })}
                                            </span>
                                        </div>
                                    </div>
                                )}
                            </div>
                        </div>
                    </>
                )}
            </div>
        </div>
    );

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
            <div className={`price-chart price-chart--${bg}`}>
                {band}
                <div className="price-chart__plot">
                    <svg
                        className="price-chart__svg"
                        viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                        width="100%"
                        role="img"
                        aria-label="Session price chart (no trades)"
                        data-testid="price-chart"
                    >
                        <rect x={0} y={0} width={VIEW_W} height={VIEW_H} fill={ink.bg} />
                        <text
                            className="price-chart__empty"
                            x={VIEW_W / 2}
                            y={VIEW_H / 2}
                            textAnchor="middle"
                            fill={ink.axis}
                            fontSize="11"
                        >
                            No trades yet
                        </text>
                    </svg>
                </div>
            </div>
        );
    }

    const { points, tMin, tMax, cMin, cMax } = series;

    const yax = priceAxis(cMin, cMax);
    const tax = timeAxis(tMin, tMax);
    const { dMin, dMax } = yax;

    const xOf = (t: number): number =>
        tMax === tMin
            ? PLOT_LEFT + PLOT_W / 2
            : PLOT_LEFT + ((t - tMin) / (tMax - tMin)) * PLOT_W;

    const yOf = (px: number): number =>
        PLOT_BOTTOM - ((px - dMin) / (dMax - dMin)) * PLOT_H;

    const linePts = points.map((p) => `${xOf(p.t)},${yOf(p.pricePx)}`).join(" ");
    const last = points[points.length - 1];

    const areaPath =
        `M ${xOf(points[0].t)} ${yOf(points[0].pricePx)} ` +
        points.map((p) => `L ${xOf(p.t)} ${yOf(p.pricePx)}`).join(" ") +
        ` L ${xOf(last.t)} ${PLOT_BOTTOM} L ${xOf(points[0].t)} ${PLOT_BOTTOM} Z`;

    // A reference draws only when its price sits inside the plotted domain, so it
    // never widens the domain (D5).
    const inDomain = (px: number): boolean => px >= dMin && px <= dMax;
    const sessionRef =
        sessionOpenPx > 0 && inDomain(sessionOpenPx) ? sessionOpenPx : null;
    const marketRef =
        openPx !== undefined && openPx > 0 && inDomain(openPx) ? openPx : null;

    // Last-price tag colour: against the session open (up green / down red, §2);
    // neutral to the line colour when there is no open anchor yet.
    const up = sessionOpenPx > 0 ? last.pricePx >= sessionOpenPx : true;
    const tagColor = sessionOpenPx > 0 ? (up ? UP : DOWN) : lineColor;
    const tagY = Math.max(PLOT_TOP + 9, Math.min(PLOT_BOTTOM - 9, yOf(last.pricePx)));

    return (
        <div className={`price-chart price-chart--${bg}`}>
            {band}
            <div className="price-chart__plot">
                <svg
                    className="price-chart__svg"
                    viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                    width="100%"
                    role="img"
                    aria-label="Session price chart"
                    data-testid="price-chart"
                >
                    <defs>
                        <linearGradient id="pc-fill-grad" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor={fillColor} stopOpacity="0.22" />
                            <stop offset="100%" stopColor={fillColor} stopOpacity="0" />
                        </linearGradient>
                    </defs>

                    <rect x={0} y={0} width={VIEW_W} height={VIEW_H} fill={ink.bg} />

                    {/* Horizontal gridlines + right-hand price labels. */}
                    {yax.levels.map((v) =>
                        inDomain(v) ? (
                            <g key={`y-${v}`}>
                                <line
                                    x1={PLOT_LEFT}
                                    y1={yOf(v)}
                                    x2={PLOT_RIGHT}
                                    y2={yOf(v)}
                                    stroke={ink.grid}
                                    strokeWidth="1"
                                />
                                <text
                                    className="price-chart__y-label"
                                    x={PLOT_RIGHT + 6}
                                    y={yOf(v)}
                                    style={{ fill: ink.axis }}
                                    dominantBaseline="middle"
                                    textAnchor="start"
                                >
                                    {formatPrice(v)}
                                </text>
                            </g>
                        ) : null,
                    )}

                    {/* Vertical time ticks, labelled with elapsed m:ss. */}
                    {tax.ticks.map((t, i) => (
                        <g key={`t-${i}`}>
                            <line
                                x1={xOf(t)}
                                y1={PLOT_TOP}
                                x2={xOf(t)}
                                y2={PLOT_BOTTOM}
                                stroke={ink.gridV}
                                strokeWidth="1"
                            />
                            <text
                                className="price-chart__x-label"
                                x={xOf(t)}
                                y={PLOT_BOTTOM + 14}
                                style={{ fill: ink.axis }}
                                textAnchor={i === 0 ? "start" : "middle"}
                            >
                                {formatElapsed(t - tMin)}
                            </text>
                        </g>
                    ))}

                    {sessionRef !== null ? (
                        <line
                            className="price-chart__ref price-chart__ref--session"
                            data-testid="price-chart-ref-session"
                            x1={PLOT_LEFT}
                            y1={yOf(sessionRef)}
                            x2={PLOT_RIGHT}
                            y2={yOf(sessionRef)}
                            stroke={ink.ref}
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
                            stroke={WARN}
                            strokeWidth="1"
                            strokeDasharray="2 2"
                        />
                    ) : null}

                    {gradientOn && points.length >= 2 ? (
                        <path className="price-chart__area" d={areaPath} fill="url(#pc-fill-grad)" />
                    ) : null}

                    {/* Dashed last-price level across the plot (Bloomberg GP signature). */}
                    <line
                        className="price-chart__level"
                        x1={PLOT_LEFT}
                        y1={yOf(last.pricePx)}
                        x2={PLOT_RIGHT}
                        y2={yOf(last.pricePx)}
                        stroke={lineColor}
                        strokeOpacity="0.5"
                        strokeWidth="1"
                        strokeDasharray="2 3"
                    />

                    {points.length >= 2 ? (
                        <polyline
                            className="price-chart__line"
                            data-testid="price-chart-line"
                            points={linePts}
                            fill="none"
                            stroke={lineColor}
                            strokeWidth="1.6"
                            strokeLinejoin="round"
                            strokeLinecap="round"
                        />
                    ) : null}

                    <circle
                        className="price-chart__last"
                        data-testid="price-chart-last"
                        cx={xOf(last.t)}
                        cy={yOf(last.pricePx)}
                        r="3"
                        fill={lineColor}
                    />

                    {/* Last-price tag on the right axis. */}
                    <rect x={PLOT_RIGHT + 1} y={tagY - 9} width={58} height={18} rx="2" fill={tagColor} />
                    <text
                        className="price-chart__tag"
                        x={PLOT_RIGHT + 30}
                        y={tagY}
                        fill="#0b0b0b"
                        dominantBaseline="middle"
                        textAnchor="middle"
                    >
                        {formatPrice(last.pricePx)}
                    </text>
                </svg>
            </div>
        </div>
    );
}
