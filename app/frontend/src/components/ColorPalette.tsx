/**
 * Colour chip row and palette popup for the price chart's settings (Charts
 * follow-up, decision 6, DESIGN_SYSTEM.md §11.4).
 *
 * A colour row shows a chip: a swatch plus the hex (or "Match line" for a fill that
 * follows the line). The chip opens the palette as a small popup in the §10 chrome,
 * anchored under it: the 42-swatch grid in six columns, a Recent row of the last
 * four picks, and a Hex field (a sunken number-font well) that takes #RRGGBB on Enter or
 * blur and ignores anything else. Picking a swatch or a recent colour, or entering a
 * valid hex, applies it and closes the palette. Fill colour's palette leads with a
 * Match line choice.
 *
 * Test ids: the chip `${prefix}-chip`, the palette `${prefix}-palette`, swatches
 * `${prefix}-${hex}`, recent swatches `${prefix}-recent-${hex}`, the hex field
 * `${prefix}-hex`, Match line `${prefix}-line` (so the fill palette keeps the P12-R
 * ids pc-fill-line and pc-fill-<hex>, and the line palette pc-line-<hex>).
 *
 * Inside the chart settings view (a scrolling pane) the palette would be clipped, so
 * the view provides a layer element (PaletteLayerContext) outside the pane. With a
 * layer the palette is portalled into it and placed from the chip's rect: under the
 * chip, right-aligned to it, or above it when the space below inside the viewport is
 * short. It follows the chip on any scroll (capture phase, so the pane counts) and on
 * resize. Without a layer it keeps the plain absolute placement under the chip.
 */

import { createContext, useContext, useLayoutEffect, useRef, useState } from "react";
import type { CSSProperties, RefObject } from "react";
import { createPortal } from "react-dom";

import { PALETTE, parseHexInput } from "../chartColors";

/** The element palettes portal into, provided by the chart settings view. */
export const PaletteLayerContext = createContext<HTMLElement | null>(null);

/** Gap between the chip and the palette, and the viewport margin kept below it. */
const PALETTE_GAP = 4;
const VIEWPORT_MARGIN = 8;

/**
 * The palette's position inside the layer: under the chip and right-aligned to it, or
 * above when it would run past the viewport's bottom and there is more room above.
 */
export function placePalette(
    chip: { readonly top: number; readonly bottom: number; readonly right: number },
    layer: { readonly top: number; readonly left: number },
    palette: { readonly width: number; readonly height: number },
    viewportH: number,
): { readonly top: number; readonly left: number } {
    const below = chip.bottom + PALETTE_GAP;
    const fitsBelow = below + palette.height <= viewportH - VIEWPORT_MARGIN;
    const roomAbove = chip.top - PALETTE_GAP - VIEWPORT_MARGIN;
    const roomBelow = viewportH - VIEWPORT_MARGIN - below;
    const top = fitsBelow || roomBelow >= roomAbove ? below : chip.top - PALETTE_GAP - palette.height;
    return { top: top - layer.top, left: chip.right - palette.width - layer.left };
}

function usePalettePlacement(
    active: boolean,
    layer: HTMLElement | null,
    chipRef: RefObject<HTMLElement | null>,
    popRef: RefObject<HTMLElement | null>,
): CSSProperties | undefined {
    const [style, setStyle] = useState<CSSProperties | undefined>(undefined);
    useLayoutEffect(() => {
        if (!active || layer === null) return;
        const place = (): void => {
            const chip = chipRef.current;
            const pop = popRef.current;
            if (chip === null || pop === null) return;
            const at = placePalette(
                chip.getBoundingClientRect(),
                layer.getBoundingClientRect(),
                { width: pop.offsetWidth, height: pop.offsetHeight },
                window.innerHeight,
            );
            setStyle({ position: "absolute", top: at.top, left: at.left, right: "auto" });
        };
        place();
        window.addEventListener("scroll", place, true);
        window.addEventListener("resize", place);
        return () => {
            window.removeEventListener("scroll", place, true);
            window.removeEventListener("resize", place);
        };
    }, [active, layer, chipRef, popRef]);
    return style;
}

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
    const layer = useContext(PaletteLayerContext);
    const chipRef = useRef<HTMLButtonElement>(null);
    const popRef = useRef<HTMLDivElement>(null);
    const showPalette = open && !disabled;
    const placed = usePalettePlacement(showPalette, layer, chipRef, popRef);
    const palette = showPalette ? (
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
            popRef={popRef}
            // Hidden until measured, so it never flashes at the layer's corner.
            style={layer === null ? undefined : (placed ?? { position: "absolute", visibility: "hidden" })}
        />
    ) : null;
    return (
        <div className={rowClass} data-testid={`${prefix}-row`}>
            <span className="settings-pop__name">{label}</span>
            <span className="color-chip__anchor">
                <button
                    ref={chipRef}
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
                {palette !== null && layer !== null ? createPortal(palette, layer) : palette}
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
    readonly popRef: RefObject<HTMLDivElement | null>;
    readonly style?: CSSProperties;
}

function ColorPalette({ title, prefix, value, hex, recent, matchLine, onPick, onClose, popRef, style }: ColorPaletteProps) {
    const [text, setText] = useState(hex.toUpperCase());
    const commit = (): void => {
        const parsed = parseHexInput(text);
        if (parsed !== null && parsed !== value) onPick(parsed);
    };

    return (
        <div
            ref={popRef}
            className="color-pop"
            role="dialog"
            aria-label={`${title} colour`}
            data-testid={`${prefix}-palette`}
            style={style}
        >
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
