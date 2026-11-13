/**
 * The terminal's one standard dropdown (DESIGN_SYSTEM.md §11.5), shared by every
 * picker: the order-entry TIF, Type, Qty and Display fields, the book reader's
 * depth selector, the blotter's filters and the price chart band's chart type and
 * bar size. Moved out of OrderEntry.tsx in the UI polish pass (D8) so a panel other
 * than the ticket never depends on `.order-entry__*` classes; every class here is
 * the neutral `field-menu__*`.
 *
 * One white scroll pick-list (no visible scrollbar) opens from either a white
 * button trigger (single-select) or a sunken editable input (combobox with manual
 * entry). Selecting is onMouseDown + preventDefault so the trigger or input keeps
 * focus; blur and Escape close. The caret is the standard dropdown caret
 * (`.term-caret`, DESIGN_SYSTEM.md §11.6).
 *
 * The open list is rendered in a portal on document.body, fixed-positioned to the
 * trigger (UI polish conformance, §11.5 "the list must escape its card"). A host
 * panel's overflow or height can therefore never clip it, and the panel keeps its
 * own scrolling. The position follows the trigger on any scroll (capture phase, so
 * an inner scroll container counts too) and on window resize.
 *
 * Proportions (Charts follow-up, decision 2): the list sizes to its content, at
 * least the trigger's width, and opens under the trigger with the left edges
 * aligned; a list that would run past its host panel's right edge aligns its right
 * edge to the trigger's instead (menuPlacement.placeList). An option may carry an
 * icon (inline SVG, aria-hidden) before its label; the trigger then shows the
 * selected option's icon too, and `iconOnly` drops the trigger's text (the
 * aria-label still names the field).
 *
 * Test ids: `${testId}-trigger` (single-select), `${testId}-input` (editable) and
 * `${testId}-opt-${value}` per option, unchanged from the order-entry original.
 */

import { useLayoutEffect, useRef, useState } from "react";
import type { CSSProperties, ReactNode, RefObject } from "react";
import { createPortal } from "react-dom";

import { MENU_BOUNDARY, placeList } from "../menuPlacement";

export interface MenuOption {
    readonly value: string;
    readonly label: string;
    /** Optional icon drawn before the label (and in the trigger when selected). */
    readonly icon?: ReactNode;
}

export interface FieldMenuProps {
    readonly testId: string;
    readonly ariaLabel: string;
    readonly options: readonly MenuOption[];
    readonly value: string;
    readonly editable?: boolean;
    readonly numeric?: boolean;
    readonly placeholder?: string;
    readonly inputMode?: "numeric" | "decimal";
    readonly disabled?: boolean;
    readonly triggerLabel?: string;
    /** Compact variant for a panel header band: a slimmer trigger. */
    readonly compact?: boolean;
    /** The trigger shows only the selected option's icon (its aria-label names the field). */
    readonly iconOnly?: boolean;
    /** Extra class on the root, for panel placement. */
    readonly className?: string;
    readonly onSelect: (value: string) => void;
    readonly onInput?: (text: string) => void;
}

/**
 * Keep a portalled list placed on its anchor while open: measured once the list is
 * in the DOM (so its content width counts), then on any scroll and on resize.
 */
export function useListPlacement(
    open: boolean,
    anchorRef: RefObject<Element | null>,
    listRef: RefObject<HTMLElement | null>,
): CSSProperties {
    const [style, setStyle] = useState<CSSProperties>({ position: "fixed" });
    useLayoutEffect(() => {
        if (!open) return;
        const place = (): void => {
            const anchor = anchorRef.current;
            if (anchor === null) return;
            const r = anchor.getBoundingClientRect();
            const host = anchor.closest(MENU_BOUNDARY);
            const boundaryRight = host !== null ? host.getBoundingClientRect().right : window.innerWidth;
            const listWidth = listRef.current?.offsetWidth ?? 0;
            setStyle(placeList(r, listWidth, boundaryRight || window.innerWidth, window.innerWidth, window.innerHeight));
        };
        place();
        window.addEventListener("scroll", place, true);
        window.addEventListener("resize", place);
        return () => {
            window.removeEventListener("scroll", place, true);
            window.removeEventListener("resize", place);
        };
    }, [open, anchorRef, listRef]);
    return style;
}

