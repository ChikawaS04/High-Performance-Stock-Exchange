/**
 * Trades and best quotes on the heatmap (P20-4, P20 D5 with greenlight amendment 6).
 *
 * Pure placement plus one replay function for the canvas. Each trade is a bubble at
 * its time and price, area proportional to quantity (layout.bubbleRadius), outlined
 * in the aggressor's side colour (--quote-bid buyer-initiated, --quote-ask
 * seller-initiated, the Phase 19 aggressor side) with a translucent fill of the same
 * colour; a trade with no known side is drawn in the neutral dim ink. Best bid and
 * ask are 1px step lines from the Phase 18 quote history (chartOverlays.lineQuoteSteps,
 * so they break on an empty side or a dropped connection exactly as on the other
 * chart types).
 *
 * Casing (P20-0 F6, amendment 6). A side colour on the ramp's 0.75 stop is 1.1:1, so
 * every line and bubble outline is drawn over a 3px casing of black at 70%, which is
 * 6.5:1 against that stop. The SVG overlays drawn over the heatmap (own order lines,
 * fill markers, VWAP) take the same casing in heatmap mode (terminal.css).
 *
 * Positions are in the chart's drawing units; the canvas sits at the plot's top-left,
 * so drawOverlay subtracts the plot origin.
 */

import { lineQuoteSteps } from "../chartOverlays";
import type { StepRuns } from "../chartOverlays";
import type { QuotePoint } from "../quotes";
import type { Side } from "../protocol/messages";
import type { TapeEntry } from "../state/reducer";
import { bubbleRadius, xOfNs, yOfPx } from "./layout";
import type { HeatView, RowGrid } from "./layout";

export const HEAT_BID = "#5a96ff";
export const HEAT_ASK = "#ff6a5a";
export const HEAT_NEUTRAL = "#8f8f8f";
export const HEAT_CASING = "rgba(0, 0, 0, 0.7)";
export const HEAT_CASING_W = 3;
export const HEAT_LINE_W = 1;
export const BUBBLE_FILL_ALPHA = 0.25;

export interface BubbleMark {
    readonly x: number;
    readonly y: number;
    readonly r: number;
    readonly side: Side | null;
    readonly qty: number;
    readonly pricePx: number;
    readonly t: number;
    readonly tradeId: number;
}

/** The colour a trade is drawn in. */
export function bubbleColor(side: Side | null): string {
    return side === "BUY" ? HEAT_BID : side === "SELL" ? HEAT_ASK : HEAT_NEUTRAL;
}

/** Bubbles for the trades inside the window and the grid's price range, oldest first. */
export function bubbleMarks(trades: readonly TapeEntry[], view: HeatView, grid: RowGrid): readonly BubbleMark[] {
    const out: BubbleMark[] = [];
    for (const tr of trades) {
        if (tr.timestamp < view.lo || tr.timestamp > view.hi) continue;
        if (tr.pricePx < grid.edgeLo || tr.pricePx > grid.edgeHi) continue;
        out.push({
            x: xOfNs(view, tr.timestamp),
            y: yOfPx(view, grid, tr.pricePx),
            r: bubbleRadius(tr.quantity),
            side: tr.aggressorSide ?? null,
            qty: tr.quantity,
            pricePx: tr.pricePx,
            t: tr.timestamp,
            tradeId: tr.tradeId,
        });
    }
    return out;
}

/** The topmost bubble under (x, y), or -1. Later (newer) bubbles draw on top. */
export function hitBubble(bubbles: readonly BubbleMark[], x: number, y: number): number {
    for (let i = bubbles.length - 1; i >= 0; i--) {
        const b = bubbles[i];
        if ((b.x - x) ** 2 + (b.y - y) ** 2 <= b.r ** 2) return i;
    }
    return -1;
}

export interface QuotePath {
    readonly side: "bid" | "ask";
    /** Flat x, y pairs of one unbroken step run. */
    readonly points: readonly number[];
}

function runPaths(runs: StepRuns, side: "bid" | "ask", view: HeatView, grid: RowGrid): QuotePath[] {
    return runs.map((run) => {
        const pts: number[] = [];
        for (const seg of run) {
            const y = yOfPx(view, grid, seg.px);
            pts.push(xOfNs(view, seg.a0), y, xOfNs(view, seg.a1), y);
        }
        return { side, points: pts };
    });
}

/** The best bid and ask step lines over the window, one path per unbroken run. */
export function quotePaths(quotes: readonly QuotePoint[], view: HeatView, grid: RowGrid): readonly QuotePath[] {
    const steps = lineQuoteSteps(quotes, view.lo, view.hi);
    return [...runPaths(steps.bid, "bid", view, grid), ...runPaths(steps.ask, "ask", view, grid)];
}

/** Hex to rgba with alpha. */
function withAlpha(hex: string, a: number): string {
    const r = parseInt(hex.slice(1, 3), 16);
    const g = parseInt(hex.slice(3, 5), 16);
    const b = parseInt(hex.slice(5, 7), 16);
    return `rgba(${r}, ${g}, ${b}, ${a})`;
}

function strokePath(ctx: CanvasRenderingContext2D, pts: readonly number[], ox: number, oy: number): void {
    ctx.beginPath();
    ctx.moveTo(pts[0] - ox, pts[1] - oy);
    for (let i = 2; i < pts.length; i += 2) ctx.lineTo(pts[i] - ox, pts[i + 1] - oy);
    ctx.stroke();
}

/**
 * Replay the overlay on the canvas: quote lines, then bubbles, each over its casing.
 * The canvas origin is the plot's top-left (view.plotLeft, view.plotTop).
 */
export function drawOverlay(
    ctx: CanvasRenderingContext2D,
    view: HeatView,
    paths: readonly QuotePath[],
    bubbles: readonly BubbleMark[],
): void {
    const ox = view.plotLeft;
    const oy = view.plotTop;
    ctx.lineJoin = "miter";
    for (const p of paths) {
        if (p.points.length < 4) continue;
        ctx.strokeStyle = HEAT_CASING;
        ctx.lineWidth = HEAT_CASING_W;
        strokePath(ctx, p.points, ox, oy);
        ctx.strokeStyle = p.side === "bid" ? HEAT_BID : HEAT_ASK;
        ctx.lineWidth = HEAT_LINE_W;
        strokePath(ctx, p.points, ox, oy);
    }
    for (const b of bubbles) {
        const color = bubbleColor(b.side);
        ctx.beginPath();
        ctx.arc(b.x - ox, b.y - oy, b.r, 0, Math.PI * 2);
        ctx.strokeStyle = HEAT_CASING;
        ctx.lineWidth = HEAT_CASING_W;
        ctx.stroke();
        ctx.fillStyle = withAlpha(color, BUBBLE_FILL_ALPHA);
        ctx.fill();
        ctx.strokeStyle = color;
        ctx.lineWidth = HEAT_LINE_W;
        ctx.stroke();
    }
}
