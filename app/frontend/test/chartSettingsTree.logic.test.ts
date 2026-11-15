import { afterEach, describe, expect, it } from "vitest";

import { INDICATORS } from "../src/indicators/registry";
import type { IndicatorDescriptor } from "../src/indicators/registry";
import type { ChartSurface, ChartType } from "../src/chartSettings";
import {
    DEFAULT_FOLDER,
    crumbs,
    findFolder,
    flatten,
    lastFolder,
    onCount,
    resetLastFolders,
    resolveFolder,
    setLastFolder,
    settingsTree,
} from "../src/settings/chartSettingsTree";
import type { Folder } from "../src/settings/chartSettingsTree";

afterEach(() => resetLastFolders());

const tree = (surface: ChartSurface, chartType: ChartType, registry: readonly IndicatorDescriptor[] = INDICATORS) =>
    settingsTree({ surface, chartType, registry, heatmapOffered: surface === "page" });
const paths = (root: Folder) => flatten(root).map((e) => e.folder.path);
const at = (root: Folder, path: string) => findFolder(root, path)!;

describe("the folder tree", () => {
    it("lays out Display and Indicators with their subfolders on the page", () => {
        expect(paths(tree("page", "candles"))).toEqual([
            "display",
            "display/chart",
            "display/candles",
            "display/line",
            "display/heatmap",
            "indicators",
            "indicators/price",
            "indicators/volume",
            "indicators/trading",
        ]);
    });

    it("hides the Heatmap folder on the floating window", () => {
        const w = tree("window", "candles");
        expect(paths(w)).not.toContain("display/heatmap");
        expect(findFolder(w, "display/heatmap")).toBeNull();
    });

    it("puts each surface's indicators in their group, in registry order", () => {
        const p = tree("page", "candles");
        expect(at(p, "indicators/price").indicatorIds).toEqual(["vwap", "sma", "sma2", "ema", "ema2", "bollinger"]);
        expect(at(p, "indicators/volume").indicatorIds).toEqual(["volume", "volprofile", "cvd"]);
        expect(at(p, "indicators/trading").indicatorIds).toEqual(["executions", "orders", "bidask"]);
        // The window offers neither the volume profile nor CVD.
        expect(at(tree("window", "candles"), "indicators/volume").indicatorIds).toEqual(["volume"]);
    });

    it("covers every offered indicator exactly once", () => {
        for (const s of ["page", "window"] as const) {
            const t = tree(s, "candles");
            const ids = flatten(t).flatMap((e) => e.folder.indicatorIds);
            expect([...ids].sort()).toEqual(INDICATORS.filter((d) => d.surfaces.includes(s)).map((d) => d.id).sort());
        }
    });

    it("files an indicator in no group under Other, and hides a group with nothing offered", () => {
        const extra: IndicatorDescriptor = {
            id: "rsi",
            label: "RSI",
            placement: "pane",
            surfaces: ["page"],
            chartTypes: ["candles"],
        };
        const p = tree("page", "candles", [...INDICATORS, extra]);
        expect(at(p, "indicators/other").indicatorIds).toEqual(["rsi"]);
        expect(paths(tree("window", "candles", [...INDICATORS, extra]))).not.toContain("indicators/other");
        const onlyTrading = INDICATORS.filter((d) => ["executions", "orders"].includes(d.id));
        expect(paths(tree("page", "candles", onlyTrading)).filter((x) => x.startsWith("indicators/"))).toEqual(["indicators/trading"]);
    });
});

