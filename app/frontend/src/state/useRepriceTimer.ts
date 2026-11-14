/**
 * The reprice timeout at the component edge (P18 D5): the pure machine never
 * reads a clock. One timer per reprice waiting on its cancel (status cancelSent),
 * started when the entry first appears; it fires onTimeout(origClOrdId) once if the
 * entry is still waiting after `ms`. A timer is cleared as soon as its entry leaves
 * cancelSent (cancelled, rejected, filled), and every timer is cleared on unmount.
 * The reducer ignores a timeout for an entry that is no longer cancelSent, so a
 * timer racing a confirmation is harmless.
 */

import { useEffect, useRef } from "react";

import { REPRICE_TIMEOUT_MS } from "../reprice";
import type { Reprice } from "../reprice";

export function useRepriceTimer(
    reprices: readonly Reprice[],
    onTimeout: (origClOrdId: number) => void,
    ms: number = REPRICE_TIMEOUT_MS,
): void {
    const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());
    const callback = useRef(onTimeout);
    callback.current = onTimeout;

    useEffect(() => {
        const waiting = new Set(reprices.filter((r) => r.status === "cancelSent").map((r) => r.origClOrdId));
        for (const [id, t] of timers.current) {
            if (!waiting.has(id)) {
                clearTimeout(t);
                timers.current.delete(id);
            }
        }
        for (const id of waiting) {
            if (timers.current.has(id)) continue;
            timers.current.set(
                id,
                setTimeout(() => {
                    timers.current.delete(id);
                    callback.current(id);
                }, ms),
            );
        }
    }, [reprices, ms]);

    useEffect(() => {
        const map = timers.current;
        return () => {
            for (const t of map.values()) clearTimeout(t);
            map.clear();
        };
    }, []);
}
