/**
 * The blocked-click alert for the topmost confirmation popups (Order Confirmation and
 * Cancel by Order ID, DESIGN_SYSTEM.md §10): a click outside one of them plays this
 * instead of closing it, the way a desktop modal dialog answers a click behind it.
 *
 * A web page cannot play the operating system's own alert sound (there is no browser
 * API for it), so this synthesises a short two-partial chime close to the Windows
 * default beep with Web Audio: about 300 ms, a fast attack and an exponential decay.
 * One AudioContext is created on first use (a click, so autoplay rules allow it) and
 * reused. Where Web Audio is missing (tests, old browsers) it does nothing.
 */

type AudioContextCtor = new () => AudioContext;

let ctx: AudioContext | null = null;

function audioContext(): AudioContext | null {
    if (ctx !== null) return ctx;
    const w = globalThis as unknown as { AudioContext?: AudioContextCtor; webkitAudioContext?: AudioContextCtor };
    const Ctor = w.AudioContext ?? w.webkitAudioContext;
    if (Ctor === undefined) return null;
    ctx = new Ctor();
    return ctx;
}

/** Fundamental and overtone of the chime, in Hz, with their peak gains. */
export const ALERT_PARTIALS: readonly (readonly [number, number])[] = [
    [880, 0.18],
    [1320, 0.06],
];
export const ALERT_SECONDS = 0.3;

/** Play the alert chime once. Never throws. */
export function playAlertSound(): void {
    try {
        const ac = audioContext();
        if (ac === null) return;
        if (ac.state === "suspended") void ac.resume();
        const t0 = ac.currentTime;
        for (const [freq, peak] of ALERT_PARTIALS) {
            const osc = ac.createOscillator();
            const gain = ac.createGain();
            osc.type = "sine";
            osc.frequency.setValueAtTime(freq, t0);
            gain.gain.setValueAtTime(0.0001, t0);
            gain.gain.exponentialRampToValueAtTime(peak, t0 + 0.01);
            gain.gain.exponentialRampToValueAtTime(0.0001, t0 + ALERT_SECONDS);
            osc.connect(gain);
            gain.connect(ac.destination);
            osc.start(t0);
            osc.stop(t0 + ALERT_SECONDS + 0.02);
        }
    } catch {
        // Sound is a courtesy: a failure here must never block the popup.
    }
}

/** Forget the shared AudioContext (tests). */
export function resetAlertSound(): void {
    ctx = null;
}
