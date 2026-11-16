/**
 * Front-to-back order of the Trading page's floating windows (DESIGN_SYSTEM.md §14).
 *
 * Pure: a stack is a back-to-front array of window ids, and every helper returns a
 * new array only when the order changes. Raising the window already in front
 * returns the same array, so a React state update bails out and nothing re-renders.
 * The order is never persisted (§13: whether a window is open is not persisted).
 */

export type WindowId = "depth" | "price";

/** Back to front: the last id is the front window. */
export type WindowStack = readonly WindowId[];

/** The windows' z band; toasts and everything above the windows stay above it. */
export const WINDOW_Z_BASE = 200;

/** Bring `id` to the front, opening it if absent. Same array when already in front. */
export function raise(stack: WindowStack, id: WindowId): WindowStack {
    if (stack.length > 0 && stack[stack.length - 1] === id) return stack;
    return [...stack.filter((w) => w !== id), id];
}

/** Drop a closed window. Same array when it is not in the stack. */
export function remove(stack: WindowStack, id: WindowId): WindowStack {
    return stack.includes(id) ? stack.filter((w) => w !== id) : stack;
}

/** z-index for a window: the base plus its stack index. An id not yet stacked reads as front. */
export function windowZ(stack: WindowStack, id: WindowId): number {
    const i = stack.indexOf(id);
    return WINDOW_Z_BASE + (i < 0 ? stack.length : i);
}

/** True when `id` is the front window, or is not stacked yet (it is about to be). */
export function isFront(stack: WindowStack, id: WindowId): boolean {
    const i = stack.indexOf(id);
    return i < 0 || i === stack.length - 1;
}
