/**
 * Pane layout for the chart plot (P17-5, P17 D6). Pure, integer pixels.
 *
 * Splits a plot of `height` into the price area on top and one rectangle per
 * enabled lower pane below it, in registry order. Each pane takes
 * floor(min(0.2, 0.4 / k) * height) for k panes: one pane is 20%, two are 20% each,
 * three share 40%. The price area takes the integer remainder and always keeps at
 * least 60%. Rectangles tile the height exactly with no gap or overlap (the
 * component draws a 1px separator on each pane's top edge). Panes beyond the
 * surface's PANE_CAP are not laid out.
 *
 * The caller passes ids already filtered to what the current chart type can draw
 * (drawableIds), so a volume id kept in line mode lays out nothing.
 */

import { INDICATORS, PANE_CAP, enabledPaneIds } from "./registry";
import type { ChartSurface, IndicatorDescriptor } from "./registry";

export interface PaneRect {
    readonly top: number;
    readonly height: number;
}

export interface PaneLayout {
    readonly price: PaneRect;
    readonly panes: readonly (PaneRect & { readonly id: string })[];
}

export function layoutPanes(
    surface: ChartSurface,
    enabledIds: readonly string[],
    height: number,
    registry: readonly IndicatorDescriptor[] = INDICATORS,
): PaneLayout {
    const h = Math.max(0, Math.floor(height));
    const ids = enabledPaneIds(enabledIds, registry).slice(0, PANE_CAP[surface]);
    if (ids.length === 0) return { price: { top: 0, height: h }, panes: [] };

    const each = Math.floor(Math.min(0.2, 0.4 / ids.length) * h);
    const priceH = h - each * ids.length;
    return {
        price: { top: 0, height: priceH },
        panes: ids.map((id, i) => ({ id, top: priceH + i * each, height: each })),
    };
}
