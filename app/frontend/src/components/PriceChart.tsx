/**
 * Session price chart: P12 line, P12-R Bloomberg rework, P17 candles.
 *
 * Two chart types share one card (DESIGN_SYSTEM.md §5/§8 chrome, §11 controls,
 * §16 Charts):
 *
 *  - Candles (the default, P17 D5): clock-aligned OHLCV bars from the reducer bars
 *    slice at the chosen size. The pure candleView module picks the visible slots
 *    (newest bars that fit at the 5px minimum slot, capped at MAX_SLOT and
 *    right-aligned), the y domain from the visible highs and lows, and the time
 *    ticks. Up (close >= open) and down candles use --candle-up / --candle-down,
 *    never blue (§2). The dashed last-price level and the last-price tag take the
 *    open bar's direction colour.
 *  - Line: the trade-print line from the capped tape, as built in P12-R, with its
 *    area fill and the last-price tag in the line colour.
 *
 * Both modes read clock time (HH:MM:SS) on the x axis: EXEC timestamps are epoch
 * nanoseconds (P17-0 F1). Prices stay integer units; only the scales touch float,
 * here at the render edge. A reference line (session open, market open) and the
 * VWAP never widen the price domain; a reference outside it is not drawn and the
 * VWAP is clipped to the price area with its tag pinned to the axis edge.
 *
 * Indicators (P17 D6) come from the registry: VWAP is an overlay in both modes;
 * volume is a lower pane in candle mode, laid out by layoutPanes under the surface's
 * pane cap. The chart settings (type, bar size, indicators) are per surface: the
 * Charts page and the floating window each keep their own (useChartSettings). The
 * display settings (background, line colour, area fill, fill colour) stay shared.
 * The settings popup lives in ChartSettingsPopup.
 *
 * Crosshair (P17 D11): on pointer move over the plot, dashed hairlines in the
 * ground-aware dim ink, a price tag on the right axis at the pointer, and a time tag
 * on the x axis, snapped to the hovered slot centre (candles) or the nearest print
 * (line). A readout strip at the top left of the price area always reads something:
 * the hovered bar or print, else the newest. Pointer state is local and never
 * persisted. The plot never starts a floating-window drag; only the band does (§14).
 *
 * Guards: no data plots a quiet "No trades yet" state; a single print plots only
 * the last-point marker and tag; a flat series is handled by priceAxis padding; a
 * single timestamp centres on x. Test ids (price-chart, -line, -last, -ref-session,
 * -ref-market, -cross) are preserved.
 */

import { useId, useMemo, useRef, useState } from "react";
import type { PointerEvent as ReactPointerEvent } from "react";

import { SETTING_KEYS, isBoolean, oneOf } from "../settings/persist";
import { usePersistentSetting } from "../settings/usePersistentSetting";
import { useBoxSize } from "../useBoxSize";
import { buildPriceSeries, priceAxis, timeAxis } from "../priceSeries";
import { formatClockNanos, formatPrice, formatQty } from "../format";
import { barSizeNs, bucketStart, seriesBars } from "../bars";
import type { Bar, BarSet } from "../bars";
import {
    bodyWidth,
    buildCandleView,
    candleTimeTicks,
    hitSlot,
    lineVwapSteps,
    nearestIndex,
    placeVwapTag,
    priceAt,
    slotCentre,
} from "../candleView";
import { useChartSettings } from "../chartSettings";
import type { ChartSurface } from "../chartSettings";
import { INDICATORS, drawableIds } from "../indicators/registry";
import type { IndicatorDescriptor } from "../indicators/registry";
import { layoutPanes } from "../indicators/layoutPanes";
import type { PaneLayout } from "../indicators/layoutPanes";
import type { TapeEntry } from "../state/reducer";
import { ChartSettingsPopup, FILL_CHOICES, LINE_COLORS } from "./ChartSettingsPopup";
import type { ChartBg, FillChoice } from "./ChartSettingsPopup";

/** The page chart's fixed drawing size; fit mode lays out at the measured box instead. */
const NOMINAL_W = 720;
const NOMINAL_H = 300;
const PAD_L = 10;
const PAD_R = 64; // room for the right-hand price axis labels and the last tag
const PAD_T = 14;
const PAD_B = 26; // room for the x time labels
const TAG_H = 18;
const TAG_W = 58;
const FIVE_S = barSizeNs("5s");

