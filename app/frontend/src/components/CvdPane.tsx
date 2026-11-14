/**
 * The CVD lower pane (P19-4, Phase 19 D5). The maths is indicators/cvd.ts.
 *
 * A 1px separator on the pane's top edge, a dashed zero baseline, the CVD line in the
 * chart's text ink through each visible bar's cvd at the slot centre, and the domain's
 * top and bottom values on the right axis (with "CVD" under the top value when the
 * pane is tall enough), as the volume pane labels its own maximum.
 */

import { useMemo } from "react";

import { formatQty } from "../format";
import { cvdDomain, cvdVertices, cvdY, formatDelta } from "../indicators/cvd";
import type { CandleSlot } from "../candleView";

export interface CvdPaneProps {
    readonly bars: readonly CandleSlot[];
    readonly xOfSlot: (slot: number) => number;
    readonly top: number;
    readonly height: number;
    readonly plotLeft: number;
    readonly plotRight: number;
    readonly sep: string;
    readonly dim: string;
    readonly text: string;
}

/** Room under the top value for the "CVD" word, as the volume pane's "VOL". */
const WORD_MIN_H = 34;

export function CvdPane({ bars, xOfSlot, top, height, plotLeft, plotRight, sep, dim, text }: CvdPaneProps) {
    const geom = useMemo(() => {
        const samples = bars.map(({ slot, bar }) => ({ slot, cvd: bar.cvd }));
        const domain = cvdDomain(samples);
        // Keep the line off the separator and the bottom edge by a pixel.
        const y = (v: number): number => cvdY(v, domain, top + 1, Math.max(1, height - 2));
        const points = cvdVertices(samples)
            .map((v) => `${xOfSlot(v.slot)},${y(v.value)}`)
            .join(" ");
        return { domain, points, zeroY: y(0), count: samples.length };
    }, [bars, xOfSlot, top, height]);

    return (
        <g className="price-chart__cvd-pane" data-testid="price-chart-cvd" pointerEvents="none">
            <line x1={plotLeft} y1={top} x2={plotRight} y2={top} stroke={sep} strokeWidth="1" />
            <line
                className="price-chart__cvd-zero"
                data-testid="price-chart-cvd-zero"
                x1={plotLeft}
                y1={geom.zeroY}
                x2={plotRight}
                y2={geom.zeroY}
                stroke={sep}
                strokeWidth="1"
                strokeDasharray="2 3"
            />
            {geom.count > 0 ? (
                <polyline
                    className="price-chart__cvd"
                    data-testid="price-chart-cvd-line"
                    points={geom.points}
                    fill="none"
                    stroke={text}
                    strokeWidth="1.2"
                    strokeLinejoin="round"
                />
            ) : null}
            <text
                className="price-chart__y-label"
                data-testid="price-chart-cvd-hi"
                x={plotRight + 6}
                y={top + 10}
                style={{ fill: dim }}
                dominantBaseline="middle"
                textAnchor="start"
            >
                {formatDelta(geom.domain.hi, formatQty)}
            </text>
            {height >= WORD_MIN_H ? (
                <text
                    className="price-chart__vol-word"
                    data-testid="price-chart-cvd-word"
                    x={plotRight + 6}
                    y={top + 22}
                    style={{ fill: dim }}
                    dominantBaseline="middle"
                    textAnchor="start"
                >
                    CVD
                </text>
            ) : null}
            <text
                className="price-chart__y-label"
                data-testid="price-chart-cvd-lo"
                x={plotRight + 6}
                y={top + height - 6}
                style={{ fill: dim }}
                dominantBaseline="middle"
                textAnchor="start"
            >
                {formatDelta(geom.domain.lo, formatQty)}
            </text>
        </g>
    );
}
