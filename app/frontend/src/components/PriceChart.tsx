/**
 * Session price chart: P12 line, P12-R Bloomberg rework, P17 candles, and the
 * Charts follow-up (band toolbar, zoom and pan, clock axis, colours).
 *
 * Two chart types share one card (DESIGN_SYSTEM.md §5/§8 chrome, §11 controls,
 * §16 Charts):
 *
 *  - Candles (the default, P17 D5): clock-aligned OHLCV bars from the reducer bars
 *    slice at the chosen size. The pure candleView module picks the visible slots,
 *    the y domain from the visible highs and lows, and the time ticks. Up
 *    (close >= open) and down candles use --candle-up / --candle-down, which the
 *    user may override with any colour (chartColors); wicks match the body or take
 *    the ground-aware dim ink. The dashed last-price level and the last-price tag
 *    take the newest bar's direction colour, the tag ink chosen by luminance.
 *  - Line: the trade-print line from the capped tape, as built in P12-R, with its
 *    area fill and the last-price tag in the line colour.
 *
 * The header band (ChartBand) carries View, chart type, bar size, Indicators and the
 * gear. Both surfaces lay out at their measured box (fit mode): the Charts page's
 * plot keeps a 12:5 box and draws at its real pixel size, the floating window fills
 * its frame. Before measurement (and in jsdom) the drawing is the nominal 720 x 300.
 *
 * Time axis (Charts follow-up, decision 4): round clock steps at least 80px apart,
 * HH:MM at a step of a minute or more, HH:MM:SS below, the first label and any
 * midnight crossing as a date tile. The crosshair time tag keeps HH:MM:SS. Price
 * labels never overlap the volume pane's labels (decision 3).
 *
 * Zoom and pan (decisions 7 to 9): the mouse wheel over the plot zooms (a
 * non-passive listener, so the page does not scroll there; elsewhere it scrolls
 * normally); a drag on the plot pans, past a 3px threshold, with pointer capture.
 * The maths is pure (chartViewport, candleView); this component owns only the
 * events. While the newest bar is off screen the chart stops following and a
 * "Latest" button returns it to live. Zoom and pan state is local to each surface
 * and never persisted. The plot never starts a floating-window drag; only the band
 * does (§14).
 *
 * Prices stay integer units; only the scales touch float, here at the render edge.
 * A reference line (session open, market open) and the VWAP never widen the price
 * domain. Guards: no data plots a quiet "No trades yet" state; a single print plots
 * only the last-point marker and tag; a flat series is handled by priceAxis padding;
 * a single timestamp centres on x. Test ids (price-chart, -line, -last,
 * -ref-session, -ref-market, -cross) are preserved.
 */

import { useEffect, useId, useMemo, useRef, useState } from "react";
import type { CSSProperties, PointerEvent as ReactPointerEvent } from "react";

import { useBoxSize } from "../useBoxSize";
import { buildPriceSeries, priceAxis } from "../priceSeries";
import { formatClockNanos, formatPrice, formatQty } from "../format";
import { barSizeNs, bucketStart, seriesBars } from "../bars";
import type { Bar, BarSet, BarSizeId } from "../bars";
import {
    bodyWidth,
    buildCandleView,
    candleTickStep,
    candleTimeTicks,
    hitSlot,
    labelClear,
    lineVwapSteps,
    nearestIndex,
    placeVwapTag,
    priceAt,
    slotCentre,
} from "../candleView";
import { useChartSettings } from "../chartSettings";
import type { ChartSurface } from "../chartSettings";
import { effectiveCandleColors, inkOn, useChartColors } from "../chartColors";
import type { ChartBg } from "../chartColors";
import {
    FOLLOW_AUTO,
    LINE_FULL,
    isDrag,
    panCandles,
    panLine,
    resolveLineWindow,
    wheelNotches,
    zoomCandles,
    zoomLine,
} from "../chartViewport";
import type { CandlePanStart, CandleWindow, LineWindow, ResolvedLineWindow } from "../chartViewport";
import { clockTicks, labelTicks, pickTickStep } from "../timeTicks";
import { INDICATORS, drawableIds } from "../indicators/registry";
import type { IndicatorDescriptor } from "../indicators/registry";
import { layoutPanes } from "../indicators/layoutPanes";
import type { PaneLayout } from "../indicators/layoutPanes";
import type { OwnFill, TapeEntry } from "../state/reducer";
import {
    fillMarkersForCandles,
    fillMarkersForLine,
    markerOffset,
    markerReadout,
    nearestPoint,
    orderTagYs,
    placeOrderLines,
    venueMid,
} from "../chartOverlays";
import type { FillMarker, OrderLine } from "../chartOverlays";
import type { WorkingOrder } from "../ownExecutions";
import { dropDrag, isMarketable, moveDrag, startDrag } from "../repriceGesture";
import type { RepriceDrag } from "../repriceGesture";
import { ModifyOrderPopup } from "./ModifyOrderPopup";
import type { ModifyRequest } from "./ModifyOrderPopup";
import { ChartBand } from "./ChartBand";
import { ChartSettingsPopup } from "./ChartSettingsPopup";

/** The nominal drawing size, used until the plot is measured. */
const NOMINAL_W = 720;
const NOMINAL_H = 300;
const PAD_L = 10;
const PAD_R = 64; // room for the right-hand price axis labels and the last tag
const PAD_T = 14;
const PAD_B = 26; // room for the x time labels
const TAG_H = 18;
const TAG_W = 58;
const DATE_H = 15;
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
 * nominal 720 x 300 this is exactly the pre-resize layout; a larger box gets more
 * gridlines, a smaller one fewer.
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

