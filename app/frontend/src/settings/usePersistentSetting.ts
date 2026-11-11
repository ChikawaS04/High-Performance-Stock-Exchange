/**
 * React state backed by a persisted setting (UI polish D10, DESIGN_SYSTEM.md §13).
 *
 * Reads the stored value once on mount (validated, falling back to the default),
 * and writes on every change, in the setter rather than on unmount, so closing the
 * app at any moment keeps the last value. Live instances of the same key (the price
 * chart page and its floating overlay, for example) stay in step through a module
 * listener set, so a change in one is reflected in the other at once.
 */

import { useCallback, useEffect, useState } from "react";

import { readSetting, writeSetting } from "./persist";
import type { Validator } from "./persist";

type Listener = (value: unknown) => void;

const listeners = new Map<string, Set<Listener>>();

export function usePersistentSetting<T>(
    key: string,
    defaultValue: T,
    validate: Validator<T>,
): readonly [T, (next: T) => void] {
    const [value, setValue] = useState<T>(() => readSetting(key, defaultValue, validate));

    useEffect(() => {
        let set = listeners.get(key);
        if (set === undefined) {
            set = new Set();
            listeners.set(key, set);
        }
        const listener: Listener = (next) => setValue(next as T);
        set.add(listener);
        return () => {
            set.delete(listener);
            if (set.size === 0) listeners.delete(key);
        };
    }, [key]);

    const update = useCallback(
        (next: T): void => {
            writeSetting(key, next);
            setValue(next);
            listeners.get(key)?.forEach((l) => l(next));
        },
        [key],
    );

    return [value, update] as const;
}
