/**
 * Floating window geometry (UI polish follow-up, DESIGN_SYSTEM.md §14). Pure, so
 * every move, resize and clamp rule is unit tested without a DOM; FloatingWindow
 * applies it to pointer gestures.
 *
 * A window is a rect in viewport pixels. It never extends past the viewport (so its
 * header is always on screen and it can always be dragged back), and it is never
 * smaller than MIN_W x MIN_H unless the viewport itself is smaller. Resizing from
 * one edge keeps the opposite edge where it was.
 */

export interface Rect {
    readonly x: number;
    readonly y: number;
    readonly w: number;
    readonly h: number;
}

export interface Size {
    readonly w: number;
    readonly h: number;
}

/** The eight resize handles: four edges and four corners. */
export type Edge = "n" | "s" | "e" | "w" | "ne" | "nw" | "se" | "sw";

export const EDGES: readonly Edge[] = ["n", "s", "e", "w", "ne", "nw", "se", "sw"];

/** Smallest window: axes and labels stay readable. */
export const MIN_W = 360;
export const MIN_H = 240;

/** Gap kept from the viewport edge when a default window is centred. */
const DEFAULT_MARGIN = 16;

/** The CSS resize cursor for each handle. */
export const EDGE_CURSOR: Readonly<Record<Edge, string>> = {
    n: "ns-resize",
    s: "ns-resize",
    e: "ew-resize",
    w: "ew-resize",
    ne: "nesw-resize",
    sw: "nesw-resize",
    nw: "nwse-resize",
    se: "nwse-resize",
};

function clamp(v: number, lo: number, hi: number): number {
    return Math.min(Math.max(v, lo), hi);
}

/** The minimum on one axis, reduced to the viewport when the viewport is smaller. */
function minFor(min: number, limit: number): number {
    return Math.min(min, Math.max(0, limit));
}

/**
 * The rect pulled fully inside the viewport: size first (at least the minimum, at
 * most the viewport), then position. Values are rounded to whole pixels.
 */
export function clampRect(r: Rect, vp: Size): Rect {
    const w = clamp(Math.round(r.w), minFor(MIN_W, vp.w), Math.max(0, vp.w));
    const h = clamp(Math.round(r.h), minFor(MIN_H, vp.h), Math.max(0, vp.h));
    const x = clamp(Math.round(r.x), 0, Math.max(0, vp.w - w));
    const y = clamp(Math.round(r.y), 0, Math.max(0, vp.h - h));
    return { x, y, w, h };
}

/** The window moved by (dx, dy) from where the gesture started, kept inside. */
export function moveRect(start: Rect, dx: number, dy: number, vp: Size): Rect {
    return clampRect({ ...start, x: start.x + dx, y: start.y + dy }, vp);
}

/**
 * One axis of a resize. `side` says which end of the axis the handle drags:
 * "end" moves the far edge (right / bottom), "start" moves the near edge (left /
 * top) and keeps the far edge fixed, null leaves the axis alone.
 */
function resizeAxis(
    pos: number,
    size: number,
    delta: number,
    side: "start" | "end" | null,
    min: number,
    limit: number,
): readonly [number, number] {
    const lo = minFor(min, limit);
    if (side === "end") {
        return [pos, clamp(size + delta, lo, Math.max(lo, limit - pos))];
    }
    if (side === "start") {
        const far = pos + size;
        const nextPos = clamp(pos + delta, 0, Math.max(0, far - lo));
        return [nextPos, far - nextPos];
    }
    return [pos, size];
}

/** The window resized from `edge` by (dx, dy) from where the gesture started. */
export function resizeRect(start: Rect, edge: Edge, dx: number, dy: number, vp: Size): Rect {
    const xSide = edge.includes("e") ? "end" : edge.includes("w") ? "start" : null;
    const ySide = edge.includes("s") ? "end" : edge.includes("n") ? "start" : null;
    const [x, w] = resizeAxis(start.x, start.w, dx, xSide, MIN_W, vp.w);
    const [y, h] = resizeAxis(start.y, start.h, dy, ySide, MIN_H, vp.h);
    return clampRect({ x, y, w, h }, vp);
}

/**
 * The opening rect when nothing valid is saved: the window's nominal size, capped at
 * 94% of the viewport width and 82% of its height (the pre-resize CSS caps), centred
 * with at least a 16px margin.
 */
export function defaultRect(nominal: Size, vp: Size): Rect {
    const w = Math.min(nominal.w, Math.round(vp.w * 0.94));
    const h = Math.min(nominal.h, Math.round(vp.h * 0.82));
    const x = Math.max(DEFAULT_MARGIN, Math.round((vp.w - w) / 2));
    const y = Math.max(DEFAULT_MARGIN, Math.round((vp.h - h) / 2));
    return clampRect({ x, y, w, h }, vp);
}

/** True for a stored value shaped like a rect of finite numbers with a positive size. */
export function isRect(value: unknown): value is Rect {
    if (typeof value !== "object" || value === null) return false;
    const v = value as Record<string, unknown>;
    return (
        ["x", "y", "w", "h"].every((k) => typeof v[k] === "number" && Number.isFinite(v[k] as number)) &&
        (v.w as number) > 0 &&
        (v.h as number) > 0
    );
}

/** A saved rect clamped to the current viewport, or the default when none is valid. */
export function restoreRect(saved: unknown, nominal: Size, vp: Size): Rect {
    return isRect(saved) ? clampRect(saved, vp) : defaultRect(nominal, vp);
}
