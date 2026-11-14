/**
 * The readout strip's backing (P19-10 open item). Pure: no React, no DOM.
 *
 * The readout at the top left of the price area is SVG text that can sit over the
 * highest footprint cells, candles, the volume profile or indicator lines. A quiet
 * backing (the chart's ground-aware ink.backing, no border, no shadow) sits under it on
 * every chart type, sized to the text.
 *
 * Width method. The readout is IBM Plex Mono at READOUT_FONT_PX, a monospaced face
 * whose every glyph advances MONO_ADVANCE_EM of the font size (600 units of 1000), so
 * the text's width is its character count times that advance plus the fixed gaps the
 * chart puts between parts: READOUT_PART_GAP before every keyed part but the first, and
 * READOUT_KEY_GAP between a key and its value (the tspans' dx). This is computed rather
 * than measured with getBBox, which jsdom cannot do, so the size is the same in tests
 * and in the browser.
 */

/** The readout's font size (DESIGN_SYSTEM.md §16.6, `.price-chart__readout`). */
export const READOUT_FONT_PX = 10;

/** IBM Plex Mono's advance width: 600 units of a 1000-unit em, for every glyph. */
export const MONO_ADVANCE_EM = 0.6;

/** dx before a keyed part that is not the first, and between a key and its value. */
export const READOUT_PART_GAP = 8;
export const READOUT_KEY_GAP = 3;

/** Padding around the text: horizontal and vertical. */
export const BACKING_PAD_X = 4;
export const BACKING_PAD_Y = 2;

/** One readout part: a key (empty for a bare value) and its value. */
export type ReadoutPart = readonly [key: string, value: string, keyFill?: string];

/** The readout text's width in drawing pixels, from its character count. */
export function readoutTextWidth(parts: readonly ReadoutPart[], fontPx = READOUT_FONT_PX): number {
    const adv = fontPx * MONO_ADVANCE_EM;
    let w = 0;
    parts.forEach(([k, v], i) => {
        if (k !== "") w += (i === 0 ? 0 : READOUT_PART_GAP) + k.length * adv + READOUT_KEY_GAP;
        w += v.length * adv;
    });
    return w;
}

export interface BackingRect {
    readonly x: number;
    readonly y: number;
    readonly width: number;
    readonly height: number;
}

/**
 * The backing for a readout whose text starts at `textX` and is centred vertically on
 * `textMidY` (dominant-baseline middle): the text box plus the padding on every side.
 */
export function readoutBacking(parts: readonly ReadoutPart[], textX: number, textMidY: number, fontPx = READOUT_FONT_PX): BackingRect {
    return {
        x: textX - BACKING_PAD_X,
        y: textMidY - fontPx / 2 - BACKING_PAD_Y,
        width: readoutTextWidth(parts, fontPx) + 2 * BACKING_PAD_X,
        height: fontPx + 2 * BACKING_PAD_Y,
    };
}
