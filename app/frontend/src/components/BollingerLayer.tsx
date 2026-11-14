/**
 * Bollinger bands (P19-9, Phase 19 D9). The maths is indicators/bollinger.ts.
 *
 * A faint fill between the upper and lower bands, the two bands as 1px lines and the
 * middle line, all in --ind-boll, through each visible bar's values at the slot
 * centre, skipping bars before the window fills. Clipped to the price area by the
 * caller.
 */

import type { CandleSlot } from "../candleView";
import type { BollingerPoint } from "../indicators/bollinger";

export interface BollingerLayerProps {
    readonly values: ReadonlyMap<number, BollingerPoint>;
    readonly bars: readonly CandleSlot[];
    readonly xOfSlot: (slot: number) => number;
    readonly yOf: (px: number) => number;
}

export function BollingerLayer({ values, bars, xOfSlot, yOf }: BollingerLayerProps) {
    const pts: { readonly x: number; readonly p: BollingerPoint }[] = [];
    for (const { slot, bar } of bars) {
        const p = values.get(bar.bucket);
        if (p !== undefined) pts.push({ x: xOfSlot(slot), p });
    }
    if (pts.length === 0) return null;
    const line = (f: (p: BollingerPoint) => number): string => pts.map(({ x, p }) => `${x},${yOf(f(p))}`).join(" ");
    const upper = pts.map(({ x, p }) => `${x} ${yOf(p.upper)}`);
    const lower = pts.map(({ x, p }) => `${x} ${yOf(p.lower)}`).reverse();
    const fill = `M ${upper.join(" L ")} L ${lower.join(" L ")} Z`;
    return (
        <g className="price-chart__boll" data-testid="price-chart-boll" pointerEvents="none">
            <path className="price-chart__boll-fill" data-testid="price-chart-boll-fill" d={fill} />
            <polyline className="price-chart__boll-band" data-testid="price-chart-boll-upper" points={line((p) => p.upper)} />
            <polyline className="price-chart__boll-band" data-testid="price-chart-boll-lower" points={line((p) => p.lower)} />
            <polyline className="price-chart__boll-mid" data-testid="price-chart-boll-mid" points={line((p) => p.mid)} />
        </g>
    );
}
