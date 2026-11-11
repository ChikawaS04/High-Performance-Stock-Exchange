/**
 * Depth chart mid-label geometry (UI polish D3). Pure, so the sizing rule is unit
 * tested without a DOM.
 *
 * The DepthCurve svg scales its 720 x 320 viewBox to whatever box it is given
 * (preserveAspectRatio "meet"), so a size written in user units renders at a
 * different pixel size in the regular panel (about 0.55x) and the expanded window
 * (about 1.4x). To make the mid price equally readable in both, the label is sized
 * in CSS pixels: MID_LABEL_PX is the one size constant both views read, and it is
 * divided by the rendered scale to get user units. 13px is the expanded view's
 * rendered size before this pass (9.5 units at ~1.41x) and the floor D3 sets.
 */

export const VIEW_W = 720;
export const VIEW_H = 320;

/** Rendered size of the depth chart mid label in CSS pixels, in every view. */
export const MID_LABEL_PX = 13;

/** Advance width of one IBM Plex Mono digit, as a fraction of the font size. */
const MONO_ADVANCE = 0.6;
/** User-unit gap between the top of the viewBox and the tag box. */
const TAG_TOP = 4;

/**
 * The "meet" scale of the viewBox inside a rendered box: rendered pixels per user
 * unit. Falls back to 1 when the box has no measured size yet (first paint, jsdom).
 */
export function svgScale(renderedWidth: number, renderedHeight: number): number {
    const sx = renderedWidth / VIEW_W;
    const sy = renderedHeight / VIEW_H;
    const s = Math.min(sx, sy);
    return Number.isFinite(s) && s > 0 ? s : 1;
}

export interface MidLabelLayout {
    /** Font size in user units; renders at MID_LABEL_PX. */
    readonly fontSize: number;
    readonly boxY: number;
    readonly boxW: number;
    readonly boxH: number;
    /** Text baseline, vertically centred in the box. */
    readonly textY: number;
    /** Bottom edge of the box: anything below the tag starts here. */
    readonly bottom: number;
}

/** Tag geometry for a mid label of `chars` characters at the given render scale. */
export function midLabelLayout(scale: number, chars: number): MidLabelLayout {
    const fontSize = MID_LABEL_PX / (scale > 0 ? scale : 1);
    const boxH = fontSize * 1.5;
    const boxW = Math.max(1, chars) * fontSize * MONO_ADVANCE + fontSize;
    const boxY = TAG_TOP;
    // Mono digits sit roughly 0.35em below the box centre line at the baseline.
    const textY = boxY + boxH / 2 + fontSize * 0.35;
    return { fontSize, boxY, boxW, boxH, textY, bottom: boxY + boxH };
}

/**
 * Horizontal centre for the tag so the whole box stays inside the viewBox even when
 * the mid sits near an edge. The dashed mid line itself stays on the true mid.
 */
export function clampTagX(midX: number, boxW: number): number {
    const half = boxW / 2;
    if (boxW >= VIEW_W) return VIEW_W / 2;
    return Math.min(Math.max(midX, half), VIEW_W - half);
}
