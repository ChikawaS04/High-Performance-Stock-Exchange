/**
 * The terminal's one standard dropdown (DESIGN_SYSTEM.md §11.5), shared by every
 * picker: the order-entry TIF, Type, Qty and Display fields, the book reader's
 * depth selector and the blotter's status filter. Moved out of OrderEntry.tsx in
 * the UI polish pass (D8) so a panel other than the ticket never depends on
 * `.order-entry__*` classes; every class here is the neutral `field-menu__*`.
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
 * an inner scroll container counts too) and on window resize. When there is not
 * enough room below and more above, it opens upward.
 *
 * Test ids: `${testId}-trigger` (single-select), `${testId}-input` (editable) and
 * `${testId}-opt-${value}` per option, unchanged from the order-entry original.
 */

import { useLayoutEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";
import { createPortal } from "react-dom";

export interface MenuOption {
    readonly value: string;
    readonly label: string;
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
    /**
     * Compact variant for a panel header band (book reader depth, blotter filter): a
     * slimmer trigger, and a list aligned to the trigger's right edge.
     */
    readonly compact?: boolean;
    /** Extra class on the root, for panel placement. */
    readonly className?: string;
    readonly onSelect: (value: string) => void;
    readonly onInput?: (text: string) => void;
}

/** The list's max-height in CSS (164px) plus its 2px gap from the trigger. */
const LIST_ROOM = 166;

/** Fixed position for the portalled list, from the anchor's viewport rect. */
function placeList(anchor: Element, alignEnd: boolean): CSSProperties {
    const r = anchor.getBoundingClientRect();
    const vh = window.innerHeight;
    const below = vh - r.bottom;
    const openUp = below < LIST_ROOM && r.top > below;
    const vertical: CSSProperties = openUp ? { bottom: vh - r.top + 2 } : { top: r.bottom + 2 };
    const horizontal: CSSProperties = alignEnd
        ? { right: window.innerWidth - r.right }
        : { left: r.left };
    return { position: "fixed", minWidth: r.width, ...vertical, ...horizontal };
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
    className,
    onSelect,
    onInput,
}: FieldMenuProps) {
    const [open, setOpen] = useState(false);
    const [listStyle, setListStyle] = useState<CSSProperties>({ position: "fixed" });
    const anchorRef = useRef<HTMLDivElement>(null);
    const close = (): void => setOpen(false);

    const showList = open && !disabled;

    // Keep the portalled list on its trigger while open.
    useLayoutEffect(() => {
        if (!showList) return;
        const place = (): void => {
            if (anchorRef.current !== null) setListStyle(placeList(anchorRef.current, compact));
        };
        place();
        window.addEventListener("scroll", place, true);
        window.addEventListener("resize", place);
        return () => {
            window.removeEventListener("scroll", place, true);
            window.removeEventListener("resize", place);
        };
    }, [showList, compact]);

    const optClass = `field-menu__opt${numeric ? " field-menu__opt--num" : ""}`;
    const rootClass = ["field-menu", compact ? "field-menu--compact" : "", className ?? ""]
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
                    <span className="field-menu__trigger-val">{triggerLabel ?? value}</span>
                </button>
            )}
            <span className="field-menu__caret term-caret" aria-hidden="true">▾</span>
            {showList
                ? createPortal(
                      <ul
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
                                  className={optClass}
                                  data-testid={`${testId}-opt-${o.value}`}
                                  onMouseDown={(e) => {
                                      e.preventDefault();
                                      onSelect(o.value);
                                      close();
                                  }}
                              >
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
