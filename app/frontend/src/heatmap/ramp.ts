/**
 * The heatmap's colour ramp (P20-2, P20 D2, stops accepted at P20-0 F6).
 *
 * One neutral ramp from the well colour through a dark blue-grey to near white. Bid
 * and ask are told apart by where they sit against the best bid and ask lines, so
 * blue and red stay reserved for side (DESIGN_SYSTEM.md §2). The heatmap always draws
 * on the black ground (F6): an empty cell is the ground, never a ramp colour.
 *
 * The stops are the source of truth for the canvas (DESIGN_SYSTEM.md documents them
 * as --heat-0 .. --heat-4). The renderer reads a 256-entry lookup table of packed
 * pixels, built once, so painting a cell is one array write.
 */

export interface RampStop {
    readonly at: number;
    readonly hex: string;
}

export const HEAT_STOPS: readonly RampStop[] = [
    { at: 0, hex: "#0d0d0d" },
    { at: 0.25, hex: "#1a2430" },
    { at: 0.5, hex: "#3b4652" },
    { at: 0.75, hex: "#8a9097" },
    { at: 1, hex: "#ebebeb" },
];

/** The ground under the heatmap: empty cells and unknown depth. */
export const HEAT_GROUND = "#000000";

/** Lookup table entries: intensity i maps to entry round(i * (RAMP_SIZE - 1)). */
export const RAMP_SIZE = 256;

export type Rgb = readonly [number, number, number];

export function hexRgb(hex: string): Rgb {
    return [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
}

/** The ramp colour at intensity v in [0, 1], interpolated linearly between stops. */
export function rampRgb(v: number, stops: readonly RampStop[] = HEAT_STOPS): Rgb {
    const x = Math.max(0, Math.min(1, v));
    for (let i = 1; i < stops.length; i++) {
        const a = stops[i - 1];
        const b = stops[i];
        if (x <= b.at) {
            const f = b.at > a.at ? (x - a.at) / (b.at - a.at) : 0;
            const ca = hexRgb(a.hex);
            const cb = hexRgb(b.hex);
            return [
                Math.round(ca[0] + (cb[0] - ca[0]) * f),
                Math.round(ca[1] + (cb[1] - ca[1]) * f),
                Math.round(ca[2] + (cb[2] - ca[2]) * f),
            ];
        }
    }
    return hexRgb(stops[stops.length - 1].hex);
}

/**
 * Pack an opaque colour for a Uint32Array view over ImageData bytes (R, G, B, A in
 * memory order). Every browser this app supports is little-endian, so the word is
 * A B G R from the high byte down.
 */
export function packRgb(rgb: Rgb): number {
    return ((255 << 24) | (rgb[2] << 16) | (rgb[1] << 8) | rgb[0]) >>> 0;
}

/** The packed ground pixel. */
export const GROUND_PIXEL = packRgb(hexRgb(HEAT_GROUND));

/** The packed lookup table, RAMP_SIZE entries. */
export function buildRampLut(stops: readonly RampStop[] = HEAT_STOPS): Uint32Array {
    const lut = new Uint32Array(RAMP_SIZE);
    for (let i = 0; i < RAMP_SIZE; i++) lut[i] = packRgb(rampRgb(i / (RAMP_SIZE - 1), stops));
    return lut;
}

/** The lookup index of an intensity. */
export function rampIndex(v: number): number {
    return Math.round(Math.max(0, Math.min(1, v)) * (RAMP_SIZE - 1));
}
