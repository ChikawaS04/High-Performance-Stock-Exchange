/**
 * The heatmap's display settings (P20-5, P20 D6): Contrast (Low, Normal, High) and
 * Trades (On, Off). Charts page only, since the heatmap is not offered on the
 * floating window, so the keys sit under hpse.v1.priceChart (DESIGN_SYSTEM.md §13).
 * Trades hides the bubbles only; own fills follow the Executions indicator.
 */

import { SETTING_KEYS, isBoolean, oneOf } from "../settings/persist";
import { usePersistentSetting } from "../settings/usePersistentSetting";
import { HEAT_CONTRASTS } from "./layout";
import type { HeatContrast } from "./layout";

export const DEFAULT_HEAT_CONTRAST: HeatContrast = "normal";
export const DEFAULT_HEAT_TRADES = true;

export const isHeatContrast = oneOf<HeatContrast>(HEAT_CONTRASTS);

export interface HeatmapSettings {
    readonly contrast: HeatContrast;
    readonly setContrast: (next: HeatContrast) => void;
    readonly trades: boolean;
    readonly setTrades: (next: boolean) => void;
}

export function useHeatmapSettings(): HeatmapSettings {
    const [contrast, setContrast] = usePersistentSetting<HeatContrast>(
        SETTING_KEYS.priceChartHeatmapContrast,
        DEFAULT_HEAT_CONTRAST,
        isHeatContrast,
    );
    const [trades, setTrades] = usePersistentSetting<boolean>(SETTING_KEYS.priceChartHeatmapTrades, DEFAULT_HEAT_TRADES, isBoolean);
    return { contrast, setContrast, trades, setTrades };
}
