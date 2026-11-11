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
 * Test ids: `${testId}-trigger` (single-select), `${testId}-input` (editable) and
 * `${testId}-opt-${value}` per option, unchanged from the order-entry original.
 */

import { useState } from "react";

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
    /** Extra class on the root, for a panel-specific size (e.g. `field-menu--compact`). */
    readonly className?: string;
    readonly onSelect: (value: string) => void;
    readonly onInput?: (text: string) => void;
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
    className,
    onSelect,
    onInput,
}: FieldMenuProps) {
    const [open, setOpen] = useState(false);
    const close = (): void => setOpen(false);
    const optClass = `field-menu__opt${numeric ? " field-menu__opt--num" : ""}`;
    const rootClass = className ? `field-menu ${className}` : "field-menu";
    return (
        <div className={rootClass}>
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
            {open && !disabled ? (
                <ul className="field-menu__menu" role="listbox" aria-label={`${ariaLabel} options`}>
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
                </ul>
            ) : null}
        </div>
    );
}
