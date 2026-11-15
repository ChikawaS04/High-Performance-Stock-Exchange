import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { resolve } from "node:path";

// The themed scrollbar is one reusable class, .term-scroll (DESIGN_SYSTEM.md §10,
// P8-3). Every scroll region carries it beside its own class. This test reads the
// stylesheet and the components, so a scroll region added later without the class,
// or a new scrolling class nobody registered here, fails.

const CSS = readFileSync(resolve(__dirname, "../src/styles/terminal.css"), "utf-8").replace(/\/\*[\s\S]*?\*\//g, "");
const COMPONENTS = resolve(__dirname, "../src/components");

/** Each scroll region's class, and how many elements in which component carry it. */
const REGIONS: Readonly<Record<string, Readonly<Record<string, number>>>> = {
    "depth-ladder__asks": { "DepthLadder.tsx": 1 },
    "depth-ladder__bids": { "DepthLadder.tsx": 1 },
    "trade-tape__rows": { "TradeTape.tsx": 1 },
    "fix-inspector__list": { "FixInspector.tsx": 1 },
    "fix-inspector__detail": { "FixInspector.tsx": 1 },
    "open-orders__scroll": { "OpenOrders.tsx": 1, "ExecutionsTable.tsx": 1 },
};

/** Scrolling classes that deliberately hide their scrollbar (no themed bar to show). */
const HIDDEN_BAR = new Set(["header__instrument", "field-menu__menu"]);

/** Plain class selectors whose rule scrolls (overflow auto or scroll on either axis). */
function scrollingClasses(): Set<string> {
    const out = new Set<string>();
    const re = /([^{}]+)\{([^{}]*)\}/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(CSS)) !== null) {
        if (!/overflow(-x|-y)?\s*:\s*(auto|scroll)/.test(m[2])) continue;
        for (const sel of m[1].split(",")) {
            // The last class in a compound or descendant selector names the element.
            const last = /\.([a-z0-9_-]+)\s*$/i.exec(sel.trim());
            if (last !== null) out.add(last[1]);
        }
    }
    return out;
}

/** Every static className value in a component's source. */
function classNames(src: string): string[] {
    const out: string[] = [];
    const re = /className=(?:"([^"]*)"|\{`([^`]*)`\}|\{"([^"]*)"\})/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(src)) !== null) out.push(m[1] ?? m[2] ?? m[3]);
    return out;
}

const sources: ReadonlyArray<readonly [string, string]> = readdirSync(COMPONENTS)
    .filter((f) => f.endsWith(".tsx"))
    .map((f) => [f, readFileSync(resolve(COMPONENTS, f), "utf-8")] as const);

describe(".term-scroll conformance (P8-3 shared scrollbar)", () => {
    it("defines the themed scrollbar once, on .term-scroll, from the scroll tokens", () => {
        expect(CSS).toMatch(/\.term-scroll\s*\{[^}]*scrollbar-color:\s*var\(--scroll-thumb\)\s+var\(--scroll-track\)/);
        expect(CSS).toMatch(/\.term-scroll::-webkit-scrollbar-thumb:hover\s*\{[^}]*var\(--scroll-thumb-hover\)/);
        // No region repeats the rules under its own selector any more.
        for (const cls of Object.keys(REGIONS)) expect(CSS).not.toContain(`.${cls}::-webkit-scrollbar`);
    });

    it("keeps the no-gutter rule after the regions' own stable gutters, so it still wins", () => {
        const gutterAuto = CSS.lastIndexOf(".term-scroll {\n    scrollbar-gutter: auto;");
        expect(gutterAuto).toBeGreaterThan(-1);
        for (const cls of ["depth-ladder__asks", "trade-tape__rows", "open-orders__scroll"]) {
            expect(gutterAuto, cls).toBeGreaterThan(CSS.indexOf(`.${cls} {`));
        }
    });

    it("knows every scrolling class in the stylesheet", () => {
        const found = [...scrollingClasses()].filter((c) => !HIDDEN_BAR.has(c)).sort();
        expect(found).toEqual(Object.keys(REGIONS).sort());
    });

    it("puts term-scroll on every element of every scroll region", () => {
        for (const [cls, where] of Object.entries(REGIONS)) {
            for (const [file, count] of Object.entries(where)) {
                const src = sources.find(([f]) => f === file)?.[1];
                expect(src, file).toBeDefined();
                const users = classNames(src!).filter((c) => c.split(/\s+/).includes(cls));
                expect(users.length, `${cls} in ${file}`).toBe(count);
                for (const c of users) expect(c.split(/\s+/), `${cls} in ${file}`).toContain("term-scroll");
            }
        }
    });

    it("finds no other component using a scroll region class without term-scroll", () => {
        for (const [file, src] of sources) {
            for (const c of classNames(src)) {
                const tokens = c.split(/\s+/);
                const region = tokens.find((t) => t in REGIONS);
                if (region !== undefined) {
                    expect(REGIONS[region][file], `${region} in ${file} is not registered`).toBeDefined();
                    expect(tokens, `${region} in ${file}`).toContain("term-scroll");
                }
            }
        }
    });
});
