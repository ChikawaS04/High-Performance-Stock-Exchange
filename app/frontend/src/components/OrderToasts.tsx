/**
 * Order notification toasts (UI polish D4, DESIGN_SYSTEM.md §12). A fixed stack at
 * the bottom right, mounted once in App so it shows on every page.
 *
 * Reads the reducer's orderNotices and toasts each notice it has not seen. The
 * "seen" marker starts at the newest id present at mount, so state that already
 * exists when the stack mounts raises nothing; only notices raised by live frames
 * afterwards are shown. Queue logic (cap, dismiss, expire) is the pure toastQueue;
 * this component owns only the timers.
 *
 * Behaviour: newest at the bottom, at most TOAST_CAP visible, each auto-dismisses
 * after TOAST_TTL_MS, hovering a toast pauses its timer, and the exit closes it.
 * The stack is role="status" aria-live="polite" and never takes focus: the exit is
 * out of the tab order and does not focus on mouse down.
 */

import { useEffect, useReducer, useRef } from "react";

import { TOAST_TTL_MS, toastQueue } from "../notices";
import type { OrderNotice } from "../notices";

export interface OrderToastsProps {
    readonly notices: readonly OrderNotice[];
}

function newestId(notices: readonly OrderNotice[]): number {
    return notices.length > 0 ? notices[notices.length - 1].id : 0;
}

export function OrderToasts({ notices }: OrderToastsProps) {
    const [toasts, dispatch] = useReducer(toastQueue, []);
    const lastSeen = useRef(newestId(notices));

    useEffect(() => {
        for (const n of notices) {
            if (n.id > lastSeen.current) {
                dispatch({ type: "add", notice: n });
            }
        }
        lastSeen.current = Math.max(lastSeen.current, newestId(notices));
    }, [notices]);

    return (
        <div className="order-toasts" role="status" aria-live="polite" data-testid="order-toasts">
            {toasts.map((t) => (
                <Toast
                    key={t.id}
                    notice={t}
                    onExpire={() => dispatch({ type: "expire", id: t.id })}
                    onDismiss={() => dispatch({ type: "dismiss", id: t.id })}
                />
            ))}
        </div>
    );
}

interface ToastProps {
    readonly notice: OrderNotice;
    readonly onExpire: () => void;
    readonly onDismiss: () => void;
}

function Toast({ notice, onExpire, onDismiss }: ToastProps) {
    // Remaining lifetime and when the current running stretch began. Kept in refs so
    // pausing and resuming never re-renders the toast.
    const remaining = useRef(TOAST_TTL_MS);
    const startedAt = useRef(0);
    const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
    const expireRef = useRef(onExpire);
    expireRef.current = onExpire;

    const start = (): void => {
        startedAt.current = Date.now();
        timer.current = setTimeout(() => expireRef.current(), remaining.current);
    };
    const pause = (): void => {
        if (timer.current === null) return;
        clearTimeout(timer.current);
        timer.current = null;
        remaining.current = Math.max(0, remaining.current - (Date.now() - startedAt.current));
    };

    useEffect(() => {
        start();
        return () => {
            if (timer.current !== null) clearTimeout(timer.current);
            timer.current = null;
        };
        // Mount only: the timer runs from when the toast appears.
    }, []);

    const sideClass = notice.side === "BUY" ? "order-toast__side--buy" : "order-toast__side--sell";

    return (
        <div
            className={`order-toast order-toast--${notice.kind}`}
            data-testid="order-toast"
            data-kind={notice.kind}
            onMouseEnter={pause}
            onMouseLeave={() => {
                if (timer.current === null) start();
            }}
        >
            <div className="order-toast__head">
                <span className="order-toast__title">{notice.title}</span>
                <button
                    type="button"
                    className="order-toast__x"
                    aria-label="Dismiss notification"
                    tabIndex={-1}
                    data-testid="order-toast-close"
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={onDismiss}
                >
                    {"×"}
                </button>
            </div>
            <div className="order-toast__body">
                <span className={`order-toast__side ${sideClass}`}>{notice.side}</span>{" "}
                <span className="order-toast__detail">{notice.detail}</span>
            </div>
        </div>
    );
}
