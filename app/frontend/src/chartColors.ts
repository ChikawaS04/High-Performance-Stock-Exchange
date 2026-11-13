/**
 * Price chart colour settings (Charts follow-up, decisions 5 and 6).
 *
 * Up candle, down candle, line colour and fill colour are each chosen on their own
 * from one shared palette. The palette is the approved mockup's 42 swatches in six
 * columns (greens, reds, blues, cyans, violets, oranges and yellows, neutrals); it
 * holds every pre-follow-up line colour, so a stored line colour stays valid. Any
 * #RRGGBB is accepted as well, through the palette's Hex field.
 *
 * Candle colours store "default" until the user picks one. The default is the
 * ground's direction colour (the --candle-up / --candle-down tokens in
 * terminal.css, mirrored in CANDLE_DEFAULTS for the luminance ink); a picked colour
 * applies on both grounds. Wicks match the body or take the chart's ground-aware dim
 * ink. Recent colours keep the last RECENT_CAP picks, newest first, once each.
 *
 * Display settings, shared by the Charts page and the floating window like the
 * background and line colour (DESIGN_SYSTEM.md §13). Invalid stored values fall back
 * to the default (readSetting removes the bad key).
 */

import { useMemo } from "react";

import { SETTING_KEYS, isBoolean, oneOf } from "./settings/persist";
import type { Validator } from "./settings/persist";
import { usePersistentSetting } from "./settings/usePersistentSetting";

export type ChartBg = "black" | "white";
/** "line" matches the price line; any other value is a #rrggbb fill. */
export type FillChoice = "line" | string;
/** "default" is the ground's direction colour; any other value is a #rrggbb pick. */
export type CandleColor = "default" | string;
export type WickMode = "body" | "grey";

export const PALETTE: readonly string[] = [
    "#00ff00", "#12d170", "#2bb673", "#1f9d3a", "#0a7d2c", "#7fff7a",
    "#ff0000", "#ff4d4f", "#e05555", "#d40000", "#a30000", "#ff8a80",
    "#3b93ff", "#5a96ff", "#1f5fd1", "#0033cc", "#8ab4ff", "#00a2ff",
    "#1fd6e8", "#0fd9b6", "#00b3a4", "#00808a", "#7ff0e6", "#26c6da",
    "#8a6bff", "#b4a7d6", "#6a5a9a", "#f14fe6", "#c2185b", "#ff6ec7",
    "#ff7a18", "#ffb01f", "#ffe234", "#fff59d", "#c87a14", "#ff9e80",
    "#ffffff", "#d8d8d8", "#a9b4c4", "#8f8f8f", "#5e5e5e", "#262626",
];
export const PALETTE_COLUMNS = 6;

export const DEFAULT_BG: ChartBg = "black";
export const DEFAULT_LINE_COLOR = "#ffb01f";
export const DEFAULT_FILL: FillChoice = "line";
export const DEFAULT_CANDLE: CandleColor = "default";
export const DEFAULT_WICKS: WickMode = "body";
export const RECENT_CAP = 4;

/** The ground defaults, mirroring --candle-up / --candle-down in terminal.css. */
export const CANDLE_DEFAULTS: Readonly<Record<ChartBg, { readonly up: string; readonly down: string }>> = {
    black: { up: "#00ff00", down: "#ff0000" },
    white: { up: "#1f9d3a", down: "#d40000" },
};

const HEX = /^#[0-9a-f]{6}$/;

/** A stored colour: exactly #rrggbb, lower case. */
export const isHexColor: Validator<string> = (v: unknown): v is string => typeof v === "string" && HEX.test(v);

/** Typed text to a stored colour: #RRGGBB in any case, else null (ignored). */
export function parseHexInput(text: string): string | null {
    const t = text.trim().toLowerCase();
    return HEX.test(t) ? t : null;
}

export const isChartBg: Validator<ChartBg> = oneOf<ChartBg>(["black", "white"]);
export const isFillChoice: Validator<FillChoice> = (v: unknown): v is FillChoice => v === "line" || isHexColor(v);
export const isCandleColor: Validator<CandleColor> = (v: unknown): v is CandleColor =>
    v === "default" || isHexColor(v);
export const isWickMode: Validator<WickMode> = oneOf<WickMode>(["body", "grey"]);
/** Lenient: any array of strings. sanitizeRecent keeps the valid ones. */
export const isRecentList: Validator<readonly string[]> = (v: unknown): v is readonly string[] =>
    Array.isArray(v) && v.every((x) => typeof x === "string");

/** Valid colours only, once each, at most RECENT_CAP, in stored order. */
export function sanitizeRecent(list: readonly string[]): readonly string[] {
    const out: string[] = [];
    for (const c of list) {
        if (isHexColor(c) && !out.includes(c) && out.length < RECENT_CAP) out.push(c);
    }
    return out;
}

