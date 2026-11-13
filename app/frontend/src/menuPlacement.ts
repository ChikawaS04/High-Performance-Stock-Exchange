/**
 * Pure placement for the white pick-list (DESIGN_SYSTEM.md §11.5), shared by
 * FieldMenu and the price chart's View menu (Charts follow-up, decision 2). No React.
 *
 * The list is fixed-positioned in a portal so no card can clip it. It sizes to its
 * content: at least the trigger's width (minWidth), otherwise as wide as its widest
 * option, never a fixed width. It opens directly under the trigger with the left
 * edges aligned; when that would run past the host panel's right edge it aligns its
 * right edge to the trigger's right edge instead. With no room below and more room
 * above, it opens upward.
 */

export interface AnchorRect {
    readonly left: number;
    readonly right: number;
    readonly top: number;
    readonly bottom: number;
    readonly width: number;
}

export interface ListPlacement {
    readonly position: "fixed";
    readonly minWidth: number;
    readonly top?: number;
    readonly bottom?: number;
    readonly left?: number;
    readonly right?: number;
}

/** The list's max-height in CSS (164px) plus its 2px gap from the trigger. */
export const LIST_ROOM = 166;

/**
 * Where the list goes, given the trigger's viewport rect, the list's own rendered
 * width (0 before it is measured), the host panel's right edge, and the viewport.
 */
export function placeList(
    anchor: AnchorRect,
    listWidth: number,
    boundaryRight: number,
    viewportW: number,
    viewportH: number,
): ListPlacement {
    const below = viewportH - anchor.bottom;
    const openUp = below < LIST_ROOM && anchor.top > below;
    const width = Math.max(listWidth, anchor.width);
    const alignRight = anchor.left + width > boundaryRight;
    return {
        position: "fixed",
        minWidth: anchor.width,
        ...(openUp ? { bottom: viewportH - anchor.top + 2 } : { top: anchor.bottom + 2 }),
        ...(alignRight ? { right: viewportW - anchor.right } : { left: anchor.left }),
    };
}

/** The selector for a list's host panel: the chart card, a terminal panel, or an explicit boundary. */
export const MENU_BOUNDARY = "[data-menu-boundary], .price-chart, .panel";
