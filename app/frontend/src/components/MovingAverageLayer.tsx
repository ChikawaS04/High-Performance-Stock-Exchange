/**
 * Moving average lines (P19-8, Phase 19 D8). The maths is indicators/movingAverages.ts.
 *
 * One 1px polyline per enabled average through each visible bar's value at the slot
 * centre, skipping bars before the window fills. SMA in --ind-sma, EMA in --ind-ema
 * (ground-aware tokens, DESIGN_SYSTEM.md §16); the second instance of each is dashed.
 * Clipped to the price area by the caller.
 */

import type { CandleSlot } from "../candleView";
import type { MaByBucket, MaKind } from "../indicators/movingAverages";

export interface MaLine {
    readonly id: string;
    readonly kind: MaKind;
    readonly second: boolean;
    /** Bars in the window, for the readout label ("SMA20"). */
    readonly length: number;
    readonly values: MaByBucket;
}

export interface MovingAverageLayerProps {
    readonly lines: readonly MaLine[];
    readonly bars: readonly CandleSlot[];
    readonly xOfSlot: (slot: number) => number;
    readonly yOf: (px: number) => number;
}

export function MovingAverageLayer({ lines, bars, xOfSlot, yOf }: MovingAverageLayerProps) {
    if (lines.length === 0) return null;
    return (
        <g className="price-chart__mas" data-testid="price-chart-mas" pointerEvents="none">
            {lines.map((l) => {
                const pts: string[] = [];
                for (const { slot, bar } of bars) {
                    const v = l.values.get(bar.bucket);
                    if (v !== undefined) pts.push(`${xOfSlot(slot)},${yOf(v.value)}`);
                }
                if (pts.length === 0) return null;
                return (
                    <polyline
                        key={l.id}
                        className={`price-chart__ma price-chart__ma--${l.kind}${l.second ? " price-chart__ma--second" : ""}`}
                        data-testid={`price-chart-ma-${l.id}`}
                        points={pts.join(" ")}
                    />
                );
            })}
        </g>
    );
}