export interface PriceChartLayout {
    readonly VIEW_W: number;
    readonly VIEW_H: number;
    readonly PLOT_W: number;
    readonly PLOT_H: number;
    readonly PLOT_LEFT: number;
    readonly PLOT_RIGHT: number;
    readonly PLOT_TOP: number;
    readonly PLOT_BOTTOM: number;
    /** Gridline targets for the price and time axes, scaled to the plot. */
    readonly yTarget: number;
    readonly xTarget: number;
}

/**
 * The plot rectangle and axis densities for a drawing of `w` x `h` units. At the
 * nominal 720 x 300 this is exactly the pre-resize layout (6 price levels, 5 time
 * ticks); a larger floating window gets more gridlines, a smaller one fewer.
 */
export function priceChartLayout(w: number, h: number): PriceChartLayout {
    const VIEW_W = Math.max(w, PAD_L + PAD_R + 40);
    const VIEW_H = Math.max(h, PAD_T + PAD_B + 40);
    const PLOT_W = VIEW_W - PAD_L - PAD_R;
    const PLOT_H = VIEW_H - PAD_T - PAD_B;
    return {
        VIEW_W,
        VIEW_H,
        PLOT_W,
        PLOT_H,
        PLOT_LEFT: PAD_L,
        PLOT_RIGHT: PAD_L + PLOT_W,
        PLOT_TOP: PAD_T,
        PLOT_BOTTOM: PAD_T + PLOT_H,
        yTarget: Math.min(12, Math.max(3, Math.round(PLOT_H / 43))),
        xTarget: Math.min(12, Math.max(2, Math.round(PLOT_W / 130))),
    };
}

const WARN = "#e0a13c"; // Alpaca market-open reference line

const isChartBg = oneOf<ChartBg>(["black", "white"]);
const isLineColor = oneOf<string>(LINE_COLORS.map((c) => c.hex));
const isFillChoice = oneOf<FillChoice>(FILL_CHOICES.map((f) => f.key));

const GEAR_PATH =
    "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.82,11.69,4.82,12s0.02,0.64,0.07,0.94l-2.03,1.58c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z";

/**
 * The chart's ground-aware ink. The chart offers a black and a white ground, so its
 * neutrals are chosen per ground here rather than read from the charcoal tokens
 * (there is no global --dim, §2). dim and text serve the crosshair and readout.
 */
interface Ink {
    readonly bg: string;
    readonly grid: string;
    readonly gridV: string;
    readonly axis: string;
    readonly ref: string;
    readonly sep: string;
    readonly dim: string;
    readonly text: string;
}

function inkFor(bg: ChartBg): Ink {
    return bg === "white"
        ? {
              bg: "#ffffff",
              grid: "rgba(0,0,0,0.15)",
              gridV: "rgba(0,0,0,0.12)",
              axis: "#222222",
              ref: "rgba(0,0,0,0.45)",
              sep: "rgba(0,0,0,0.25)",
              dim: "#6b6b6b",
              text: "#111111",
          }
        : {
              bg: "#000000",
              grid: "rgba(255,255,255,0.15)",
              gridV: "rgba(255,255,255,0.12)",
              axis: "#ffffff",
              ref: "rgba(255,255,255,0.5)",
              sep: "rgba(255,255,255,0.25)",
              dim: "#8f8f8f",
              text: "#d8d8d8",
          };
}

/** Legible ink (near-black or white) for text on a coloured fill, by luminance. */
function inkOn(hex: string): string {
    const h = hex.replace("#", "");
    const r = parseInt(h.slice(0, 2), 16);
    const g = parseInt(h.slice(2, 4), 16);
    const b = parseInt(h.slice(4, 6), 16);
    const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return lum > 0.6 ? "#0b0b0b" : "#ffffff";
}

const dirOf = (bar: Bar): "up" | "down" => (bar.close >= bar.open ? "up" : "down");

/** A text anchor that keeps an x-axis label inside the plot near either edge. */
function labelAnchor(x: number, left: number, right: number): "start" | "middle" | "end" {
    if (x - left < 26) return "start";
    if (right - x < 26) return "end";
    return "middle";
}

interface PlotPoint {
    readonly t: number;
    readonly px: number;
    readonly qty: number;
    readonly x: number;
    readonly y: number;
}

