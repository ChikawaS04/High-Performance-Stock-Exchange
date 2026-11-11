/**
 * The rendered size of an element, kept current with a ResizeObserver. The first
 * measurement is taken at once; later ones are debounced to one animation frame, so
 * a resize drag redraws a chart at most once per frame. Returns { w: 0, h: 0 } until
 * measured, and wherever ResizeObserver does not exist (jsdom), so callers fall back
 * to their nominal layout.
 */

import { useEffect, useState } from "react";

export interface BoxSize {
    readonly w: number;
    readonly h: number;
}

const UNMEASURED: BoxSize = { w: 0, h: 0 };

export function useBoxSize(el: Element | null, enabled = true): BoxSize {
    const [size, setSize] = useState<BoxSize>(UNMEASURED);

    useEffect(() => {
        if (!enabled || el === null || typeof ResizeObserver === "undefined") return;
        const measure = (): void => {
            const r = el.getBoundingClientRect();
            setSize((prev) => (prev.w === r.width && prev.h === r.height ? prev : { w: r.width, h: r.height }));
        };
        const schedule =
            typeof requestAnimationFrame === "function"
                ? (cb: () => void): number => requestAnimationFrame(cb)
                : (cb: () => void): number => window.setTimeout(cb, 16);
        const cancel =
            typeof cancelAnimationFrame === "function"
                ? (id: number): void => cancelAnimationFrame(id)
                : (id: number): void => window.clearTimeout(id);

        let pending: number | null = null;
        measure();
        const ro = new ResizeObserver(() => {
            if (pending !== null) cancel(pending);
            pending = schedule(() => {
                pending = null;
                measure();
            });
        });
        ro.observe(el);
        return () => {
            ro.disconnect();
            if (pending !== null) cancel(pending);
        };
    }, [el, enabled]);

    return size;
}
