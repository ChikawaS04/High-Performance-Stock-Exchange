import { afterEach, describe, expect, it, vi } from "vitest";

import { ALERT_PARTIALS, ALERT_SECONDS, playAlertSound, resetAlertSound } from "../src/alertSound";

// The blocked-click alert: a short synthesised chime, since a page cannot play the
// operating system's own alert sound.

afterEach(() => {
    resetAlertSound();
    vi.unstubAllGlobals();
});

interface Osc {
    type: string;
    freq: number;
    started: number;
    stopped: number;
}

function fakeAudio(state: "running" | "suspended" = "running") {
    const oscs: Osc[] = [];
    const resume = vi.fn(() => Promise.resolve());
    let made = 0;
    class FakeContext {
        state = state;
        currentTime = 2;
        destination = {};
        resume = resume;
        constructor() {
            made++;
        }
        createOscillator() {
            const o: Osc = { type: "", freq: 0, started: -1, stopped: -1 };
            oscs.push(o);
            return {
                set type(t: string) {
                    o.type = t;
                },
                frequency: { setValueAtTime: (f: number) => (o.freq = f) },
                connect: () => undefined,
                start: (t: number) => (o.started = t),
                stop: (t: number) => (o.stopped = t),
            };
        }
        createGain() {
            return { gain: { setValueAtTime: () => undefined, exponentialRampToValueAtTime: () => undefined }, connect: () => undefined };
        }
    }
    vi.stubGlobal("AudioContext", FakeContext);
    return { oscs, resume, made: () => made };
}

describe("playAlertSound", () => {
    it("plays one sine partial per chime partial, starting now and stopping after the chime", () => {
        const a = fakeAudio();
        playAlertSound();
        expect(a.oscs.map((o) => o.freq)).toEqual(ALERT_PARTIALS.map(([f]) => f));
        for (const o of a.oscs) {
            expect(o.type).toBe("sine");
            expect(o.started).toBe(2);
            expect(o.stopped).toBeGreaterThan(2 + ALERT_SECONDS);
        }
    });

    it("reuses one audio context and resumes it when the browser suspended it", () => {
        const a = fakeAudio("suspended");
        playAlertSound();
        playAlertSound();
        expect(a.made()).toBe(1);
        expect(a.resume).toHaveBeenCalled();
        expect(a.oscs).toHaveLength(2 * ALERT_PARTIALS.length);
    });

    it("does nothing, and never throws, without Web Audio", () => {
        vi.stubGlobal("AudioContext", undefined);
        expect(() => playAlertSound()).not.toThrow();
        vi.stubGlobal("AudioContext", class {
            constructor() {
                throw new Error("blocked");
            }
        });
        expect(() => playAlertSound()).not.toThrow();
    });
});
