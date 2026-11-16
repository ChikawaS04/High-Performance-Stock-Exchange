/**
 * The terminal's one text-selection lock (DESIGN_SYSTEM.md §14, §16.10).
 *
 * While a pointer gesture runs (moving or resizing a floating window, panning the
 * price chart, dragging an order tag to reprice) nothing on the page may select. A
 * holder calls lockSelection() when its gesture starts and the returned release when
 * it ends, cancels or unmounts. The body carries NO_SELECT_CLASS while any holder is
 * active; holders are counted, so two overlapping gestures never clear each other's
 * lock. The release is idempotent, so calling it from both an end handler and an
 * unmount cleanup is safe.
 */

export const NO_SELECT_CLASS = "term-no-select";

let holders = 0;

/** Suppress page selection until the returned release is called. */
export function lockSelection(): () => void {
    holders += 1;
    document.body.classList.add(NO_SELECT_CLASS);
    let released = false;
    return () => {
        if (released) return;
        released = true;
        holders -= 1;
        if (holders <= 0) {
            holders = 0;
            document.body.classList.remove(NO_SELECT_CLASS);
        }
    };
}
