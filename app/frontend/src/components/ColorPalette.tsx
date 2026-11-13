/**
 * Colour chip row and palette popup for the price chart's settings (Charts
 * follow-up, decision 6, DESIGN_SYSTEM.md §11.4).
 *
 * A colour row shows a chip: a swatch plus the hex (or "Match line" for a fill that
 * follows the line). The chip opens the palette as a small popup in the §10 chrome,
 * anchored under it: the 42-swatch grid in six columns, a Recent row of the last
 * four picks, and a Hex field (a sunken mono well) that takes #RRGGBB on Enter or
 * blur and ignores anything else. Picking a swatch or a recent colour, or entering a
 * valid hex, applies it and closes the palette. Fill colour's palette leads with a
 * Match line choice.
 *
 * Test ids: the chip `${prefix}-chip`, the palette `${prefix}-palette`, swatches
 * `${prefix}-${hex}`, recent swatches `${prefix}-recent-${hex}`, the hex field
 * `${prefix}-hex`, Match line `${prefix}-line` (so the fill palette keeps the P12-R
 * ids pc-fill-line and pc-fill-<hex>, and the line palette pc-line-<hex>).
 */

import { useState } from "react";

import { PALETTE, parseHexInput } from "../chartColors";

export interface ColorRowProps {
    readonly label: string;
    readonly prefix: string;
    /** The colour on screen (#rrggbb), shown in the chip. */
    readonly hex: string;
    /** "line" when a fill follows the line colour; else the stored hex. */
    readonly value: string;
    readonly disabled: boolean;
    readonly open: boolean;
    readonly onToggle: () => void;
    readonly onClose: () => void;
    readonly recent: readonly string[];
    readonly onPick: (value: string) => void;
    /** Offer Match line first (fill colour). */
    readonly matchLine?: boolean;
    readonly rowClass: string;
}

export function ColorRow({
    label,
    prefix,
    hex,
    value,
    disabled,
    open,
    onToggle,
    onClose,
    recent,
    onPick,
    matchLine = false,
    rowClass,
}: ColorRowProps) {
    const chipText = value === "line" ? "Match line" : hex.toUpperCase();
    return (
        <div className={rowClass} data-testid={`${prefix}-row`}>
            <span className="settings-pop__name">{label}</span>
            <span className="color-chip__anchor">
                <button
                    type="button"
                    className="color-chip"
                    aria-haspopup="dialog"
                    aria-expanded={open}
                    aria-label={`${label}: ${chipText}`}
                    disabled={disabled}
                    data-testid={`${prefix}-chip`}
                    onClick={onToggle}
                >
                    <i className="color-chip__swatch" style={{ background: hex }} aria-hidden="true" />
                    <span className="color-chip__text">{chipText}</span>
                    <span className="color-chip__caret" aria-hidden="true">
                        {"▾"}
                    </span>
                </button>
                {open && !disabled ? (
                    <ColorPalette
                        title={label}
                        prefix={prefix}
                        value={value}
                        hex={hex}
                        recent={recent}
                        matchLine={matchLine}
                        onPick={(v) => {
                            onPick(v);
                            onClose();
                        }}
                        onClose={onClose}
                    />
                ) : null}
            </span>
        </div>
    );
}

interface ColorPaletteProps {
    readonly title: string;
    readonly prefix: string;
    readonly value: string;
    readonly hex: string;
    readonly recent: readonly string[];
    readonly matchLine: boolean;
    readonly onPick: (value: string) => void;
    readonly onClose: () => void;
}

function ColorPalette({ title, prefix, value, hex, recent, matchLine, onPick, onClose }: ColorPaletteProps) {
    const [text, setText] = useState(hex.toUpperCase());
    const commit = (): void => {
        const parsed = parseHexInput(text);
        if (parsed !== null && parsed !== value) onPick(parsed);
    };

    return (
        <div className="color-pop" role="dialog" aria-label={`${title} colour`} data-testid={`${prefix}-palette`}>
            <div className="settings-pop__head color-pop__head">
                <span className="settings-pop__title">{title}</span>
                <button
                    type="button"
                    className="settings-pop__x"
                    aria-label="Close"
                    data-testid={`${prefix}-palette-close`}
                    onClick={onClose}
                >
                    {"×"}
                </button>
            </div>
            {matchLine ? (
                <button
                    type="button"
                    role="radio"
                    aria-checked={value === "line"}
                    className={value === "line" ? "color-pop__match color-pop__match--on" : "color-pop__match"}
                    data-testid={`${prefix}-line`}
                    onClick={() => onPick("line")}
                >
                    Match line
                </button>
            ) : null}
            <div className="color-pop__grid" role="radiogroup" aria-label={`${title} palette`}>
                {PALETTE.map((c) => (
                    <button
                        key={c}
                        type="button"
                        role="radio"
                        aria-checked={value === c}
                        aria-label={c}
                        title={c.toUpperCase()}
                        className={value === c ? "color-pop__sw color-pop__sw--on" : "color-pop__sw"}
                        style={{ background: c }}
                        data-testid={`${prefix}-${c}`}
                        onClick={() => onPick(c)}
                    />
                ))}
            </div>
            <div className="settings-pop__note color-pop__label">Recent</div>
            <div className="color-pop__recent" data-testid={`${prefix}-recent`}>
                {recent.map((c) => (
                    <button
                        key={c}
                        type="button"
                        aria-label={`Recent ${c}`}
                        title={c.toUpperCase()}
                        className="color-pop__sw"
                        style={{ background: c }}
                        data-testid={`${prefix}-recent-${c}`}
                        onClick={() => onPick(c)}
                    />
                ))}
            </div>
            <div className="color-pop__hexrow">
                <label className="settings-pop__note color-pop__label" htmlFor={`${prefix}-hex-input`}>
                    Hex
                </label>
                <input
                    id={`${prefix}-hex-input`}
                    className="color-pop__hex"
                    type="text"
                    spellCheck={false}
                    autoComplete="off"
                    maxLength={7}
                    value={text}
                    data-testid={`${prefix}-hex`}
                    onChange={(e) => setText(e.target.value)}
                    onBlur={commit}
                    onKeyDown={(e) => {
                        if (e.key === "Enter") commit();
                        if (e.key === "Escape") onClose();
                    }}
                />
            </div>
        </div>
    );
}