/**
 * The chart's ground-aware ink. The chart offers a black and a white ground, so its
 * neutrals are chosen per ground here rather than read from the charcoal tokens
 * (there is no global --dim, §2). dim and text serve the crosshair, the readout and
 * grey wicks.
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

export function inkFor(bg: ChartBg): Ink {
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

const dirOf = (bar: Bar): "up" | "down" => (bar.close >= bar.open ? "up" : "down");

/** A text anchor that keeps an x-axis label inside the plot near either edge. */
function labelAnchor(x: number, left: number, right: number): "start" | "middle" | "end" {
    if (x - left < 26) return "start";
    if (right - x < 26) return "end";
    return "middle";
}

/** Approximate rendered width of an x-axis label (10px mono, or the 10.5px sans date tile). */
function labelWidth(text: string, date: boolean): number {
    return date ? text.length * 6 + 12 : text.length * 6.1;
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
    /** When set, the band shows the View button with "View in trading", calling this. */
    readonly onViewInTrading?: () => void;
    /** When set (floating overlay), the band shows a close button that calls this. */
    readonly onClose?: () => void;
    /**
     * Fit mode: the chart lays out at its measured box in CSS pixels, so axes,
     * gridlines, candles and tags reflow to the box rather than a fixed drawing being
     * scaled. The page's plot keeps a 12:5 box; the floating window fills its frame.
     */
    readonly fit?: boolean;
    /** Which surface's chart settings to use (P17 D5): the Charts page or the floating window. */
    readonly surface?: ChartSurface;
    /** Indicator registry; a test seam for the pane cap (P17-0 F13). */
    readonly registry?: readonly IndicatorDescriptor[];
    /** This terminal's own fills, newest first (buildOwnExecutions().fills), for the markers (P18-2). */
    readonly fills?: readonly OwnFill[];
    /** This terminal's resting orders (buildOwnExecutions().working), for the order lines (P18-3). */
    readonly working?: readonly WorkingOrder[];
    /** Lit best bid and ask in units (state.book), for a peg's line at the mid; -1 when a side is empty. */
    readonly bestBid?: number;
    readonly bestAsk?: number;
    /**
     * Drag to reprice (P18-4): called once per Confirm Modify with the order and its
     * new on-tick price. Absent, order tags are not draggable.
     */
    readonly onReprice?: (origClOrdId: number, newPx: number) => void;
    /** Orders with a reprice in flight (P18-5); their tags cannot be dragged. */
    readonly repricing?: readonly number[];
}

const NO_FILLS: readonly OwnFill[] = [];
const NO_WORKING: readonly WorkingOrder[] = [];
const NO_IDS: readonly number[] = [];
/** Order tag height, and the gap kept between a tag and the price axis. */
const ORDER_TAG_H = 14;

/** Approximate width of an order tag: 10px mono label plus padding and an arrow when pinned. */
function orderTagWidth(label: string, pinned: boolean): number {
    return (label.length + (pinned ? 2 : 0)) * 6.1 + 10;
}

/** One placed fill marker: its point in drawing units (the triangle's centre). */
interface MarkerPoint {
    readonly m: FillMarker;
    readonly x: number;
    readonly y: number;
}

/** A 7 x 6 triangle centred on (x, y): pointing up for a buy, down for a sell (P18 D2). */
function markerPath(p: MarkerPoint): string {
    const { x, y } = p;
    return p.m.side === "BUY"
        ? `M ${x} ${y - 3} L ${x + 3.5} ${y + 3} L ${x - 3.5} ${y + 3} Z`
        : `M ${x} ${y + 3} L ${x + 3.5} ${y - 3} L ${x - 3.5} ${y - 3} Z`;
}

interface Hover {
    readonly sx: number;
    readonly sy: number;
}

