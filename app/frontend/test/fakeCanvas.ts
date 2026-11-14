/**
 * A recording fake 2D context for the heatmap renderer tests (P20-0 F5). jsdom has no
 * canvas, and no dependency is added: this implements only the calls the renderer
 * makes and logs each one, with property writes logged as "set <name>".
 */

import { vi } from "vitest";

export type Call = readonly [name: string, ...args: unknown[]];

export class FakeContext {
    readonly calls: Call[] = [];
    private props: Record<string, unknown> = {};

    constructor(readonly canvas: HTMLCanvasElement) {
        const logged = ["fillStyle", "strokeStyle", "lineWidth", "globalAlpha", "imageSmoothingEnabled", "lineJoin", "lineCap"];
        for (const name of logged) {
            Object.defineProperty(this, name, {
                get: () => this.props[name],
                set: (v: unknown) => {
                    this.props[name] = v;
                    this.calls.push([`set ${name}`, v]);
                },
            });
        }
    }

    private log(name: string, args: unknown[]): void {
        this.calls.push([name, ...args]);
    }

    createImageData(width: number, height: number): ImageData {
        this.log("createImageData", [width, height]);
        return { width, height, data: new Uint8ClampedArray(width * height * 4), colorSpace: "srgb" } as ImageData;
    }
    putImageData(...a: unknown[]): void { this.log("putImageData", a.slice(1)); }
    drawImage(...a: unknown[]): void { this.log("drawImage", a.slice(1)); }
    setTransform(...a: unknown[]): void { this.log("setTransform", a); }
    fillRect(...a: unknown[]): void { this.log("fillRect", a); }
    clearRect(...a: unknown[]): void { this.log("clearRect", a); }
    beginPath(): void { this.log("beginPath", []); }
    closePath(): void { this.log("closePath", []); }
    moveTo(...a: unknown[]): void { this.log("moveTo", a); }
    lineTo(...a: unknown[]): void { this.log("lineTo", a); }
    arc(...a: unknown[]): void { this.log("arc", a); }
    stroke(): void { this.log("stroke", []); }
    fill(): void { this.log("fill", []); }
    save(): void { this.log("save", []); }
    restore(): void { this.log("restore", []); }
    setLineDash(...a: unknown[]): void { this.log("setLineDash", a); }

    /** Names of the calls, in order. */
    names(): string[] {
        return this.calls.map((c) => c[0]);
    }
    clear(): void {
        this.calls.length = 0;
    }
}

/**
 * Route every canvas's getContext("2d") to a FakeContext, one per canvas. Returns a
 * lookup and the list of contexts in creation order. Restore with vi.restoreAllMocks.
 */
export function installFakeCanvas(): { readonly ctxOf: (c: HTMLCanvasElement) => FakeContext; readonly all: FakeContext[] } {
    const map = new WeakMap<HTMLCanvasElement, FakeContext>();
    const all: FakeContext[] = [];
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockImplementation(function (this: HTMLCanvasElement) {
        let ctx = map.get(this);
        if (ctx === undefined) {
            ctx = new FakeContext(this);
            map.set(this, ctx);
            all.push(ctx);
        }
        return ctx as unknown as CanvasRenderingContext2D;
    } as unknown as typeof HTMLCanvasElement.prototype.getContext);
    return {
        ctxOf: (c) => {
            const ctx = map.get(c);
            if (ctx === undefined) throw new Error("no context for canvas");
            return ctx;
        },
        all,
    };
}
