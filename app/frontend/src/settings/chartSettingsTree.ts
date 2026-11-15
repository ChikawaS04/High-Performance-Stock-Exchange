/**
 * The chart settings view's folder tree (DESIGN_SYSTEM.md §10 and §11.4): pure, no
 * React. The popup renders it as a tree on the left and the selected folder's rows
 * (or, for a parent, its subfolders as entries) on the right, under breadcrumbs.
 *
 *   Settings
 *   ├── Display
 *   │   ├── Chart      Background, Chart type, Bar size
 *   │   ├── Candles    Up candle, Down candle, Wicks
 *   │   ├── Line       Line color, Area fill, Fill color
 *   │   └── Heatmap    Contrast, Trades (Charts page only)
 *   └── Indicators
 *       ├── Price      VWAP, SMA, SMA 2, EMA, EMA 2, Bollinger
 *       ├── Volume     Volume, Volume profile, CVD
 *       └── Trading    Executions, Orders, Bid/Ask
 *
 * Hidden by surface: a folder with nothing offered on the surface is left out (the
 * Heatmap folder on the floating window, an indicator group whose indicators are all
 * page-only). An offered indicator in no group lands in an "Other" folder, so a new
 * indicator can never vanish from the popup.
 *
 * Dim by chart type: a folder none of whose rows applies in the current chart type is
 * dim with a short note, but stays navigable (its rows show disabled, as before), so
 * the tree never moves under the user while they switch chart type.
 *
 * The last folder viewed on each surface is held here for the session (never
 * persisted, §13 excludes navigation state); the popup reopens there.
 */

import { isBarChart } from "../chartSettings";
import type { ChartSurface, ChartType } from "../chartSettings";
import { onSurface } from "../indicators/registry";
import type { IndicatorDescriptor } from "../indicators/registry";

export interface Folder {
    /** Slash-separated path from the root, "" for the root ("display/candles"). */
    readonly path: string;
    readonly label: string;
    readonly children: readonly Folder[];
    /** None of the folder's rows applies in the current chart type. */
    readonly dim: boolean;
    /** Why it is dim ("Bar charts only"), shown on its listing entry; null when live. */
    readonly note: string | null;
    /** For an indicator group, the offered indicator ids it renders, in registry order. */
    readonly indicatorIds: readonly string[];
}

export interface TreeInput {
    readonly surface: ChartSurface;
    readonly chartType: ChartType;
    readonly registry: readonly IndicatorDescriptor[];
    /** The Heatmap folder is offered (the page, with heatmap settings wired). */
    readonly heatmapOffered: boolean;
}

export interface IndicatorGroup {
    readonly id: string;
    readonly label: string;
    readonly ids: readonly string[];
}

export const INDICATOR_GROUPS: readonly IndicatorGroup[] = [
    { id: "price", label: "Price", ids: ["vwap", "sma", "sma2", "ema", "ema2", "bollinger"] },
    { id: "volume", label: "Volume", ids: ["volume", "volprofile", "cvd"] },
    { id: "trading", label: "Trading", ids: ["executions", "orders", "bidask"] },
];
export const OTHER_GROUP = { id: "other", label: "Other" } as const;

export const ROOT_PATH = "";
export const DEFAULT_FOLDER = "display/chart";
const NOT_IN_TYPE = "Not in this chart type";

function folder(path: string, label: string, children: readonly Folder[], dim = false, note: string | null = null, ids: readonly string[] = []): Folder {
    return { path, label, children, dim, note: dim ? note : null, indicatorIds: ids };
}

/** A parent is dim when it has children and every one of them is dim. */
function parent(path: string, label: string, children: readonly Folder[]): Folder {
    const dim = children.length > 0 && children.every((c) => c.dim);
    return folder(path, label, children, dim, NOT_IN_TYPE);
}

export function settingsTree({ surface, chartType, registry, heatmapOffered }: TreeInput): Folder {
    const display: Folder[] = [
        folder("display/chart", "Chart", []),
        folder("display/candles", "Candles", [], !isBarChart(chartType), "Bar charts only"),
        folder("display/line", "Line", [], chartType !== "line", "Line mode only"),
    ];
    if (heatmapOffered) display.push(folder("display/heatmap", "Heatmap", [], chartType !== "heatmap", "Heatmap only"));

    const offered = onSurface(surface, registry);
    const grouped = new Set(INDICATOR_GROUPS.flatMap((g) => g.ids));
    const groups: { readonly id: string; readonly label: string; readonly members: readonly IndicatorDescriptor[] }[] = [
        ...INDICATOR_GROUPS.map((g) => ({ id: g.id, label: g.label, members: offered.filter((d) => g.ids.includes(d.id)) })),
        { id: OTHER_GROUP.id, label: OTHER_GROUP.label, members: offered.filter((d) => !grouped.has(d.id)) },
    ];
    const indicators = groups
        .filter((g) => g.members.length > 0)
        .map((g) =>
            folder(
                `indicators/${g.id}`,
                g.label,
                [],
                g.members.every((d) => !d.chartTypes.includes(chartType)),
                NOT_IN_TYPE,
                g.members.map((d) => d.id),
            ),
        );

    return folder(ROOT_PATH, "Settings", [parent("display", "Display", display), parent("indicators", "Indicators", indicators)]);
}

/** The folder at a path, or null when it is not in the tree (hidden or unknown). */
export function findFolder(root: Folder, path: string): Folder | null {
    if (path === root.path) return root;
    for (const c of root.children) {
        if (path === c.path || path.startsWith(`${c.path}/`)) return findFolder(c, path);
    }
    return null;
}

/** The folder at a path, or its nearest ancestor in the tree (the root at worst). */
export function resolveFolder(root: Folder, path: string): Folder {
    let p = path;
    for (;;) {
        const f = findFolder(root, p);
        if (f !== null) return f;
        if (p === ROOT_PATH) return root;
        const cut = p.lastIndexOf("/");
        p = cut < 0 ? ROOT_PATH : p.slice(0, cut);
    }
}

/** The breadcrumb trail from the root to a folder in the tree, inclusive. */
export function crumbs(root: Folder, path: string): readonly Folder[] {
    const target = resolveFolder(root, path);
    const out: Folder[] = [root];
    let at = root;
    while (at.path !== target.path) {
        const next = at.children.find((c) => target.path === c.path || target.path.startsWith(`${c.path}/`));
        if (next === undefined) break;
        out.push(next);
        at = next;
    }
    return out;
}

/** Every folder below the root, depth first, in tree order (the tree pane's buttons). */
export function flatten(root: Folder): readonly { readonly folder: Folder; readonly depth: number }[] {
    const out: { folder: Folder; depth: number }[] = [];
    const walk = (f: Folder, depth: number): void => {
        for (const c of f.children) {
            out.push({ folder: c, depth });
            walk(c, depth + 1);
        }
    };
    walk(root, 0);
    return out;
}

/** How many of an indicator group's indicators are on. */
export function onCount(f: Folder, indicators: readonly string[]): number {
    return f.indicatorIds.filter((id) => indicators.includes(id)).length;
}

// ---- Last folder per surface: session memory only. ---------------------------------

const last = new Map<ChartSurface, string>();

export function lastFolder(surface: ChartSurface): string {
    return last.get(surface) ?? DEFAULT_FOLDER;
}

export function setLastFolder(surface: ChartSurface, path: string): void {
    last.set(surface, path);
}

/** Forget every surface's last folder (tests). */
export function resetLastFolders(): void {
    last.clear();
}