describe("dim folders by chart type", () => {
    const dims = (t: Folder) => flatten(t).filter((e) => e.folder.dim).map((e) => e.folder.path);

    it("dims the Line and Heatmap folders in candles", () => {
        expect(dims(tree("page", "candles"))).toEqual(["display/line", "display/heatmap"]);
    });

    it("dims Candles, Heatmap and the bar-only Volume group in line mode", () => {
        // Line keeps the volume profile, so Volume stays live on the page; the window's
        // Volume group holds volume alone, which line cannot draw.
        expect(dims(tree("page", "line"))).toEqual(["display/candles", "display/heatmap"]);
        expect(dims(tree("window", "line"))).toEqual(["display/candles", "indicators/volume"]);
    });

    it("dims Candles, Line and Volume in heatmap mode but keeps Price for VWAP", () => {
        const t = tree("page", "heatmap");
        expect(dims(t)).toEqual(["display/candles", "display/line", "indicators/volume"]);
        expect(at(t, "indicators/price").dim).toBe(false);
    });

    it("dims Line and Heatmap in footprint, where candle colours still apply", () => {
        expect(dims(tree("page", "footprint"))).toEqual(["display/line", "display/heatmap"]);
    });

    it("gives a dim folder its note and a live one none", () => {
        const t = tree("page", "line");
        expect(at(t, "display/candles").note).toBe("Bar charts only");
        expect(at(t, "display/line").note).toBeNull();
        expect(at(tree("page", "candles"), "display/line").note).toBe("Line mode only");
        expect(at(t, "display/heatmap").note).toBe("Heatmap only");
        expect(at(tree("window", "line"), "indicators/volume").note).toBe("Not in this chart type");
    });

    it("never dims the Chart folder or the top-level folders", () => {
        for (const ct of ["line", "candles", "footprint", "heatmap"] as const) {
            const t = tree("page", ct);
            expect(at(t, "display/chart").dim).toBe(false);
            expect(at(t, "display").dim).toBe(false);
            expect(at(t, "indicators").dim).toBe(false);
        }
    });
});

describe("paths and breadcrumbs", () => {
    it("finds folders and returns null for hidden or unknown ones", () => {
        const p = tree("page", "candles");
        expect(findFolder(p, "")!.label).toBe("Settings");
        expect(findFolder(p, "display/candles")!.label).toBe("Candles");
        expect(findFolder(p, "display/nope")).toBeNull();
    });

    it("falls back to the nearest ancestor in the tree", () => {
        const w = tree("window", "candles");
        expect(resolveFolder(w, "display/heatmap").path).toBe("display");
        expect(resolveFolder(w, "nothing/here").path).toBe("");
        expect(resolveFolder(w, "display/line").path).toBe("display/line");
    });

    it("builds the trail from Settings to the folder", () => {
        const p = tree("page", "candles");
        expect(crumbs(p, "display/candles").map((f) => f.label)).toEqual(["Settings", "Display", "Candles"]);
        expect(crumbs(p, "indicators").map((f) => f.label)).toEqual(["Settings", "Indicators"]);
        expect(crumbs(p, "").map((f) => f.label)).toEqual(["Settings"]);
        expect(crumbs(tree("window", "candles"), "display/heatmap").map((f) => f.path)).toEqual(["", "display"]);
    });

    it("lists the tree depth first with each folder's depth", () => {
        const flat = flatten(tree("window", "candles"));
        expect(flat.find((e) => e.folder.path === "display")!.depth).toBe(0);
        expect(flat.find((e) => e.folder.path === "display/candles")!.depth).toBe(1);
    });

    it("counts a group's indicators that are on", () => {
        const p = tree("page", "candles");
        expect(onCount(at(p, "indicators/trading"), ["executions", "bidask", "volume"])).toBe(2);
        expect(onCount(at(p, "display/candles"), ["executions"])).toBe(0);
    });
});

describe("last folder per surface (session memory)", () => {
    it("opens on Display > Chart until a folder is viewed", () => {
        expect(DEFAULT_FOLDER).toBe("display/chart");
        expect(lastFolder("page")).toBe("display/chart");
    });

    it("keeps the page and the window apart, and reset forgets both", () => {
        setLastFolder("page", "indicators/price");
        setLastFolder("window", "display/line");
        expect(lastFolder("page")).toBe("indicators/price");
        expect(lastFolder("window")).toBe("display/line");
        resetLastFolders();
        expect(lastFolder("page")).toBe(DEFAULT_FOLDER);
        expect(lastFolder("window")).toBe(DEFAULT_FOLDER);
    });
});
