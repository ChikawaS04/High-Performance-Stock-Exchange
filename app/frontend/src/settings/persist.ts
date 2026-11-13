/**
 * Persisted panel and header settings (UI polish D10, DESIGN_SYSTEM.md §13). Pure
 * storage functions; usePersistentSetting is the React face of them.
 *
 * Keys are namespaced and versioned, `hpse.v1.<panel>.<setting>`, and each value is
 * stored as JSON. Every read is validated against the setting's allowed values:
 * anything invalid or unparsable falls back to the default and the bad key is
 * removed. A key under the `hpse.` namespace with a version other than v1 is from
 * an unknown version and is removed by purgeStaleSettings at start-up. Every
 * localStorage access is wrapped in try/catch, since storage can be unavailable
 * (private mode, a sandboxed frame) or full; a failure means "use the default",
 * never a crash. Settings are per browser profile; nothing goes to the backend.
 *
 * What never persists: order-ticket values (side, price, quantity, TIF, type), open
 * popups, expanded sections, window positions and scroll positions. A stale ticket
 * value restored on start could send an order the user did not intend.
 */

export const SETTINGS_NAMESPACE = "hpse.";
export const SETTINGS_VERSION = "v1";

/** `hpse.v1.<panel>.<setting>` */
export function settingKey(panel: string, setting: string): string {
    return `${SETTINGS_NAMESPACE}${SETTINGS_VERSION}.${panel}.${setting}`;
}

/** Every persisted setting, in one place (the DESIGN_SYSTEM.md §13 inventory). */
export const SETTING_KEYS = {
    headerLabelMode: settingKey("header", "labelMode"),
    tapeLevels: settingKey("tape", "levels"),
    tapeTimeUnits: settingKey("tape", "timeUnits"),
    priceChartBackground: settingKey("priceChart", "background"),
    priceChartLineColor: settingKey("priceChart", "lineColor"),
    priceChartGradient: settingKey("priceChart", "gradient"),
    priceChartFill: settingKey("priceChart", "fill"),
    // Per-surface chart settings (P17-4): the Charts page under priceChart, the
    // floating window under priceWindow. Read through chartSettings.useChartSettings.
    priceChartChartType: settingKey("priceChart", "chartType"),
    priceChartBarSize: settingKey("priceChart", "barSize"),
    priceChartIndicators: settingKey("priceChart", "indicators"),
    priceWindowChartType: settingKey("priceWindow", "chartType"),
    priceWindowBarSize: settingKey("priceWindow", "barSize"),
    priceWindowIndicators: settingKey("priceWindow", "indicators"),
    depthChartBackground: settingKey("depthChart", "background"),
    bookReaderDepth: settingKey("bookReader", "depth"),
    blotterStatusFilter: settingKey("blotter", "statusFilter"),
    blotterOwnerFilter: settingKey("blotter", "ownerFilter"),
    priceWindowGeometry: settingKey("priceWindow", "geometry"),
    depthWindowGeometry: settingKey("depthWindow", "geometry"),
} as const;

/** The pre-pass header key (Header.tsx stored the label mode here, un-namespaced). */
export const LEGACY_HEADER_LABEL_KEY = "header.labelMode";

export type Validator<T> = (value: unknown) => value is T;

/** A validator accepting exactly the listed values. */
export function oneOf<T extends string | number | boolean>(values: readonly T[]): Validator<T> {
    return (value: unknown): value is T => values.some((v) => v === value);
}

export const isBoolean: Validator<boolean> = (value: unknown): value is boolean => typeof value === "boolean";

function store(): Storage | null {
    try {
        return typeof window === "undefined" ? null : window.localStorage;
    } catch {
        return null;
    }
}

/** Remove a key, ignoring any storage failure. */
export function removeSetting(key: string): void {
    try {
        store()?.removeItem(key);
    } catch {
        // Unavailable storage: nothing to remove.
    }
}

/**
 * The stored value for `key` if it parses and validates, else `fallback`. An
 * invalid or unparsable stored value is removed so it is not read again.
 */
export function readSetting<T>(key: string, fallback: T, validate: Validator<T>): T {
    let raw: string | null;
    try {
        const s = store();
        if (s === null) return fallback;
        raw = s.getItem(key);
    } catch {
        return fallback;
    }
    if (raw === null) return fallback;

    let parsed: unknown;
    try {
        parsed = JSON.parse(raw);
    } catch {
        removeSetting(key);
        return fallback;
    }
    if (validate(parsed)) return parsed;
    removeSetting(key);
    return fallback;
}

/** Store `value` as JSON. False when storage is unavailable or full. */
export function writeSetting<T>(key: string, value: T): boolean {
    try {
        const s = store();
        if (s === null) return false;
        s.setItem(key, JSON.stringify(value));
        return true;
    } catch {
        return false;
    }
}

/**
 * Remove every `hpse.` key whose version is not the current one. Returns how many
 * were removed (0 when storage is unavailable).
 */
export function purgeStaleSettings(): number {
    try {
        const s = store();
        if (s === null) return 0;
        const current = `${SETTINGS_NAMESPACE}${SETTINGS_VERSION}.`;
        const stale: string[] = [];
        for (let i = 0; i < s.length; i++) {
            const k = s.key(i);
            if (k !== null && k.startsWith(SETTINGS_NAMESPACE) && !k.startsWith(current)) stale.push(k);
        }
        for (const k of stale) s.removeItem(k);
        return stale.length;
    } catch {
        return 0;
    }
}

/**
 * One-time move of the pre-pass header label key into the namespace. The legacy
 * value was a bare string ("white" / "faint"); it is carried over only when valid
 * and when the new key is not already set, and the legacy key is always removed.
 */
export function migrateLegacySettings(): void {
    try {
        const s = store();
        if (s === null) return;
        const legacy = s.getItem(LEGACY_HEADER_LABEL_KEY);
        if (legacy === null) return;
        if (s.getItem(SETTING_KEYS.headerLabelMode) === null && (legacy === "white" || legacy === "faint")) {
            s.setItem(SETTING_KEYS.headerLabelMode, JSON.stringify(legacy));
        }
        s.removeItem(LEGACY_HEADER_LABEL_KEY);
    } catch {
        // Unavailable storage: nothing to migrate.
    }
}

/** Start-up housekeeping, run once before the first render (main.tsx). */
export function initSettings(): void {
    migrateLegacySettings();
    purgeStaleSettings();
}
