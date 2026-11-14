/**
 * The heatmap's canvas (P20-3, P20 D3). A thin component around HeatmapRenderer: it
 * owns the <canvas>, and paints on requestAnimationFrame only when something it draws
 * changed (the recorder version, the window, the grid, the contrast, the overlay or
 * the size). A pointer move re-renders PriceChart but none of these, so the canvas is
 * untouched by hover; the crosshair and readout stay in the SVG above it.
 *
 * Hidden tab: no paint is scheduled while document.hidden. The change is remembered
 * and painted once when the tab is visible again. A window resize (which a browser
 * zoom or a move to another monitor's device pixel ratio fires) schedules a paint,
 * and the renderer resizes and repaints in full when the ratio changed.
 *
 * Where no 2D context exists (jsdom without the test fake) the canvas mounts and
 * nothing paints.
 */

import { memo, useCallback, useEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";

import { HeatmapRenderer } from "../heatmap/renderer";
import type { OverlayDraw, PaintStats } from "../heatmap/renderer";
import type { DepthRing } from "../heatmap/depthRing";
import type { HeatContrast, HeatView, RowGrid } from "../heatmap/layout";

export interface HeatmapLayerProps {
    readonly ring: DepthRing;
    /** The recorder's version: a change means the ring changed. */
    readonly version: number;
    readonly view: HeatView;
    readonly grid: RowGrid;
    readonly contrast: HeatContrast;
    readonly overlay?: OverlayDraw;
    readonly style?: CSSProperties;
    /** Called after each paint with its stats and duration in ms (P20-6 instrumentation, tests). */
    readonly onPaint?: (stats: PaintStats, ms: number) => void;
}

function HeatmapLayerImpl({ ring, version, view, grid, contrast, overlay, style, onPaint }: HeatmapLayerProps) {
    const [canvas, setCanvas] = useState<HTMLCanvasElement | null>(null);
    const rendererRef = useRef<HeatmapRenderer | null | "unavailable">(null);
    const frameRef = useRef<number | null>(null);
    const pendingRef = useRef(false);
    const propsRef = useRef({ ring, view, grid, contrast, overlay, onPaint });
    propsRef.current = { ring, view, grid, contrast, overlay, onPaint };

    const paint = useCallback((): void => {
        frameRef.current = null;
        if (canvas === null) return;
        if (document.hidden) {
            pendingRef.current = true;
            return;
        }
        if (rendererRef.current === null) {
            try {
                rendererRef.current = new HeatmapRenderer(canvas);
            } catch {
                rendererRef.current = "unavailable";
            }
        }
        const r = rendererRef.current;
        if (r === "unavailable") return;
        const p = propsRef.current;
        const t0 = performance.now();
        r.resize(p.view.plotW, p.view.plotH, window.devicePixelRatio || 1);
        const stats = r.render(p.ring, p.view, p.grid, p.contrast, p.overlay);
        p.onPaint?.(stats, performance.now() - t0);
    }, [canvas]);

    const schedule = useCallback((): void => {
        if (frameRef.current !== null) return;
        if (document.hidden) {
            pendingRef.current = true;
            return;
        }
        frameRef.current = requestAnimationFrame(paint);
    }, [paint]);

    useEffect(() => {
        schedule();
    }, [schedule, version, view.lo, view.hi, view.plotW, view.plotH, grid, contrast, overlay]);

    useEffect(() => {
        const onVisible = (): void => {
            if (!document.hidden && pendingRef.current) {
                pendingRef.current = false;
                schedule();
            }
        };
        const onResize = (): void => schedule();
        document.addEventListener("visibilitychange", onVisible);
        window.addEventListener("resize", onResize);
        return () => {
            document.removeEventListener("visibilitychange", onVisible);
            window.removeEventListener("resize", onResize);
            if (frameRef.current !== null) cancelAnimationFrame(frameRef.current);
            frameRef.current = null;
        };
    }, [schedule]);

    return <canvas ref={setCanvas} className="heatmap__canvas" data-testid="heatmap-canvas" style={style} aria-hidden="true" />;
}

export const HeatmapLayer = memo(HeatmapLayerImpl);
