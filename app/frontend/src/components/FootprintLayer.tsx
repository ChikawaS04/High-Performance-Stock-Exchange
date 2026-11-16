/**
 * Footprint columns (P19-6, Phase 19 D6). The layout is the pure footprint module;
 * this draws it.
 *
 * Each visible bar is a column of price rows in its slot. A row with trades shows
 * "sell x buy" in the number font (--num, IBM Plex Sans) tabular at 9px, in the chart's text ink. A row with a
 * diagonal imbalance is tinted with the dominant side's direction colour: the up
 * colour for buyers lifting offers, the down colour for sellers hitting bids, the
 * resolved colours including any user pick. The bar's open-close range is outlined in
 * its direction colour across the rows that hold the open and the close.
 *
 * A bar without per-price detail (aged past FOOTPRINT_CAP) draws as a plain candle
 * outline, a wick and an unfilled body, with a dim "no detail" marker above it, so the
 * column is never empty.
 */

import { TICK_PX as TICK } from "../prices";
import { cellText, footprintBar, rowLoOf } from "../footprint";
import type { CandleSlot } from "../candleView";

export interface FootprintLayerProps {
    readonly bars: readonly CandleSlot[];
    readonly xOfSlot: (slot: number) => number;
    readonly slotW: number;
    readonly yOf: (px: number) => number;
    readonly stepTicks: number;
    /** Resolved up and down colours (chartColors.effectiveCandleColors). */
    readonly up: string;
    readonly down: string;
    readonly text: string;
    readonly dim: string;
}

/** The fraction of the slot a plain (no detail) candle body takes. */
const PLAIN_BODY = 0.35;

export function FootprintLayer({ bars, xOfSlot, slotW, yOf, stepTicks, up, down, text, dim }: FootprintLayerProps) {
    const width = stepTicks * TICK;
    return (
        <g className="price-chart__fp" data-testid="price-chart-footprint">
            {bars.map(({ slot, bar }) => {
                const fb = footprintBar(bar, stepTicks);
                const cx = xOfSlot(slot);
                const colX = cx - slotW / 2 + 1;
                const colW = Math.max(1, slotW - 2);
                const dir = bar.close >= bar.open ? "up" : "down";
                const dirHex = dir === "up" ? up : down;

                if (fb.noDetail) {
                    const bw = Math.max(1, slotW * PLAIN_BODY);
                    const yo = yOf(bar.open);
                    const yc = yOf(bar.close);
                    return (
                        <g key={bar.bucket} data-testid="price-chart-fp-bar" data-detail="none">
                            <line x1={cx} y1={yOf(bar.high)} x2={cx} y2={yOf(bar.low)} stroke={dirHex} strokeWidth="1" />
                            <rect
                                className="price-chart__fp-plain"
                                data-testid="price-chart-fp-plain"
                                x={cx - bw / 2}
                                y={Math.min(yo, yc)}
                                width={bw}
                                height={Math.max(1, Math.abs(yo - yc))}
                                fill="none"
                                stroke={dirHex}
                                strokeWidth="1"
                            />
                            <text
                                className="price-chart__fp-text price-chart__fp-nodetail"
                                data-testid="price-chart-fp-nodetail"
                                x={cx}
                                y={yOf(bar.high) - 6}
                                fill={dim}
                                textAnchor="middle"
                                dominantBaseline="middle"
                            >
                                no detail
                            </text>
                        </g>
                    );
                }

                const lo = Math.min(bar.open, bar.close);
                const hi = Math.max(bar.open, bar.close);
                const oTop = yOf(rowLoOf(hi, stepTicks) + width);
                const oBot = yOf(rowLoOf(lo, stepTicks));
                return (
                    <g key={bar.bucket} data-testid="price-chart-fp-bar" data-detail="rows">
                        {fb.rows.map((row) => {
                            const yTop = yOf(row.hi);
                            const yBot = yOf(row.lo);
                            const traded = row.buy + row.sell > 0;
                            return (
                                <g
                                    key={row.lo}
                                    className={
                                        row.imbalance === null
                                            ? "price-chart__fp-cell"
                                            : `price-chart__fp-cell price-chart__fp-cell--${row.imbalance}`
                                    }
                                    data-testid="price-chart-fp-cell"
                                    data-imbalance={row.imbalance ?? ""}
                                    data-lo={row.lo}
                                >
                                    {row.imbalance !== null ? (
                                        <rect
                                            className="price-chart__fp-tint"
                                            data-testid="price-chart-fp-tint"
                                            x={colX}
                                            y={yTop}
                                            width={colW}
                                            height={Math.max(1, yBot - yTop)}
                                            fill={row.imbalance === "buy" ? up : down}
                                        />
                                    ) : null}
                                    {traded ? (
                                        <text
                                            className="price-chart__fp-text"
                                            data-testid="price-chart-fp-text"
                                            x={cx}
                                            y={(yTop + yBot) / 2}
                                            fill={text}
                                            textAnchor="middle"
                                            dominantBaseline="middle"
                                        >
                                            {cellText(row)}
                                        </text>
                                    ) : null}
                                </g>
                            );
                        })}
                        <rect
                            className={`price-chart__fp-outline price-chart__fp-outline--${dir}`}
                            data-testid="price-chart-fp-outline"
                            x={colX}
                            y={oTop}
                            width={colW}
                            height={Math.max(1, oBot - oTop)}
                            fill="none"
                            stroke={dirHex}
                            strokeWidth="1"
                        />
                    </g>
                );
            })}
        </g>
    );
}