/** An in-progress press on the plot: where it began and the window then on screen. */
interface DragState {
    readonly pointerId: number;
    readonly x0: number;
    readonly y0: number;
    /** Drawing units per CSS pixel, from the svg's rect at the press. */
    readonly scale: number;
    readonly candle: CandlePanStart | null;
    readonly line: ResolvedLineWindow | null;
    moved: boolean;
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
    fills = NO_FILLS,
    working = NO_WORKING,
    bestBid = -1,
    bestAsk = -1,
    onReprice,
    repricing = NO_IDS,
}: PriceChartProps) {
    // Display settings persist, shared by the page and the floating window (§13).
    const colors = useChartColors();
    const { bg, lineColor, gradientOn, fill, wicks } = colors;
    // Chart settings are per surface (P17 D5).
    const settings = useChartSettings(surface, registry);
    const { chartType, barSize, indicators } = settings;

    const [hover, setHover] = useState<Hover | null>(null);
    const [panning, setPanning] = useState(false);
    // Zoom and pan: component-local, never persisted. The candle window belongs to
    // one bar size (its buckets are that size's); switching size starts following.
    const [cwinState, setCwinState] = useState<{ readonly size: BarSizeId; readonly win: CandleWindow }>({
        size: barSize,
        win: FOLLOW_AUTO,
    });
    const cwin = cwinState.size === barSize ? cwinState.win : FOLLOW_AUTO;
    const setCwin = (win: CandleWindow): void => setCwinState({ size: barSize, win });
    const [lwin, setLwin] = useState<LineWindow>(LINE_FULL);
    const dragRef = useRef<DragState | null>(null);
    // Drag to reprice (P18-4): the order-tag gesture, separate from the plot pan.
    const [orderDrag, setOrderDrag] = useState<RepriceDrag | null>(null);
    const orderDragRef = useRef<{ readonly pointerId: number; drag: RepriceDrag } | null>(null);
    const [modify, setModify] = useState<ModifyRequest | null>(null);
    const confirmedRef = useRef<ModifyRequest | null>(null);

    const [svgEl, setSvgEl] = useState<SVGSVGElement | null>(null);
    const clipId = `pc-clip-${useId().replace(/[^a-zA-Z0-9_-]/g, "")}`;
    const [plotEl, setPlotEl] = useState<HTMLDivElement | null>(null);
    const box = useBoxSize(plotEl, fit);
    const measured = fit && box.w > 0 && box.h > 0;
    const { VIEW_W, VIEW_H, PLOT_W, PLOT_H, PLOT_LEFT, PLOT_RIGHT, PLOT_TOP, PLOT_BOTTOM } = priceChartLayout(
        measured ? box.w : NOMINAL_W,
        measured ? box.h : NOMINAL_H,
    );

    const ink = inkFor(bg);
    const fillColor = fill === "line" ? lineColor : fill;
    const candles = chartType === "candles";
    const drawable = drawableIds(surface, chartType, indicators, registry);
    const vwapOn = drawable.includes("vwap");
    const executionsOn = drawable.includes("executions");
    const ordersOn = drawable.includes("orders");
    const candleInk = effectiveCandleColors(bg, colors.candleUp, colors.candleDown);

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

    // ---- Line geometry: the tape inside the visible time window. ----
    const line = useMemo(() => {
        if (candles) return null;
        const series = buildPriceSeries(tape);
        if (series.points.length === 0 || series.tMin === null || series.tMax === null) return null;
        const { points, tMin, tMax } = series;
        const win = resolveLineWindow(lwin, tMin, tMax);
        const { lo, hi, span } = win;
        const n = tape.length;
        // In view, plus one neighbour each side so the line runs to the plot edges.
        let first = points.findIndex((p) => p.t >= lo);
        if (first < 0) first = points.length - 1;
        let last = first;
        while (last + 1 < points.length && points[last + 1].t <= hi) last++;
        const drawFrom = Math.max(0, first - 1);
        const drawTo = Math.min(points.length - 1, last + 1);
        let cMin = Infinity;
        let cMax = -Infinity;
        for (let i = first; i <= last; i++) {
            if (points[i].t < lo || points[i].t > hi) continue;
            cMin = Math.min(cMin, points[i].pricePx);
            cMax = Math.max(cMax, points[i].pricePx);
        }
        if (cMin === Infinity) {
            for (let i = drawFrom; i <= drawTo; i++) {
                cMin = Math.min(cMin, points[i].pricePx);
                cMax = Math.max(cMax, points[i].pricePx);
            }
        }
        const yax = priceAxis(cMin, cMax, yTarget);
        const { dMin, dMax } = yax;
        const xOf = (t: number): number => (span <= 0 ? PLOT_LEFT + PLOT_W / 2 : PLOT_LEFT + ((t - lo) / span) * PLOT_W);
        const yOf = (px: number): number => priceBottom - ((px - dMin) / (dMax - dMin)) * priceH;
        const toPoint = (i: number): PlotPoint => ({
            t: points[i].t,
            px: points[i].pricePx,
            qty: tape[n - 1 - i].quantity,
            x: xOf(points[i].t),
            y: yOf(points[i].pricePx),
        });
        const drawn: PlotPoint[] = [];
        for (let i = drawFrom; i <= drawTo; i++) drawn.push(toPoint(i));
        const inView = drawn.filter((p) => p.t >= lo && p.t <= hi);
        const newest = toPoint(points.length - 1);
        const linePts = drawn.map((p) => `${p.x},${p.y}`).join(" ");
        const areaPath =
            drawn.length > 0
                ? `M ${drawn[0].x} ${drawn[0].y} ` +
                  drawn.map((p) => `L ${p.x} ${p.y}`).join(" ") +
                  ` L ${drawn[drawn.length - 1].x} ${priceBottom} L ${drawn[0].x} ${priceBottom} Z`
                : "";
        const stepSec = pickTickStep(span > 0 ? PLOT_W / (span / 1_000_000_000) : 0);
        const ticks = span > 0 ? clockTicks(lo, hi, stepSec) : [lo];
        return {
            tMin,
            tMax,
            win,
            dMin,
            dMax,
            levels: yax.levels,
            ticks,
            stepSec,
            drawn,
            inView,
            newest,
            linePts,
            areaPath,
            xOf,
            yOf,
        };
    }, [candles, tape, lwin, PLOT_LEFT, PLOT_W, priceBottom, priceH, yTarget]);

    // ---- Candle geometry: the selected series inside the zoom and pan window. ----
    const sizeNs = barSizeNs(barSize);
    const series = bars[barSize];
    const candle = useMemo(() => {
        if (!candles) return null;
        const view = buildCandleView(series, PLOT_W, yTarget, cwin);
        if (view === null) return null;
        const { dMin, dMax } = view;
        const yOf = (px: number): number => priceBottom - ((px - dMin) / (dMax - dMin)) * priceH;
        const xOfSlot = (slot: number): number => PLOT_LEFT + slotCentre(view, slot);
        const bw = bodyWidth(view);
        const stepSec = candleTickStep(view, sizeNs);
        const ticks = candleTimeTicks(view, sizeNs).map((k) => ({ x: xOfSlot(k.slot) - view.slotW / 2, t: k.t }));
        const all = seriesBars(series);
        const newest = all[all.length - 1];
        const lastVisible = view.bars.length > 0 ? view.bars[view.bars.length - 1].bar : newest;
        return { view, dMin, dMax, levels: view.levels, yOf, xOfSlot, bw, ticks, stepSec, newest, lastVisible };
    }, [candles, series, cwin, PLOT_W, PLOT_LEFT, priceBottom, priceH, yTarget, sizeNs]);

    // Candle and volume marks are memoised so a pointer move re-renders only the
    // crosshair and the readout, not hundreds of rects.
    const greyWicks = wicks === "grey";
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
                    <line
                        className={greyWicks ? "price-chart__wick price-chart__wick--grey" : "price-chart__wick"}
                        data-testid="price-chart-wick"
                        x1={cx}
                        y1={yOf(bar.high)}
                        x2={cx}
                        y2={yOf(bar.low)}
                        stroke={greyWicks ? ink.dim : undefined}
                    />
                    <rect className="price-chart__body" data-testid="price-chart-body" x={cx - bw / 2} y={top} width={bw} height={h} />
                </g>
            );
        });
    }, [candle, greyWicks, ink.dim]);

    const volumePane = panes.panes.find((p) => p.id === "volume") ?? null;
    const volTop = volumePane !== null ? PLOT_TOP + volumePane.top : null;
    // The volume pane's own axis band: its max label just under the separator, "VOL"
    // under that when the pane is tall enough.
    const volMaxY = volTop !== null ? volTop + 10 : null;
    const volWordY = volTop !== null && volumePane !== null && volumePane.height >= 34 ? volTop + 22 : null;
    const volumeMarks = useMemo(() => {
        if (candle === null || volumePane === null || volTop === null || volMaxY === null) return null;
        const { view, xOfSlot, bw } = candle;
        const h = volumePane.height;
        const max = Math.max(1, view.maxVolume);
        return (
            <g className="price-chart__vol-pane" data-testid="price-chart-vol">
                <line x1={PLOT_LEFT} y1={volTop} x2={PLOT_RIGHT} y2={volTop} stroke={ink.sep} strokeWidth="1" />
                {view.bars.map(({ slot, bar }) => {
                    const vh = (bar.volume / max) * h;
                    return (
                        <rect
                            key={bar.bucket}
                            className={`price-chart__vol price-chart__vol--${dirOf(bar)}`}
                            data-testid="price-chart-vol-bar"
                            x={xOfSlot(slot) - bw / 2}
                            y={volTop + h - vh}
                            width={bw}
                            height={vh}
                        />
                    );
                })}
                <text
                    className="price-chart__y-label"
                    data-testid="price-chart-vol-max"
                    x={PLOT_RIGHT + 6}
                    y={volMaxY}
                    style={{ fill: ink.dim }}
                    dominantBaseline="middle"
                    textAnchor="start"
                >
                    {formatQty(view.maxVolume)}
                </text>
                {volWordY !== null ? (
                    <text
                        className="price-chart__vol-word"
                        data-testid="price-chart-vol-word"
                        x={PLOT_RIGHT + 6}
                        y={volWordY}
                        style={{ fill: ink.dim }}
                        dominantBaseline="middle"
                        textAnchor="start"
                    >
                        VOL
                    </text>
                ) : null}
            </g>
        );
    }, [candle, volumePane, volTop, volMaxY, volWordY, PLOT_LEFT, PLOT_RIGHT, ink.sep, ink.dim]);

    // Own fill markers (P18-2), placed by the pure chartOverlays module and memoised so
    // a pointer move re-renders only the crosshair, the readout and the hover pick.
    const markerPts: readonly MarkerPoint[] = useMemo(() => {
        if (!executionsOn || fills.length === 0) return [];
        if (candle !== null) {
            return fillMarkersForCandles(fills, candle.view.firstBucket, candle.view.lastBucket, sizeNs).map((m) => ({
                m,
                x: candle.xOfSlot(m.anchor),
                y: candle.yOf(m.pricePx) + markerOffset(m.side, m.stack),
            }));
        }
        if (line !== null) {
            return fillMarkersForLine(fills, line.win.lo, line.win.hi).map((m) => ({
                m,
                x: line.xOf(m.anchor),
                y: line.yOf(m.pricePx) + markerOffset(m.side, m.stack),
            }));
        }
        return [];
    }, [executionsOn, fills, candle, line, sizeNs]);
    const markerMarks = useMemo(
        () =>
            markerPts.length === 0 ? null : (
                <g className="price-chart__fills" data-testid="price-chart-fills" pointerEvents="none">
                    {markerPts.map((p) => {
                        const cls = p.m.side === "BUY" ? "buy" : "sell";
                        return (
                            <g key={p.m.key} data-testid="price-chart-fill" data-side={p.m.side} data-count={p.m.count}>
                                <path className={`price-chart__fill price-chart__fill--${cls}`} d={markerPath(p)} />
                                {p.m.count > 1 ? (
                                    <text
                                        className={`price-chart__fill-count price-chart__fill-count--${cls}`}
                                        data-testid="price-chart-fill-count"
                                        x={p.x + 6}
                                        y={p.y}
                                        dominantBaseline="middle"
                                    >
                                        {p.m.count}
                                    </text>
                                ) : null}
                            </g>
                        );
                    })}
                </g>
            ),
        [markerPts],
    );

    // ---- Zoom (wheel) and pan (drag). The handlers read this render's geometry. ----
    const toView = (clientX: number, clientY: number): Hover | null => {
        if (svgEl === null) return null;
        const rect = svgEl.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return null;
        return {
            sx: ((clientX - rect.left) / rect.width) * VIEW_W,
            sy: ((clientY - rect.top) / rect.height) * VIEW_H,
        };
    };
    const inPlot = (p: Hover): boolean =>
        p.sx >= PLOT_LEFT && p.sx <= PLOT_RIGHT && p.sy >= PLOT_TOP && p.sy <= PLOT_BOTTOM;

    const onWheel = (e: WheelEvent): boolean => {
        const p = toView(e.clientX, e.clientY);
        if (p === null || !inPlot(p)) return false;
        const notches = wheelNotches(e.deltaY, e.deltaMode);
        if (notches === 0) return true;
        if (candle !== null) {
            const extent = { oldest: candle.view.oldestBucket, newest: candle.view.newestBucket };
            setCwin(zoomCandles(cwin, candle.view.slotW, notches, PLOT_W, extent));
        } else if (line !== null) {
            setLwin(zoomLine(lwin, notches, line.tMin, line.tMax));
        }
        return true;
    };
    const wheelRef = useRef(onWheel);
    wheelRef.current = onWheel;
    useEffect(() => {
        if (svgEl === null) return;
        const listener = (e: WheelEvent): void => {
            if (wheelRef.current(e)) e.preventDefault();
        };
        svgEl.addEventListener("wheel", listener, { passive: false });
        return () => svgEl.removeEventListener("wheel", listener);
    }, [svgEl]);

    const onDown = (e: ReactPointerEvent<SVGSVGElement>): void => {
        if (e.button !== 0) return;
        const p = toView(e.clientX, e.clientY);
        if (p === null || !inPlot(p) || svgEl === null) return;
        const rect = svgEl.getBoundingClientRect();
        dragRef.current = {
            pointerId: e.pointerId,
            x0: e.clientX,
            y0: e.clientY,
            scale: VIEW_W / rect.width,
            candle:
                candle !== null
                    ? { window: cwin, slotW: candle.view.slotW, rightBucket: candle.view.lastBucket }
                    : null,
            line: line !== null ? line.win : null,
            moved: false,
        };
        if (typeof svgEl.setPointerCapture === "function") {
            try {
                svgEl.setPointerCapture(e.pointerId);
            } catch {
                // A synthetic pointer cannot be captured; the drag still works.
            }
        }
    };

    const releaseCapture = (pointerId: number): void => {
        if (svgEl !== null && typeof svgEl.hasPointerCapture === "function" && svgEl.hasPointerCapture(pointerId)) {
            svgEl.releasePointerCapture(pointerId);
        }
    };

    // An order tag press starts a reprice drag and stops the plot pan from starting.
    const onTagDown = (e: ReactPointerEvent<SVGGElement>, o: OrderLine): void => {
        if (e.button !== 0 || onReprice === undefined) return;
        const drag = startDrag({ clOrdId: o.clOrdId, side: o.side, pricePx: o.pricePx, draggable: o.draggable }, repricing);
        if (drag === null) return;
        e.stopPropagation();
        orderDragRef.current = { pointerId: e.pointerId, drag };
        setOrderDrag(drag);
        setHover(null);
        if (svgEl !== null && typeof svgEl.setPointerCapture === "function") {
            try {
                svgEl.setPointerCapture(e.pointerId);
            } catch {
                // A synthetic pointer cannot be captured; the drag still works.
            }
        }
    };

    /** The pointer's y read as a price on the current scene, clamped to the price area. */
    const pointerPrice = (sy: number): number | null => {
        const sc = candles ? candle : line;
        if (sc === null) return null;
        const y = Math.max(priceTop, Math.min(priceBottom, sy));
        return priceAt(y, priceTop, priceH, sc.dMin, sc.dMax);
    };

    const endOrderDrag = (e: ReactPointerEvent<SVGSVGElement>, commit: boolean): boolean => {
        const od = orderDragRef.current;
        if (od === null || od.pointerId !== e.pointerId) return false;
        orderDragRef.current = null;
        setOrderDrag(null);
        releaseCapture(e.pointerId);
        if (!commit) return true;
        const drop = dropDrag(od.drag);
        if (drop.kind === "confirm") {
            const w = working.find((x) => x.clOrdId === drop.clOrdId);
            setModify({
                clOrdId: drop.clOrdId,
                side: drop.side,
                remainingQty: w !== undefined ? w.remainingQty : 0,
                oldPx: drop.oldPx,
                newPx: drop.newPx,
                marketable: false,
            });
        }
        return true;
    };

    const endDrag = (e: ReactPointerEvent<SVGSVGElement>): void => {
        if (endOrderDrag(e, true)) return;
        const d = dragRef.current;
        if (d === null || d.pointerId !== e.pointerId) return;
        dragRef.current = null;
        setPanning(false);
        if (svgEl !== null && typeof svgEl.hasPointerCapture === "function" && svgEl.hasPointerCapture(e.pointerId)) {
            svgEl.releasePointerCapture(e.pointerId);
        }
    };

    const cancelDrag = (e: ReactPointerEvent<SVGSVGElement>): void => {
        if (endOrderDrag(e, false)) return;
        endDrag(e);
    };

    const onMove = (e: ReactPointerEvent<SVGSVGElement>): void => {
        const od = orderDragRef.current;
        if (od !== null && od.pointerId === e.pointerId) {
            const p = toView(e.clientX, e.clientY);
            const px = p !== null ? pointerPrice(p.sy) : null;
            if (px !== null) {
                const next = moveDrag(od.drag, px);
                if (next !== od.drag) {
                    od.drag = next;
                    setOrderDrag(next);
                }
            }
            return;
        }
        const d = dragRef.current;
        if (d !== null && d.pointerId === e.pointerId) {
            const dxCss = e.clientX - d.x0;
            if (!d.moved && !isDrag(dxCss, e.clientY - d.y0)) {
                // Under the threshold: still a click, keep the crosshair.
            } else {
                d.moved = true;
                setPanning(true);
                setHover(null);
                const dx = dxCss * d.scale;
                if (d.candle !== null && candle !== null) {
                    const extent = { oldest: candle.view.oldestBucket, newest: candle.view.newestBucket };
                    setCwin(panCandles(d.candle, dx, PLOT_W, extent));
                } else if (d.line !== null && line !== null) {
                    setLwin(panLine(d.line, dx, PLOT_W, line.tMin, line.tMax));
                }
                return;
            }
        }
        const p = toView(e.clientX, e.clientY);
        if (p === null) return;
        setHover(inPlot(p) ? p : null);
    };
    const onLeave = (): void => setHover(null);

    const renderSettings = (close: () => void) => (
        <ChartSettingsPopup
            surface={surface}
            onClose={close}
            colors={colors}
            chartType={chartType}
            setChartType={settings.setChartType}
            barSize={barSize}
            setBarSize={settings.setBarSize}
            indicators={indicators}
            setIndicators={settings.setIndicators}
            registry={registry}
        />
    );

    const band = (
        <ChartBand
            surface={surface}
            onViewInTrading={onViewInTrading}
            onClose={onClose}
            chartType={chartType}
            setChartType={settings.setChartType}
            barSize={barSize}
            setBarSize={settings.setBarSize}
            indicators={indicators}
            setIndicators={settings.setIndicators}
            registry={registry}
            renderSettings={renderSettings}
        />
    );

    // A picked candle colour overrides the ground's token on both grounds.
    const rootStyle: Record<string, string> = {};
    if (colors.candleUp !== "default") rootStyle["--candle-up"] = colors.candleUp;
    if (colors.candleDown !== "default") rootStyle["--candle-down"] = colors.candleDown;
    const rootClass = `price-chart price-chart--${bg}${fit ? " price-chart--fit" : ""}${
        surface === "page" ? " price-chart--page" : ""
    }`;
    const scene = candles ? candle : line;

    // Empty: a quiet frame, nothing to plot, no NaN.
    if (scene === null) {
        return (
            <div className={rootClass} style={rootStyle as CSSProperties} data-menu-boundary="">
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
    const following = candle !== null ? candle.view.following : line!.win.following;

    // x-axis ticks: round clock steps, the first and any midnight crossing as a date.
    const rawTicks: readonly { readonly x: number; readonly t: number }[] =
        candle !== null ? candle.ticks : line!.ticks.map((t) => ({ x: line!.xOf(t), t }));
    const stepSec = candle !== null ? candle.stepSec : line!.stepSec;
    const tickLabels = labelTicks(
        rawTicks.map((k) => k.t),
        stepSec,
    );
    // Drop a time label that would collide with the one before it (the date tile is wider).
    type XLabel = { readonly x: number; readonly t: number; readonly text: string; readonly date: boolean; readonly left: number; readonly w: number };
    const xLabels: XLabel[] = [];
    rawTicks.forEach((k, i) => {
        const lab = tickLabels[i];
        const w = labelWidth(lab.text, lab.date);
        const left = Math.max(PLOT_LEFT, Math.min(PLOT_RIGHT - w, k.x - w / 2));
        const prev = xLabels[xLabels.length - 1];
        if (prev !== undefined && left < prev.left + prev.w + 6) return;
        xLabels.push({ x: k.x, t: k.t, text: lab.text, date: lab.date, left, w });
    });

    // Last price: the newest bar's close (candles) or the newest print (line), even
    // when panned back; the tag pins to the axis edge when it is off-scale.
    const lastPx = candle !== null ? candle.newest.close : line!.newest.px;
    const lastY = yOf(lastPx);
    const lastDir = candle !== null ? dirOf(candle.newest) : null;
    const tagY = Math.max(priceTop + TAG_H / 2, Math.min(priceBottom - TAG_H / 2, lastY));
    const lastHex = lastDir === null ? lineColor : candleInk[lastDir];

    // Price labels clear of the volume pane's separator and labels (decision 3).
    const blockers: number[] = [];
    if (volTop !== null) blockers.push(volTop);
    if (volMaxY !== null) blockers.push(volMaxY);
    if (volWordY !== null) blockers.push(volWordY);

    // VWAP overlay (P17-8): session-anchored, from the bars' recorded VWAP.
    const fiveS = bars["5s"];
    const vwapNow = fiveS.open !== null ? fiveS.open.vwap : -1;
    let vwapPts: string | null = null;
    if (vwapOn && vwapNow >= 0) {
        if (candle !== null) {
            const ps = candle.view.bars.filter((s) => s.bar.vwap >= 0).map((s) => `${candle.xOfSlot(s.slot)},${yOf(s.bar.vwap)}`);
            vwapPts = ps.length > 0 ? ps.join(" ") : null;
        } else if (line !== null) {
            const steps = lineVwapSteps(seriesBars(fiveS), FIVE_S, line.win.lo, line.win.hi);
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

    // Working order lines (P18-3): a limit dashed at its price, a peg dotted at the mid,
    // an off-domain order pinned to the edge as a tag with an arrow. Never widen the domain.
    const orderLines = ordersOn ? placeOrderLines(working, venueMid(bestBid, bestAsk), dMin, dMax) : [];
    const orderYs = orderTagYs(orderLines, yOf, priceTop, priceBottom, ORDER_TAG_H);
    const canDrag = (o: OrderLine): boolean => o.draggable && onReprice !== undefined && !repricing.includes(o.clOrdId);
    // The ghost line of a drag in progress, at its snapped price, clamped to the price area.
    const ghostY = orderDrag !== null ? Math.max(priceTop, Math.min(priceBottom, yOf(orderDrag.ghostPx))) : null;
    const ghostLabel = orderDrag !== null ? formatPrice(orderDrag.ghostPx) : "";
    const ghostW = orderTagWidth(ghostLabel, false);
    // The popup reads the live book and working list, so a crossing or a fill while it is open shows at once.
    const modifyView: ModifyRequest | null =
        modify !== null ? { ...modify, marketable: isMarketable(modify.side, modify.newPx, bestBid, bestAsk) } : null;
    const modifyActive = modify !== null && working.some((w) => w.clOrdId === modify.clOrdId) && !repricing.includes(modify.clOrdId);
    const confirmModify = (): void => {
        // Exactly once per popup: a second click before the re-render is ignored.
        if (modify === null || confirmedRef.current === modify || onReprice === undefined) return;
        confirmedRef.current = modify;
        setModify(null);
        onReprice(modify.clOrdId, modify.newPx);
    };

    // Crosshair (P17-10), hidden while panning.
    let cross: { readonly x: number; readonly t: number; readonly py: number | null; readonly price: number | null } | null = null;
    let hoverBar: Bar | null = null;
    let hoverPrint: PlotPoint | null = null;
    let hoverMarker: FillMarker | null = null;
    if (hover !== null && !panning && orderDrag === null) {
        const mi = nearestPoint(markerPts, hover.sx, hover.sy);
        if (mi >= 0) hoverMarker = markerPts[mi].m;
        const price = priceAt(hover.sy, priceTop, priceH, dMin, dMax);
        const py = price !== null ? hover.sy : null;
        if (candle !== null) {
            const hit = hitSlot(candle.view, hover.sx - PLOT_LEFT);
            if (hit !== null) {
                hoverBar = hit.bar;
                cross = { x: candle.xOfSlot(hit.slot), t: bucketStart(hit.bucket, sizeNs), py, price };
            }
        } else if (line !== null) {
            const i = nearestIndex(line.inView.map((p) => p.x), hover.sx);
            if (i >= 0) {
                hoverPrint = line.inView[i];
                cross = { x: hoverPrint.x, t: hoverPrint.t, py, price };
            }
        }
    }

    // Readout strip: the hovered bar or print, else the newest in view, so it always reads something.
    type Part = readonly [key: string, value: string];
    let readout: readonly Part[];
    if (candle !== null) {
        const b = hoverBar ?? candle.lastVisible;
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
        const p = hoverPrint ?? line!.inView[line!.inView.length - 1] ?? line!.newest;
        readout = [
            ["", formatPrice(p.px)],
            ["x", formatQty(p.qty)],
            ["@", formatClockNanos(p.t, "s")],
        ];
    }
    // A hovered own fill marker extends the readout (P18 D2): "BUY 200 @ 1446.66".
    if (hoverMarker !== null) readout = [...readout, [hoverMarker.side, markerReadout(hoverMarker, formatPrice, formatQty)]];

    const crossTagFill = ink.dim;
    const crossTagInk = inkOn(ink.dim);
    const clip = `url(#${clipId})`;
    const svgClass = `price-chart__svg price-chart__svg--pan${panning ? " price-chart__svg--panning" : ""}`;

    return (
        <div className={rootClass} style={rootStyle as CSSProperties} data-menu-boundary="">
            {band}
            <div className="price-chart__plot" ref={setPlotEl}>
                <svg
                    ref={setSvgEl}
                    className={svgClass}
                    viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
                    width="100%"
                    role="img"
                    aria-label="Session price chart"
                    data-testid="price-chart"
                    data-chart-type={chartType}
                    data-following={following ? "true" : "false"}
                    onPointerDown={onDown}
                    onPointerMove={onMove}
                    onPointerUp={endDrag}
                    onPointerCancel={cancelDrag}
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

                    {/* Horizontal gridlines + right-hand price labels (a label is skipped near the volume pane's). */}
                    {levels.map((v) =>
                        inDomain(v) ? (
                            <g key={`y-${v}`}>
                                <line x1={PLOT_LEFT} y1={yOf(v)} x2={PLOT_RIGHT} y2={yOf(v)} stroke={ink.grid} strokeWidth="1" />
                                {labelClear(yOf(v), blockers) ? (
                                    <text
                                        className="price-chart__y-label"
                                        data-testid="price-chart-y-label"
                                        x={PLOT_RIGHT + 6}
                                        y={yOf(v)}
                                        style={{ fill: ink.axis }}
                                        dominantBaseline="middle"
                                        textAnchor="start"
                                    >
                                        {formatPrice(v)}
                                    </text>
                                ) : null}
                            </g>
                        ) : null,
                    )}

                    {/* Vertical time ticks on round clock steps; the first label is the date tile. */}
                    {xLabels.map((l) => (
                        <g key={`t-${l.t}`}>
                            <line x1={l.x} y1={PLOT_TOP} x2={l.x} y2={PLOT_BOTTOM} stroke={ink.gridV} strokeWidth="1" />
                            {l.date ? (
                                <g data-testid="price-chart-x-date">
                                    <rect
                                        className="price-chart__datebg"
                                        x={l.left}
                                        y={PLOT_BOTTOM + 4}
                                        width={l.w}
                                        height={DATE_H}
                                    />
                                    <text
                                        className="price-chart__date"
                                        data-testid="price-chart-x-label"
                                        data-date="true"
                                        x={l.left + l.w / 2}
                                        y={PLOT_BOTTOM + 4 + DATE_H / 2}
                                        dominantBaseline="middle"
                                        textAnchor="middle"
                                    >
                                        {l.text}
                                    </text>
                                </g>
                            ) : (
                                <text
                                    className="price-chart__x-label"
                                    data-testid="price-chart-x-label"
                                    x={l.x}
                                    y={PLOT_BOTTOM + 14}
                                    style={{ fill: ink.axis }}
                                    textAnchor={labelAnchor(l.x, PLOT_LEFT, PLOT_RIGHT)}
                                >
                                    {l.text}
                                </text>
                            )}
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

                    {line !== null && gradientOn && line.drawn.length >= 2 ? (
                        <path className="price-chart__area" d={line.areaPath} fill="url(#pc-fill-grad)" clipPath={clip} />
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
                            clipPath={clip}
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
                            clipPath={clip}
                        />
                    )}

                    {candleMarks}
                    {volumeMarks}

                    {line !== null && line.drawn.length >= 2 ? (
                        <polyline
                            className="price-chart__line"
                            data-testid="price-chart-line"
                            points={line.linePts}
                            fill="none"
                            stroke={lineColor}
                            strokeWidth="1.6"
                            strokeLinejoin="round"
                            strokeLinecap="round"
                            clipPath={clip}
                        />
                    ) : null}

                    {line !== null && line.win.following ? (
                        <circle
                            className="price-chart__last"
                            data-testid="price-chart-last"
                            cx={line.newest.x}
                            cy={line.newest.y}
                            r="3"
                            fill={lineColor}
                        />
                    ) : null}

                    {vwapPts !== null ? (
                        <polyline className="price-chart__vwap" data-testid="price-chart-vwap" points={vwapPts} clipPath={clip} />
                    ) : null}

                    {markerMarks !== null ? <g clipPath={clip}>{markerMarks}</g> : null}

                    {orderLines.length > 0 ? (
                        <g className="price-chart__orders" data-testid="price-chart-orders">
                            {orderLines.map((o, i) => {
                                const side = o.side === "BUY" ? "buy" : "sell";
                                const y = orderYs[i];
                                const text = o.pin === null ? o.label : `${o.pin === "top" ? "▲" : "▼"} ${o.label}`;
                                const w = orderTagWidth(o.label, o.pin !== null);
                                const x = PLOT_RIGHT - 2 - w;
                                return (
                                    <g key={o.clOrdId} data-order-id={o.clOrdId}>
                                        {o.pin === null ? (
                                            <line
                                                className={`price-chart__order price-chart__order--${side} price-chart__order--${o.kind}`}
                                                data-testid="price-chart-order"
                                                data-kind={o.kind}
                                                x1={PLOT_LEFT}
                                                y1={y}
                                                x2={PLOT_RIGHT}
                                                y2={y}
                                                clipPath={clip}
                                            />
                                        ) : null}
                                        <g
                                            className={`price-chart__order-tag price-chart__order-tag--${side}${
                                                canDrag(o) ? " price-chart__order-tag--draggable" : ""
                                            }`}
                                            data-testid="price-chart-order-tag"
                                            data-pin={o.pin ?? ""}
                                            data-draggable={o.draggable ? "true" : "false"}
                                            data-repricing={repricing.includes(o.clOrdId) ? "true" : "false"}
                                            onPointerDown={canDrag(o) ? (e) => onTagDown(e, o) : undefined}
                                        >
                                            <rect
                                                className={`price-chart__order-tagbg price-chart__order-tagbg--${side}`}
                                                x={x}
                                                y={y - ORDER_TAG_H / 2}
                                                width={w}
                                                height={ORDER_TAG_H}
                                                rx="2"
                                            />
                                            <text
                                                className="price-chart__order-text"
                                                data-testid="price-chart-order-text"
                                                x={x + w / 2}
                                                y={y}
                                                dominantBaseline="middle"
                                                textAnchor="middle"
                                            >
                                                {text}
                                            </text>
                                        </g>
                                    </g>
                                );
                            })}
                        </g>
                    ) : null}

                    {/* Last-price tag on the right axis; its ink is chosen by luminance. */}
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
                                data-testid="price-chart-tag-text"
                                x={PLOT_RIGHT + 30}
                                y={tagY}
                                style={{ fill: inkOn(lastHex) }}
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
                                data-testid="price-chart-tag-text"
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

                    {orderDrag !== null && ghostY !== null ? (
                        <g className="price-chart__order-ghost" data-testid="price-chart-order-ghost" pointerEvents="none">
                            <line
                                className={`price-chart__order price-chart__order--${orderDrag.side === "BUY" ? "buy" : "sell"} price-chart__order--ghost`}
                                x1={PLOT_LEFT}
                                y1={ghostY}
                                x2={PLOT_RIGHT}
                                y2={ghostY}
                            />
                            <rect
                                className={`price-chart__order-tagbg price-chart__order-tagbg--${orderDrag.side === "BUY" ? "buy" : "sell"}`}
                                x={PLOT_RIGHT - 2 - ghostW}
                                y={ghostY - ORDER_TAG_H / 2}
                                width={ghostW}
                                height={ORDER_TAG_H}
                                rx="2"
                            />
                            <text
                                className="price-chart__order-text"
                                data-testid="price-chart-order-ghost-text"
                                x={PLOT_RIGHT - 2 - ghostW / 2}
                                y={ghostY}
                                dominantBaseline="middle"
                                textAnchor="middle"
                            >
                                {ghostLabel}
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

                {!following ? (
                    <button
                        type="button"
                        className="chart-btn price-chart__latest"
                        data-testid="pc-latest"
                        style={{
                            right: `${((VIEW_W - PLOT_RIGHT + 6) / VIEW_W) * 100}%`,
                            top: `${((priceBottom - 28) / VIEW_H) * 100}%`,
                        }}
                        onClick={() => {
                            if (candles) setCwin({ slotW: cwin.slotW, rightBucket: null });
                            else setLwin({ spanNs: lwin.spanNs, rightT: null });
                        }}
                    >
                        Latest
                        <span aria-hidden="true">{" ⇥"}</span>
                    </button>
                ) : null}
            </div>
            {modifyView !== null ? (
                <ModifyOrderPopup request={modifyView} active={modifyActive} onConfirm={confirmModify} onClose={() => setModify(null)} />
            ) : null}
        </div>
    );
}