/** A pick goes to the front; a repeat moves to the front; the list keeps RECENT_CAP. */
export function pushRecent(list: readonly string[], hex: string): readonly string[] {
    if (!isHexColor(hex)) return list;
    return [hex, ...list.filter((c) => c !== hex)].slice(0, RECENT_CAP);
}

/** The colours candles draw in: a pick on any ground, else the ground's default. */
export function effectiveCandleColors(
    bg: ChartBg,
    up: CandleColor,
    down: CandleColor,
): { readonly up: string; readonly down: string } {
    const d = CANDLE_DEFAULTS[bg];
    return { up: up === "default" ? d.up : up, down: down === "default" ? d.down : down };
}

/**
 * Legible ink (near-black or white) for text on a coloured fill, by perceived
 * luminance. The 0.55 threshold reproduces the pre-follow-up tag inks for all four
 * default candle colours: dark on the black ground's #00ff00, white on the reds and
 * on the white ground's green.
 */
export function inkOn(hex: string): string {
    const h = hex.replace("#", "");
    const r = parseInt(h.slice(0, 2), 16);
    const g = parseInt(h.slice(2, 4), 16);
    const b = parseInt(h.slice(4, 6), 16);
    const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return lum > 0.55 ? "#0b0b0b" : "#ffffff";
}

export type ColorSetting = "up" | "down" | "line" | "fill";

export interface ChartColors {
    readonly bg: ChartBg;
    readonly setBg: (next: ChartBg) => void;
    readonly lineColor: string;
    readonly gradientOn: boolean;
    readonly setGradientOn: (next: boolean) => void;
    readonly fill: FillChoice;
    readonly candleUp: CandleColor;
    readonly candleDown: CandleColor;
    readonly wicks: WickMode;
    readonly setWicks: (next: WickMode) => void;
    readonly recent: readonly string[];
    /** Set one colour; a hex pick also goes to the front of Recent. "line" only for fill. */
    readonly pick: (setting: ColorSetting, value: string) => void;
    /** Reset colors: up, down, line, fill and wicks back to their defaults. Recent stays. */
    readonly resetColors: () => void;
}

/** Every display setting of the price chart, persisted and shared by both surfaces. */
export function useChartColors(): ChartColors {
    const [bg, setBg] = usePersistentSetting<ChartBg>(SETTING_KEYS.priceChartBackground, DEFAULT_BG, isChartBg);
    const [lineColor, setLineColor] = usePersistentSetting<string>(
        SETTING_KEYS.priceChartLineColor,
        DEFAULT_LINE_COLOR,
        isHexColor,
    );
    const [gradientOn, setGradientOn] = usePersistentSetting<boolean>(SETTING_KEYS.priceChartGradient, true, isBoolean);
    const [fill, setFill] = usePersistentSetting<FillChoice>(SETTING_KEYS.priceChartFill, DEFAULT_FILL, isFillChoice);
    const [candleUp, setCandleUp] = usePersistentSetting<CandleColor>(
        SETTING_KEYS.priceChartCandleUp,
        DEFAULT_CANDLE,
        isCandleColor,
    );
    const [candleDown, setCandleDown] = usePersistentSetting<CandleColor>(
        SETTING_KEYS.priceChartCandleDown,
        DEFAULT_CANDLE,
        isCandleColor,
    );
    const [wicks, setWicks] = usePersistentSetting<WickMode>(SETTING_KEYS.priceChartWicks, DEFAULT_WICKS, isWickMode);
    const [storedRecent, setRecent] = usePersistentSetting<readonly string[]>(
        SETTING_KEYS.priceChartRecentColors,
        [],
        isRecentList,
    );
    const recent = useMemo(() => sanitizeRecent(storedRecent), [storedRecent]);

    const pick = (setting: ColorSetting, value: string): void => {
        if (setting === "fill" && value === "line") {
            setFill("line");
            return;
        }
        if (!isHexColor(value)) return;
        if (setting === "up") setCandleUp(value);
        else if (setting === "down") setCandleDown(value);
        else if (setting === "line") setLineColor(value);
        else setFill(value);
        setRecent(pushRecent(recent, value));
    };

    const resetColors = (): void => {
        setCandleUp(DEFAULT_CANDLE);
        setCandleDown(DEFAULT_CANDLE);
        setLineColor(DEFAULT_LINE_COLOR);
        setFill(DEFAULT_FILL);
        setWicks(DEFAULT_WICKS);
    };

    return {
        bg,
        setBg,
        lineColor,
        gradientOn,
        setGradientOn,
        fill,
        candleUp,
        candleDown,
        wicks,
        setWicks,
        recent,
        pick,
        resetColors,
    };
}
