/**
 * The terminal's floating window shell (DESIGN_SYSTEM.md §14), shared by the
 * maximized depth chart and the floating price chart. It replaces the drag code
 * each overlay used to carry.
 *
 * - Dragging the header (any element matching `dragSelector`) moves the window.
 *   Interactive controls inside it (buttons, settings popups) never start a drag.
 * - Dragging an edge or corner resizes it. The handles are thin invisible strips on
 *   the border with the matching resize cursor, plus a subtle grip mark in the
 *   bottom-right corner. One gesture is either a move or a resize, never both.
 * - Pointer events with pointer capture, so a fast drag that leaves the window keeps
 *   going; text selection is suppressed for the length of a gesture.
 * - Geometry is clamped by the pure floatingWindow module: at least MIN_W x MIN_H,
 *   never past the viewport, header always on screen. A browser resize clamps the
 *   window back inside.
 * - Size and position persist per window (usePersistentSetting, `storageKey`),
 *   validated and clamped to the current viewport on restore; a missing or invalid
 *   value falls back to the nominal default, centred. Whether the window is open is
 *   the parent's business and is not persisted.
 */

import { useEffect, useRef, useState } from "react";
import type { PointerEvent as ReactPointerEvent, ReactNode } from "react";

import { EDGES, EDGE_CURSOR, clampRect, isRect, moveRect, resizeRect, restoreRect } from "../floatingWindow";
import type { Edge, Rect, Size } from "../floatingWindow";
import { usePersistentSetting } from "../settings/usePersistentSetting";

export interface FloatingWindowProps {
    readonly className: string;
    readonly testId: string;
    readonly ariaLabel: string;
    /** Selector for the drag grip (the window's header band). */
    readonly dragSelector: string;
    /** Persisted geometry key, `hpse.v1.<window>.geometry`. */
    readonly storageKey: string;
    /** Default size before anything is saved, capped to the viewport. */
    readonly nominal: Size;
    readonly children: ReactNode;
}

/** Elements inside the grip that must never start a drag. */
const NO_DRAG = "button, input, .settings-pop, .settings-pop__backdrop";

const BUSY_CLASS = "floating-window--busy";

function viewport(): Size {
    return { w: window.innerWidth, h: window.innerHeight };
}

type Gesture =
    | { readonly kind: "move"; readonly x0: number; readonly y0: number; readonly start: Rect; readonly pointerId: number }
    | {
          readonly kind: "resize";
          readonly edge: Edge;
          readonly x0: number;
          readonly y0: number;
          readonly start: Rect;
          readonly pointerId: number;
      };

export function FloatingWindow({
    className,
    testId,
    ariaLabel,
    dragSelector,
    storageKey,
    nominal,
    children,
}: FloatingWindowProps) {
    const [saved, setSaved] = usePersistentSetting<Rect | null>(storageKey, null, (v): v is Rect | null =>
        v === null || isRect(v),
    );
    // The live rect: restored once on mount, updated every pointer move, saved when
    // a gesture ends.
    const [rect, setRect] = useState<Rect>(() => restoreRect(saved, nominal, viewport()));
    const rectRef = useRef(rect);
    rectRef.current = rect;
    const gesture = useRef<Gesture | null>(null);

    // A browser resize pulls the window back inside the new viewport.
    useEffect(() => {
        const onResize = (): void => setRect((r) => clampRect(r, viewport()));
        window.addEventListener("resize", onResize);
        return () => window.removeEventListener("resize", onResize);
    }, []);

    // Never leave the page unselectable if the window unmounts mid-gesture.
    useEffect(() => () => document.body.classList.remove(BUSY_CLASS), []);

    const begin = (e: ReactPointerEvent<HTMLDivElement>, g: Gesture): void => {
        gesture.current = g;
        document.body.classList.add(BUSY_CLASS);
        const el = e.currentTarget;
        try {
            el.setPointerCapture(e.pointerId);
        } catch {
            // No capture support (or an inactive pointer): the gesture still works
            // while the pointer stays over the window.
        }
        e.preventDefault();
    };

    const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>): void => {
        if (e.button !== 0) return;
        const target = e.target as Element;
        const handle = target.closest("[data-edge]");
        if (handle !== null && e.currentTarget.contains(handle)) {
            const edge = handle.getAttribute("data-edge") as Edge;
            begin(e, { kind: "resize", edge, x0: e.clientX, y0: e.clientY, start: rectRef.current, pointerId: e.pointerId });
            return;
        }
        if (target.closest(dragSelector) === null || target.closest(NO_DRAG) !== null) return;
        begin(e, { kind: "move", x0: e.clientX, y0: e.clientY, start: rectRef.current, pointerId: e.pointerId });
    };

    const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>): void => {
        const g = gesture.current;
        if (g === null || e.pointerId !== g.pointerId) return;
        const dx = e.clientX - g.x0;
        const dy = e.clientY - g.y0;
        const next =
            g.kind === "move"
                ? moveRect(g.start, dx, dy, viewport())
                : resizeRect(g.start, g.edge, dx, dy, viewport());
        setRect(next);
    };

    const end = (e: ReactPointerEvent<HTMLDivElement>): void => {
        const g = gesture.current;
        if (g === null || e.pointerId !== g.pointerId) return;
        gesture.current = null;
        document.body.classList.remove(BUSY_CLASS);
        const el = e.currentTarget;
        try {
            if (el.hasPointerCapture(e.pointerId)) el.releasePointerCapture(e.pointerId);
        } catch {
            // Capture already gone.
        }
        setSaved(rectRef.current);
    };

    return (
        <div
            className={`floating-window ${className}`}
            role="dialog"
            aria-label={ariaLabel}
            data-testid={testId}
            style={{ left: rect.x, top: rect.y, width: rect.w, height: rect.h }}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={end}
            onPointerCancel={end}
        >
            {children}
            {EDGES.map((edge) => (
                <div
                    key={edge}
                    className={`floating-window__handle floating-window__handle--${edge}`}
                    data-edge={edge}
                    data-testid={`${testId}-resize-${edge}`}
                    style={{ cursor: EDGE_CURSOR[edge] }}
                    aria-hidden="true"
                />
            ))}
            <svg className="floating-window__grip" viewBox="0 0 10 10" aria-hidden="true">
                <path d="M9 3 L3 9 M9 6 L6 9" />
            </svg>
        </div>
    );
}
