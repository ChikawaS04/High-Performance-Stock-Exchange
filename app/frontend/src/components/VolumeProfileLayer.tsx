/**
 * Volume profile layer (P19-3, Phase 19 D4). The pure maths is
 * indicators/volumeProfile.ts; this draws its rows.
 *
 * A horizontal histogram against the right-hand side of the price area: each display
 * row is a bar from the price axis leftward, its length proportional to the row's
 * share of the largest row of the session, at most PROFILE_WIDTH_FRACTION of the plot.
 * Neutral greys only, since the profile is volume, not direction: rows outside the
 * value area are faint, value-area rows brighter, and the point of control row takes
 * the chart's text ink. Drawn behind the candles and the line, clipped to the price
 * area by the caller.
 */

import { useMemo } from "react";

import { PROFILE_WIDTH_FRACTION, profileRows } from "../indicators/volumeProfile";
import type { ProfileStats } from "../indicators/volumeProfile";

export interface VolumeProfileLayerProps {
    readonly stats: ProfileStats;
    readonly dMin: number;
    readonly dMax: number;
    readonly yOf: (px: number) => number;
    readonly priceH: number;
    readonly plotRight: number;
    readonly plotW: number;
    /** The chart's ground-aware neutrals (PriceChart inkFor). */
    readonly dim: string;
    readonly text: string;
}

export function VolumeProfileLayer({ stats, dMin, dMax, yOf, priceH, plotRight, plotW, dim, text }: VolumeProfileLayerProps) {
    const layout = useMemo(() => profileRows(stats, dMin, dMax, priceH), [stats, dMin, dMax, priceH]);
    if (layout.rows.length === 0 || layout.maxVolume <= 0) return null;
    const maxW = plotW * PROFILE_WIDTH_FRACTION;
    return (
        <g className="price-chart__profile" data-testid="price-chart-profile" pointerEvents="none">
            {layout.rows.map((r) => {
                const w = (r.volume / layout.maxVolume) * maxW;
                const yTop = yOf(r.hi);
                const yBot = yOf(r.lo);
                const kind = r.poc ? "poc" : r.inValue ? "va" : "out";
                return (
                    <rect
                        key={r.lo}
                        className={`price-chart__profile-row price-chart__profile-row--${kind}`}
                        data-testid="price-chart-profile-row"
                        data-kind={kind}
                        data-lo={r.lo}
                        data-volume={r.volume}
                        x={plotRight - w}
                        y={yTop}
                        width={w}
                        // A 1px gap between rows when they are tall enough to keep it.
                        height={Math.max(1, yBot - yTop - (yBot - yTop >= 4 ? 1 : 0))}
                        fill={r.poc ? text : dim}
                    />
                );
            })}
        </g>
    );
}
