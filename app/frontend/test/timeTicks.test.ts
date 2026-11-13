import { describe, expect, it } from "vitest";

import {
    MIN_LABEL_GAP_PX,
    TICK_STEPS_SEC,
    clockTicks,
    formatDateLabel,
    formatTickTime,
    labelTicks,
    pickTickStep,
} from "../src/timeTicks";
import { formatClockNanos } from "../src/format";

// Pure time-axis rules (Charts follow-up, decision 4). Times are built in local
// time so the assertions hold in any time zone.
const local = (y: number, mo: number, d: number, h: number, mi: number, s = 0): number =>
    new Date(y, mo, d, h, mi, s).getTime() * 1_000_000;

describe("pickTickStep", () => {
    it("lists the decision's round clock steps, then hour multiples", () => {
        expect(TICK_STEPS_SEC.slice(0, 11)).toEqual([5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600]);
        expect(MIN_LABEL_GAP_PX).toBe(80);
    });

    it("picks the smallest step whose labels sit at least 80px apart, per zoom level", () => {
        // [px per second, expected step]
        const cases: [number, number][] = [
            [20, 5], // 5s = 100px
            [10, 10], // 5s = 50px, 10s = 100px
            [6, 15], // 10s = 60px, 15s = 90px
            [3, 30], // 15s = 45px, 30s = 90px
            [1.5, 60],
            [1, 120], // 1m = 60px, 2m = 120px
            [0.5, 300],
            [0.1, 900], // 10m = 60px, 15m = 90px
            [0.05, 1800],
            [0.025, 3600],
        ];
        for (const [pxPerSec, step] of cases) expect(pickTickStep(pxPerSec)).toBe(step);
    });

    it("keeps candle steps on whole bars", () => {
        // 15s bars at 40px a slot: 10s would be 26px, so even zoomed in the step is a bar multiple.
        expect(pickTickStep(40 / 15, 15)).toBe(30);
        // 5m bars at 3px a slot: 1h is 36px and 2h 72px, so the axis steps past the
        // decision's list to 4h (144px) rather than crowd the labels.
        expect(pickTickStep(3 / 300, 300)).toBe(14400);
        // 1m bars never tick at 5s, 10s, 15s or 30s.
        expect(pickTickStep(40 / 60, 60)).toBe(120);
    });

    it("falls back to the largest allowed step when nothing is wide enough", () => {
        expect(pickTickStep(0)).toBe(TICK_STEPS_SEC[TICK_STEPS_SEC.length - 1]);
    });
});

describe("clockTicks", () => {
    it("places ticks on whole multiples of the step inside the window", () => {
        const t0 = local(2026, 10, 13, 15, 16, 7);
        const ticks = clockTicks(t0, t0 + 50 * 1e9, 15);
        expect(ticks.length).toBeGreaterThanOrEqual(3);
        for (const t of ticks) {
            expect(Math.round(t / 1e9) % 15).toBe(0);
            expect(t).toBeGreaterThanOrEqual(t0);
        }
    });

    it("is empty for an inverted window", () => {
        expect(clockTicks(10, 5, 5)).toEqual([]);
    });
});

describe("tick labels", () => {
    it("reads HH:MM at a step of a minute or more and HH:MM:SS below", () => {
        const t = local(2026, 10, 13, 15, 16, 30);
        expect(formatTickTime(t, 60)).toBe("15:16");
        expect(formatTickTime(t, 300)).toBe("15:16");
        expect(formatTickTime(t, 30)).toBe("15:16:30");
        expect(formatTickTime(t, 5)).toBe(formatClockNanos(t, "s"));
    });

    it("formats the date tile as weekday, day and month", () => {
        expect(formatDateLabel(local(2026, 10, 13, 9, 0))).toBe("Fri 13 Nov");
        expect(formatDateLabel(local(2026, 0, 4, 0, 0))).toBe("Sun 4 Jan");
    });

    it("shows the date on the first label and the clock on the rest", () => {
        const base = local(2026, 10, 13, 15, 16);
        const ticks = [base, base + 60e9, base + 120e9];
        const labels = labelTicks(ticks, 60);
        expect(labels.map((l) => l.text)).toEqual(["Fri 13 Nov", "15:17", "15:18"]);
        expect(labels.map((l) => l.date)).toEqual([true, false, false]);
    });

    it("shows the date again on a tick that crosses midnight", () => {
        const before = local(2026, 10, 13, 23, 58);
        const ticks = [before, local(2026, 10, 13, 23, 59), local(2026, 10, 14, 0, 0), local(2026, 10, 14, 0, 1)];
        const labels = labelTicks(ticks, 60);
        expect(labels.map((l) => l.text)).toEqual(["Fri 13 Nov", "23:59", "Sat 14 Nov", "00:01"]);
        expect(labels[2].date).toBe(true);
    });

    it("uses 24-hour time", () => {
        expect(formatTickTime(local(2026, 10, 13, 21, 5), 60)).toBe("21:05");
    });
});