export interface PriceChartProps {
    readonly tape: readonly TapeEntry[];
    /** The reducer bars slice (state.bars): four clock-aligned series. */
    readonly bars: BarSet;
    /** Engine first-trade anchor (state.sessionOpenPx); drawn when > 0 and in domain. */
    readonly sessionOpenPx: number;
    /** Alpaca market open in units (P11); drawn only when inside the price domain. */
    readonly openPx?: number;
    /** When set, the view dropdown shows "View in trading" and calls this. */
    readonly onViewInTrading?: () => void;
    /** When set (floating overlay), the band shows a close button that calls this. */
    readonly onClose?: () => void;
    /**
     * Fit mode (the resizable floating window): the chart lays out at its measured
     * box in CSS pixels, so axes, gridlines, candles and tags reflow to the window
     * rather than a fixed drawing being scaled.
     */
    readonly fit?: boolean;
    /** Which surface's chart settings to use (P17 D5): the Charts page or the floating window. */
    readonly surface?: ChartSurface;
    /** Indicator registry; a test seam for the pane cap (P17-0 F13). */
    readonly registry?: readonly IndicatorDescriptor[];
}

interface Hover {
    readonly sx: number;
    readonly sy: number;
}

export function PriceChart({
    tape,
    bars,
    sessionOpenPx,
    openPx,
    onViewInTrading,
    onClose,
    fit = false,
    surface = "page",
    registry = INDICATORS,
}: PriceChartProps) {
    // Display settings persist across page switches and restarts, shared by the
    // chart page and the floating overlay (UI polish D10).
    const [bg, setBg] = usePersistentSetting<ChartBg>(SETTING_KEYS.priceChartBackground, "black", isChartBg);
    const [lineColor, setLineColor] = usePersistentSetting<string>(
        SETTING_KEYS.priceChartLineColor,
        "#ffb01f",
        isLineColor,
    );
    const [gradientOn, setGradientOn] = usePersistentSetting<boolean>(
        SETTING_KEYS.priceChartGradient,
        true,
        isBoolean,
    );
    const [fill, setFill] = usePersistentSetting<FillChoice>(SETTING_KEYS.priceChartFill, "line", isFillChoice);
    // Chart settings are per surface (P17 D5).
    const settings = useChartSettings(surface, registry);
    const { chartType, barSize, indicators } = settings;

    const [settingsOpen, setSettingsOpen] = useState(false);
    const [viewOpen, setViewOpen] = useState(false);
    const [hover, setHover] = useState<Hover | null>(null);
    const svgRef = useRef<SVGSVGElement>(null);
    const clipId = `pc-clip-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
    const [plotEl, setPlotEl] = useState<HTMLDivElement | null>(null);
    const box = useBoxSize(plotEl, fit);
    const measured = fit && box.w > 0 && box.h > 0;
    const { VIEW_W, VIEW_H, PLOT_W, PLOT_H, PLOT_LEFT, PLOT_RIGHT, PLOT_TOP, PLOT_BOTTOM, xTarget } =
        priceChartLayout(measured ? box.w : NOMINAL_W, measured ? box.h : NOMINAL_H);

    const ink = inkFor(bg);
    const fillColor = fill === "line" ? lineColor : fill;
    const candles = chartType === "candles";
    const drawable = drawableIds(surface, chartType, indicators, registry);
    const vwapOn = drawable.includes("vwap");

    // Lower panes only in candle mode (volume needs bars); price keeps >= 60%. Keyed
    // on the joined ids so a fresh drawable array each render keeps the memo stable.
    const drawableKey = drawable.join(",");
    const panes: PaneLayout = useMemo(
        () =>
            candles
                ? layoutPanes(surface, drawableKey === "" ? [] : drawableKey.split(","), PLOT_H, registry)
                : { price: { top: 0, height: PLOT_H }, panes: [] },
        [candles, surface, drawableKey, PLOT_H, registry],
    );
    const priceTop = PLOT_TOP + panes.price.top;
    const priceH = panes.price.height;
    const priceBottom = priceTop + priceH;
    const yTarget = Math.min(12, Math.max(3, Math.round(priceH / 43)));

    // ---- Line geometry (P12): depends only on the tape and the price rect. ----
    const line = useMemo(() => {
        if (candles) return null;
        const series = buildPriceSeries(tape);
        if (series.points.length === 0 || series.tMin === null || series.tMax === null || series.cMin === null || series.cMax === null) {
            return null;
        }
        const { points, tMin, tMax, cMin, cMax } = series;
        const yax = priceAxis(cMin, cMax, yTarget);
        const tax = timeAxis(tMin, tMax, xTarget);
        const { dMin, dMax } = yax;
        const xOf = (t: number): number =>
            tMax === tMin ? PLOT_LEFT + PLOT_W / 2 : PLOT_LEFT + ((t - tMin) / (tMax - tMin)) * PLOT_W;
        const yOf = (px: number): number => priceBottom - ((px - dMin) / (dMax - dMin)) * priceH;
        const n = tape.length;
        const pts: PlotPoint[] = points.map((p, i) => ({
            t: p.t,
            px: p.pricePx,
            qty: tape[n - 1 - i].quantity,
            x: xOf(p.t),
            y: yOf(p.pricePx),
        }));
        const last = pts[pts.length - 1];
        const linePts = pts.map((p) => `${p.x},${p.y}`).join(" ");
        const areaPath =
            `M ${pts[0].x} ${pts[0].y} ` +
            pts.map((p) => `L ${p.x} ${p.y}`).join(" ") +
            ` L ${last.x} ${priceBottom} L ${pts[0].x} ${priceBottom} Z`;
        return { tMin, tMax, dMin, dMax, levels: yax.levels, ticks: tax.ticks, pts, last, linePts, areaPath, xOf, yOf };
    }, [candles, tape, PLOT_LEFT, PLOT_W, priceBottom, priceH, yTarget, xTarget]);

    // ---- Candle geometry (P17-6): depends on the selected series and the plot. ----
    const sizeNs = barSizeNs(barSize);
    const series = bars[barSize];
    const candle = useMemo(() => {
        if (!candles) return null;
        const view = buildCandleView(series, PLOT_W, yTarget);
        if (view === null) return null;
        const { dMin, dMax } = view;
        const yOf = (px: number): number => priceBottom - ((px - dMin) / (dMax - dMin)) * priceH;
        const xOfSlot = (slot: number): number => PLOT_LEFT + slotCentre(view, slot);
        const bw = bodyWidth(view);
        const ticks = candleTimeTicks(view, sizeNs, xTarget).map((k) => ({ x: xOfSlot(k.slot) - view.slotW / 2, t: k.t }));
        const last = view.bars[view.bars.length - 1].bar;
        return { view, dMin, dMax, levels: view.levels, yOf, xOfSlot, bw, ticks, last };
    }, [candles, series, PLOT_W, PLOT_LEFT, priceBottom, priceH, yTarget, xTarget, sizeNs]);

    // Candle and volume marks are memoised so a pointer move re-renders only the
    // crosshair and the readout, not hundreds of rects.
    const candleMarks = useMemo(() => {
        if (candle === null) return null;
        const { view, yOf, xOfSlot, bw } = candle;
        return view.bars.map(({ slot, bar }) => {
            const cx = xOfSlot(slot);
            const yo = yOf(bar.open);
            const yc = yOf(bar.close);
            const top = Math.min(yo, yc);
            const h = Math.max(1, Math.abs(yo - yc));
            return (
                <g
                    key={bar.bucket}
                    className={`price-chart__candle price-chart__candle--${dirOf(bar)}`}
                    data-testid="price-chart-candle"
                >
                    <line className="price-chart__wick" data-testid="price-chart-wick" x1={cx} y1={yOf(bar.high)} x2={cx} y2={yOf(bar.low)} />
                    <rect className="price-chart__body" data-testid="price-chart-body" x={cx - bw / 2} y={top} width={bw} height={h} />
                </g>
            );
        });
    }, [candle]);

    const volumePane = panes.panes.find((p) => p.id === "volume") ?? null;
    const volumeMarks = useMemo(() => {
        if (candle === null || volumePane === null) return null;
        const { view, xOfSlot, bw } = candle;
        const top = PLOT_TOP + volumePane.top;
        const h = volumePane.height;
        const max = Math.max(1, view.maxVolume);
        return (
            <g className="price-chart__vol-pane" data-testid="price-chart-vol">
                <line x1={PLOT_LEFT} y1={top} x2={PLOT_RIGHT} y2={top} stroke={ink.sep} strokeWidth="1" />
                {view.bars.map(({ slot, bar }) => {
                    const vh = (bar.volume / max) * h;
                    return (
                        <rect
                            key={bar.bucket}
                            className={`price-chart__vol price-chart__vol--${dirOf(bar)}`}
                            data-testid="price-chart-vol-bar"
                            x={xOfSlot(slot) - bw / 2}
                            y={top + h - vh}
                            width={bw}
                            height={vh}
                        />
                    );
                })}
                <text
                    className="price-chart__y-label"
                    data-testid="price-chart-vol-max"
                    x={PLOT_RIGHT + 6}
                    y={top + 8}
                    style={{ fill: ink.axis }}
                    dominantBaseline="middle"
                    textAnchor="start"
                >
                    {formatQty(view.maxVolume)}
                </text>
            </g>
        );
    }, [candle, volumePane, PLOT_TOP, PLOT_LEFT, PLOT_RIGHT, ink.sep, ink.axis]);

    const onMove = (e: ReactPointerEvent<SVGSVGElement>): void => {
        const svg = svgRef.current;
        if (svg === null) return;
        const rect = svg.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return;
        const sx = ((e.clientX - rect.left) / rect.width) * VIEW_W;
        const sy = ((e.clientY - rect.top) / rect.height) * VIEW_H;
        if (sx < PLOT_LEFT || sx > PLOT_RIGHT || sy < PLOT_TOP || sy > PLOT_BOTTOM) {
            setHover(null);
            return;
        }
        setHover({ sx, sy });
    };
    const onLeave = (): void => setHover(null);

    const band = (
        <div className="price-chart__band">
            <span className="price-chart__title">Price chart</span>
            <div className="price-chart__controls">
                {onViewInTrading ? (
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
                            <span className="term-caret" aria-hidden="true">{"▾"}</span>
                        </button>

                        {viewOpen && (
                            <>
                                <div
                                    className="price-chart__view-backdrop"
                                    data-testid="pc-view-backdrop"
                                    onClick={() => setViewOpen(false)}
                                />
                                <div
                                    className="field-menu__menu field-menu__menu--anchored"
                                    role="menu"
                                    aria-label="Chart views"
                                    data-testid="pc-view-menu"
                                >
                                    {/* Navigates to the trading page and floats this chart there (App wires onViewInTrading). */}
                                    <button
                                        type="button"
                                        role="menuitem"
                                        className="field-menu__opt field-menu__opt--action"
                                        data-testid="pc-view-trading"
                                        onClick={() => {
                                            setViewOpen(false);
                                            onViewInTrading?.();
                                        }}
                                    >
                                        View in trading
                                    </button>
                                </div>
                            </>
                        )}
                    </div>
                ) : null}

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
                    <ChartSettingsPopup
                        surface={surface}
                        onClose={() => setSettingsOpen(false)}
                        bg={bg}
                        setBg={setBg}
                        lineColor={lineColor}
                        setLineColor={setLineColor}
                        gradientOn={gradientOn}
                        setGradientOn={setGradientOn}
                        fill={fill}
                        setFill={setFill}
                        chartType={chartType}
                        setChartType={settings.setChartType}
                        barSize={barSize}
                        setBarSize={settings.setBarSize}
                        indicators={indicators}
                        setIndicators={settings.setIndicators}
                        registry={registry}
                    />
                )}

                {onClose ? (
                    <button
                        type="button"
                        className="price-chart__close"
                        aria-label="Close floating chart"
                        data-testid="pc-close"
                        onClick={onClose}
                    >
                        ×
                    </button>
                ) : null}
            </div>
        </div>
    );

    const rootClass = `price-chart price-chart--${bg}${fit ? " price-chart--fit" : ""}`;
    const scene = candles ? candle : line;

    // Empty: a quiet frame, nothing to plot, no NaN.
    if (scene === null) {
        return (
            <div className={rootClass}>
                {band}
                <div className="price-chart__plot" ref={setPlotEl}>
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

    const { dMin, dMax, levels, yOf } = scene;
    const inDomain = (px: number): boolean => px >= dMin && px <= dMax;
    const sessionRef = sessionOpenPx > 0 && inDomain(sessionOpenPx) ? sessionOpenPx : null;
    const marketRef = openPx !== undefined && openPx > 0 && inDomain(openPx) ? openPx : null;

    // x-axis ticks: clock time, HH:MM:SS, in both modes.
    const xTicks: readonly { readonly x: number; readonly t: number }[] =
        candle !== null ? candle.ticks : line!.ticks.map((t) => ({ x: line!.xOf(t), t }));

    // Last price: the open bar's close (candles) or the newest print (line).
    const lastPx = candle !== null ? candle.last.close : line!.last.px;
    const lastY = yOf(lastPx);
    const lastDir = candle !== null ? dirOf(candle.last) : null;
    const tagY = Math.max(priceTop + TAG_H / 2, Math.min(priceBottom - TAG_H / 2, lastY));

    // VWAP overlay (P17-8): session-anchored, from the bars' recorded VWAP.
    const fiveS = bars["5s"];
    const vwapNow = fiveS.open !== null ? fiveS.open.vwap : -1;
    let vwapPts: string | null = null;
    if (vwapOn && vwapNow >= 0) {
        if (candle !== null) {
            const ps = candle.view.bars.filter((s) => s.bar.vwap >= 0).map((s) => `${candle.xOfSlot(s.slot)},${yOf(s.bar.vwap)}`);
            vwapPts = ps.length > 0 ? ps.join(" ") : null;
        } else if (line !== null) {
            const steps = lineVwapSteps(seriesBars(fiveS), FIVE_S, line.tMin, line.tMax);
            const ps: string[] = [];
            steps.forEach((p, i) => {
                const x = line.xOf(p.t);
                const y = yOf(p.vwap);
                if (i > 0) ps.push(`${x},${yOf(steps[i - 1].vwap)}`);
                ps.push(`${x},${y}`);
            });
            vwapPts = ps.length > 1 ? ps.join(" ") : null;
        }
    }
    const vwapTagY = vwapPts !== null ? placeVwapTag(tagY, yOf(vwapNow), priceTop, priceBottom, TAG_H) : null;

    // Crosshair (P17-10).
    let cross: { readonly x: number; readonly t: number; readonly py: number | null; readonly price: number | null } | null = null;
    let hoverBar: Bar | null = null;
    let hoverPrint: PlotPoint | null = null;
    if (hover !== null) {
        const price = priceAt(hover.sy, priceTop, priceH, dMin, dMax);
        const py = price !== null ? hover.sy : null;
        if (candle !== null) {
            const hit = hitSlot(candle.view, hover.sx - PLOT_LEFT);
            if (hit !== null) {
                hoverBar = hit.bar;
                cross = { x: candle.xOfSlot(hit.slot), t: bucketStart(hit.bucket, sizeNs), py, price };
            }
        } else if (line !== null) {
            const i = nearestIndex(line.pts.map((p) => p.x), hover.sx);
            if (i >= 0) {
                hoverPrint = line.pts[i];
                cross = { x: hoverPrint.x, t: hoverPrint.t, py, price };
            }
        }
    }

    // Readout strip: the hovered bar or print, else the newest, so it always reads something.
    type Part = readonly [key: string, value: string];
    let readout: readonly Part[];
    if (candle !== null) {
        const b = hoverBar ?? candle.last;
        const parts: Part[] = [
            ["O", formatPrice(b.open)],
            ["H", formatPrice(b.high)],
            ["L", formatPrice(b.low)],
            ["C", formatPrice(b.close)],
            ["V", formatQty(b.volume)],
        ];
        if (vwapOn && b.vwap >= 0) parts.push(["VWAP", formatPrice(b.vwap)]);
        readout = parts;
    } else {
        const p = hoverPrint ?? line!.last;
        readout = [
            ["", formatPrice(p.px)],
            ["x", formatQty(p.qty)],
            ["@", formatClockNanos(p.t, "s")],
        ];
    }

    const crossTagFill = ink.dim;
    const crossTagInk = inkOn(ink.dim);

    return (
        <div className={rootClass}>
            {band}
            <div className="price-chart__plot" ref={setPlotEl}>
                <svg
                    ref={svgRef}
                    className="price-chart__svg"
                    viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                    width="100%"
                    role="img"
                    aria-label="Session price chart"
                    data-testid="price-chart"
                    data-chart-type={chartType}
                    onPointerMove={onMove}
                    onPointerLeave={onLeave}
                >
                    <defs>
                        <linearGradient id="pc-fill-grad" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%" stopColor={fillColor} stopOpacity="0.22" />
                            <stop offset="100%" stopColor={fillColor} stopOpacity="0" />
                        </linearGradient>
                        <clipPath id={clipId}>
                            <rect x={PLOT_LEFT} y={priceTop} width={PLOT_W} height={priceH} />
                        </clipPath>
                    </defs>

                    <rect x={0} y={0} width={VIEW_W} height={VIEW_H} fill={ink.bg} />

                    {/* Horizontal gridlines + right-hand price labels. */}
                    {levels.map((v) =>
                        inDomain(v) ? (
                            <g key={`y-${v}`}>
                                <line x1={PLOT_LEFT} y1={yOf(v)} x2={PLOT_RIGHT} y2={yOf(v)} stroke={ink.grid} strokeWidth="1" />
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

                    {/* Vertical time ticks, labelled with clock time HH:MM:SS. */}
                    {xTicks.map(({ x, t }) => (
                        <g key={`t-${t}`}>
                            <line x1={x} y1={PLOT_TOP} x2={x} y2={PLOT_BOTTOM} stroke={ink.gridV} strokeWidth="1" />
                            <text
                                className="price-chart__x-label"
                                data-testid="price-chart-x-label"
                                x={x}
                                y={PLOT_BOTTOM + 14}
                                style={{ fill: ink.axis }}
                                textAnchor={labelAnchor(x, PLOT_LEFT, PLOT_RIGHT)}
                            >
                                {formatClockNanos(t, "s")}
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

                    {line !== null && gradientOn && line.pts.length >= 2 ? (
                        <path className="price-chart__area" d={line.areaPath} fill="url(#pc-fill-grad)" />
                    ) : null}

                    {/* Dashed last-price level across the plot (Bloomberg GP signature). */}
                    {lastDir !== null ? (
                        <line
                            className={`price-chart__level price-chart__level--${lastDir}`}
                            data-testid="price-chart-level"
                            x1={PLOT_LEFT}
                            y1={lastY}
                            x2={PLOT_RIGHT}
                            y2={lastY}
                        />
                    ) : (
                        <line
                            className="price-chart__level"
                            data-testid="price-chart-level"
                            x1={PLOT_LEFT}
                            y1={lastY}
                            x2={PLOT_RIGHT}
                            y2={lastY}
                            stroke={lineColor}
                            strokeOpacity="0.5"
                            strokeWidth="1"
                            strokeDasharray="2 3"
                        />
                    )}

                    {candleMarks}
                    {volumeMarks}

                    {line !== null && line.pts.length >= 2 ? (
                        <polyline
                            className="price-chart__line"
                            data-testid="price-chart-line"
                            points={line.linePts}
                            fill="none"
                            stroke={lineColor}
                            strokeWidth="1.6"
                            strokeLinejoin="round"
                            strokeLinecap="round"
                        />
                    ) : null}

                    {line !== null ? (
                        <circle
                            className="price-chart__last"
                            data-testid="price-chart-last"
                            cx={line.last.x}
                            cy={line.last.y}
                            r="3"
                            fill={lineColor}
                        />
                    ) : null}

                    {vwapPts !== null ? (
                        <polyline
                            className="price-chart__vwap"
                            data-testid="price-chart-vwap"
                            points={vwapPts}
                            clipPath={`url(#${clipId})`}
                        />
                    ) : null}

                    {/* Last-price tag on the right axis. */}
                    {lastDir !== null ? (
                        <g data-testid="price-chart-tag">
                            <rect
                                className={`price-chart__tagbg price-chart__tagbg--${lastDir}`}
                                x={PLOT_RIGHT + 1}
                                y={tagY - TAG_H / 2}
                                width={TAG_W}
                                height={TAG_H}
                                rx="2"
                            />
                            <text
                                className={`price-chart__tag price-chart__tag--${lastDir}`}
                                x={PLOT_RIGHT + 30}
                                y={tagY}
                                dominantBaseline="middle"
                                textAnchor="middle"
                            >
                                {formatPrice(lastPx)}
                            </text>
                        </g>
                    ) : (
                        <g data-testid="price-chart-tag">
                            <rect x={PLOT_RIGHT + 1} y={tagY - TAG_H / 2} width={TAG_W} height={TAG_H} rx="2" fill={lineColor} />
                            <text
                                className="price-chart__tag"
                                x={PLOT_RIGHT + 30}
                                y={tagY}
                                fill={inkOn(lineColor)}
                                dominantBaseline="middle"
                                textAnchor="middle"
                            >
                                {formatPrice(lastPx)}
                            </text>
                        </g>
                    )}

                    {vwapTagY !== null ? (
                        <g data-testid="price-chart-vwap-tag">
                            <rect
                                className="price-chart__tagbg price-chart__tagbg--vwap"
                                x={PLOT_RIGHT + 1}
                                y={vwapTagY - TAG_H / 2}
                                width={TAG_W}
                                height={TAG_H}
                                rx="2"
                            />
                            <text
                                className="price-chart__tag price-chart__tag--vwap"
                                x={PLOT_RIGHT + 30}
                                y={vwapTagY}
                                dominantBaseline="middle"
                                textAnchor="middle"
                            >
                                {formatPrice(vwapNow)}
                            </text>
                        </g>
                    ) : null}

                    {/* Readout strip, top left of the price area: keys dim, values in the text ink. */}
                    <text
                        className="price-chart__readout"
                        data-testid="price-chart-readout"
                        x={PLOT_LEFT + 4}
                        y={priceTop + 10}
                        dominantBaseline="middle"
                        pointerEvents="none"
                    >
                        {readout.map(([k, v], i) => (
                            <tspan key={`${k}-${i}`}>
                                {k !== "" ? (
                                    <tspan className="price-chart__readout-key" fill={ink.dim} dx={i === 0 ? 0 : 8}>
                                        {k}
                                    </tspan>
                                ) : null}
                                <tspan
                                    className="price-chart__readout-val"
                                    data-testid={`price-chart-readout-${k === "" ? "px" : k}`}
                                    fill={ink.text}
                                    dx={k === "" ? 0 : 3}
                                >
                                    {v}
                                </tspan>
                            </tspan>
                        ))}
                    </text>

                    {/* Crosshair (hover). Non-interactive so it never eats pointer moves. */}
                    {cross !== null ? (
                        <g className="price-chart__cross" data-testid="price-chart-cross" pointerEvents="none">
                            <line
                                data-testid="price-chart-cross-v"
                                x1={cross.x}
                                y1={PLOT_TOP}
                                x2={cross.x}
                                y2={PLOT_BOTTOM}
                                stroke={ink.dim}
                                strokeWidth="1"
                                strokeDasharray="2 2"
                            />
                            {cross.py !== null && cross.price !== null ? (
                                <>
                                    <line
                                        data-testid="price-chart-cross-h"
                                        x1={PLOT_LEFT}
                                        y1={cross.py}
                                        x2={PLOT_RIGHT}
                                        y2={cross.py}
                                        stroke={ink.dim}
                                        strokeWidth="1"
                                        strokeDasharray="2 2"
                                    />
                                    <rect
                                        x={PLOT_RIGHT + 1}
                                        y={cross.py - TAG_H / 2}
                                        width={TAG_W}
                                        height={TAG_H}
                                        rx="2"
                                        fill={crossTagFill}
                                    />
                                    <text
                                        className="price-chart__tag"
                                        data-testid="price-chart-cross-price"
                                        x={PLOT_RIGHT + 30}
                                        y={cross.py}
                                        fill={crossTagInk}
                                        dominantBaseline="middle"
                                        textAnchor="middle"
                                    >
                                        {/* The pointer's position read as a price, for display only; never an order price. */}
                                        {formatPrice(Math.round(cross.price))}
                                    </text>
                                </>
                            ) : null}
                            <rect x={cross.x - 30} y={PLOT_BOTTOM + 3} width={60} height={16} rx="2" fill={crossTagFill} />
                            <text
                                className="price-chart__x-label"
                                data-testid="price-chart-cross-time"
                                x={cross.x}
                                y={PLOT_BOTTOM + 11}
                                fill={crossTagInk}
                                dominantBaseline="middle"
                                textAnchor="middle"
                            >
                                {formatClockNanos(cross.t, "s")}
                            </text>
                        </g>
                    ) : null}
                </svg>
            </div>
        </div>
    );
}