export function FieldMenu({
    testId,
    ariaLabel,
    options,
    value,
    editable = false,
    numeric = false,
    placeholder,
    inputMode,
    disabled = false,
    triggerLabel,
    compact = false,
    iconOnly = false,
    className,
    onSelect,
    onInput,
}: FieldMenuProps) {
    const [open, setOpen] = useState(false);
    const anchorRef = useRef<HTMLDivElement>(null);
    const listRef = useRef<HTMLUListElement>(null);
    const close = (): void => setOpen(false);

    const showList = open && !disabled;
    const listStyle = useListPlacement(showList, anchorRef, listRef);

    const selected = options.find((o) => o.value === value);
    const triggerIcon = selected?.icon;

    const optClass = `field-menu__opt${numeric ? " field-menu__opt--num" : ""}`;
    const rootClass = [
        "field-menu",
        compact ? "field-menu--compact" : "",
        iconOnly ? "field-menu--icon-only" : "",
        className ?? "",
    ]
        .filter((c) => c !== "")
        .join(" ");
    const listClass = `field-menu__menu field-menu__menu--portal${compact ? " field-menu__menu--compact" : ""}`;

    return (
        <div className={rootClass} ref={anchorRef}>
            {editable ? (
                <input
                    className="field-menu__input"
                    type="text"
                    inputMode={inputMode}
                    placeholder={placeholder}
                    value={value}
                    data-testid={`${testId}-input`}
                    aria-label={ariaLabel}
                    autoComplete="off"
                    disabled={disabled}
                    onChange={(e) => onInput?.(e.target.value)}
                    onFocus={() => setOpen(true)}
                    onClick={() => setOpen(true)}
                    onBlur={close}
                    onKeyDown={(e) => {
                        if (e.key === "Escape") close();
                    }}
                />
            ) : (
                <button
                    type="button"
                    className="field-menu__trigger"
                    data-testid={`${testId}-trigger`}
                    aria-haspopup="listbox"
                    aria-expanded={open}
                    aria-label={ariaLabel}
                    disabled={disabled}
                    onClick={() => setOpen((o) => !o)}
                    onBlur={close}
                    onKeyDown={(e) => {
                        if (e.key === "Escape") close();
                    }}
                >
                    {triggerIcon !== undefined ? <span className="field-menu__icon">{triggerIcon}</span> : null}
                    {iconOnly && triggerIcon !== undefined ? null : (
                        <span className="field-menu__trigger-val">{triggerLabel ?? value}</span>
                    )}
                </button>
            )}
            <span className="field-menu__caret term-caret" aria-hidden="true">▾</span>
            {showList
                ? createPortal(
                      <ul
                          ref={listRef}
                          className={listClass}
                          role="listbox"
                          aria-label={`${ariaLabel} options`}
                          style={listStyle}
                          // A press on the list itself (padding, scroll) must not blur
                          // the trigger, or the list would close under the pointer.
                          onMouseDown={(e) => e.preventDefault()}
                      >
                          {options.map((o) => (
                              <li
                                  key={o.value}
                                  role="option"
                                  aria-selected={value === o.value}
                                  className={o.icon !== undefined ? `${optClass} field-menu__opt--icon` : optClass}
                                  data-testid={`${testId}-opt-${o.value}`}
                                  onMouseDown={(e) => {
                                      e.preventDefault();
                                      onSelect(o.value);
                                      close();
                                  }}
                              >
                                  {o.icon !== undefined ? <span className="field-menu__icon">{o.icon}</span> : null}
                                  {o.label}
                              </li>
                          ))}
                      </ul>,
                      document.body,
                  )
                : null}
        </div>
    );
}
