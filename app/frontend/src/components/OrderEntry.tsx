/**
 * Manual order entry ticket (SRS §3.7), refined in P7-7. Presentational and
 * callback-driven: it owns only transient form state (side, time in force, the
 * raw price/qty strings, an error) and emits resolved, units-internal intent via
 * `onSubmit`. It never generates a clOrdId, builds a wire frame, or touches the
 * socket; that lives in one place, the App wiring over the single useOrderBook
 * instance, so clOrdId is generated in exactly one place and never derived from
 * server data.
 *
 * P7-7 adds ticket affordances WITHOUT changing what it sends (the same NEW
 * frame): bid / mid / ask reference chips, one-cent tick nudges, quantity
 * presets, and an Order ID field showing the client-assigned id the next NEW
 * will use. Every affordance resolves to integer units through format.ts; no
 * float touches the price path.
 *
 * P8-6 stripped this ticket's FIX teaching annotations (the Tag 44 / 38 / 11
 * labels, the Primitive helper, and the "auto, client-assigned" caption):
 * the working ticket shows plain labels and values, and the tag-level view
 * lives only in the FIX inspector now. Every underlying value is kept.
 *
 * P14-6 adds a time-in-force segmented control (GTC / IOC / FOK, default GTC) and
 * moves the submit seam to a single OrderIntent object (decision H), so the later
 * iceberg (P14-8) and midpoint-peg (P14-10) steps add meaning to the intent's
 * displayQty / ordType without the signature growing again. For now the ticket
 * only ever sends ordType "LIMIT".
 *
 * P14-8 gives the iceberg half meaning: an optional Display quantity field, shown
 * only for a GTC order (an iceberg is GTC-only, D8), validated locally against
 * 0 < display <= qty with display == qty normalised to 0 (hides nothing, D7). IOC
 * and FOK hide the field and always send displayQty 0, so no iceberg+IOC/FOK
 * combination can leave the ticket. The resolved displayQty rides the existing
 * intent -> maxFloor wiring (P14-6); ordType stays "LIMIT" until P14-10.
 *
 * Ticket redesign (this step) is layout-only and changes nothing the ticket
 * sends. The time-in-force and order-type controls become compact <select>
 * dropdowns (GTC default, Limit default) in place of the segmented button rows;
 * the price stepper collapses from a −/+ pair flanking the input into a single
 * up/down control on the right edge of the price field; the client-assigned
 * Order ID readout is removed from the ticket (it is display-only and lives in
 * the FIX inspector / blotter), so the clOrdIdPreview prop is gone. The side,
 * reference-chip, price, quantity and display seams and all validation are
 * unchanged, and every test id the logic relied on (tif/ordtype values via the
 * selects, nudge-up / nudge-down, the chips, presets, and the inputs) is kept.
 *
 * Controlled-price seam. The chips and nudges write the SAME uncontrolled
 * `priceInput` the user types, so there is one source of truth and the P5-4
 * validation is byte-identical. The external seam for the P7-5 depth curve
 * (wired in P7-10) is the imperative `setPrice(pricePx)` handle, which takes
 * exactly the shape `onPriceSelect` emits. The ref exposes only a price setter,
 * not state ownership, so the P5-6 discipline holds.
 */

import { forwardRef, useImperativeHandle, useState } from "react";

import { formatPrice, parsePrice, EMPTY_PRICE } from "../format";
import { NA } from "../protocol/messages";
import type { OrderIntent, OrdType, Side, TimeInForce } from "../protocol/messages";

export type ValidationResult =
    | { readonly ok: true; readonly pricePx: number; readonly qty: number }
    | { readonly ok: false; readonly reason: string };

const PRICE_REASON = "Price must be greater than 0 with at most 2 decimals";
const QTY_REASON = "Quantity must be a positive whole number";

/**
 * Pure input validation, exported for direct unit testing (mirrors P5-2's
 * `buildLadder` split). Price delegates to `parsePrice`, which mirrors the
 * backend `parsePrice` policy exactly; quantity must be a positive whole number
 * (no decimals, no sign, no exponent).
 */
export type QtyResult =
    | { readonly ok: true; readonly qty: number }
    | { readonly ok: false; readonly reason: string };

/**
 * Pure quantity validation, exported for direct unit testing. Reused by both the
 * limit path (via validateOrderInput) and the midpoint-peg path (P14-10), which
 * has no price to validate. A quantity must be a positive whole number (no
 * decimals, no sign, no exponent).
 */
export function validateQtyInput(qtyInput: string): QtyResult {
    const qtyTrimmed = qtyInput.trim();
    if (!/^\d+$/.test(qtyTrimmed)) {
        return { ok: false, reason: QTY_REASON };
    }
    const qty = Number(qtyTrimmed);
    if (!Number.isSafeInteger(qty) || qty <= 0) {
        return { ok: false, reason: QTY_REASON };
    }
    return { ok: true, qty };
}

export function validateOrderInput(priceInput: string, qtyInput: string): ValidationResult {
    const pricePx = parsePrice(priceInput);
    if (pricePx === null) {
        return { ok: false, reason: PRICE_REASON };
    }
    const qtyResult = validateQtyInput(qtyInput);
    if (!qtyResult.ok) {
        return qtyResult;
    }
    return { ok: true, pricePx, qty: qtyResult.qty };
}

/**
 * The resolved iceberg display quantity for a GTC order, or a reason it is
 * invalid. Exported for direct unit testing (mirrors validateOrderInput), kept
 * separate from it so the price/qty return shape is unchanged. Takes the
 * already-resolved integer qty, so it runs only after validateOrderInput passes.
 * Rules (D7/D8): an empty field is a plain order (display 0); any entered value
 * must be a positive whole number no greater than qty; display == qty hides
 * nothing and is normalised to 0. IOC/FOK never reach here; the caller sends
 * displayQty 0 for them.
 */
export type DisplayResult =
    | { readonly ok: true; readonly displayQty: number }
    | { readonly ok: false; readonly reason: string };

const DISPLAY_REASON = "Display must be a whole number from 1 to the order quantity";

export function validateDisplayQty(displayInput: string, qty: number): DisplayResult {
    const trimmed = displayInput.trim();
    if (trimmed === "") {
        return { ok: true, displayQty: 0 };
    }
    if (!/^\d+$/.test(trimmed)) {
        return { ok: false, reason: DISPLAY_REASON };
    }
    const display = Number(trimmed);
    if (!Number.isSafeInteger(display) || display <= 0 || display > qty) {
        return { ok: false, reason: DISPLAY_REASON };
    }
    // display == qty hides nothing, so it is a plain order, not an iceberg (D7).
    return { ok: true, displayQty: display === qty ? 0 : display };
}

/** One cent, in units of $0.0001. The integer-units primitive is the unit end to end. */
export const TICK_PX = 100;

/** Round-lot quantity presets. */
export const QTY_PRESETS = [10, 50, 100, 500] as const;

/**
 * Quantity quick-pick list for the Qty combobox: 10, 50, 100, then +40 up to 980.
 * Manual entry is still allowed; this is only the dropdown's suggestions.
 */
export const QTY_OPTIONS: readonly number[] = (() => {
    const out: number[] = [10, 50, 100];
    for (let q = 140; q <= 980; q += 40) out.push(q);
    return out;
})();

/** The time-in-force options, in the order the dropdown renders them. */
export const TIF_OPTIONS = ["GTC", "IOC", "FOK"] as const satisfies readonly TimeInForce[];

/** The order-type options, in the order the dropdown renders them. */
export const ORD_TYPE_OPTIONS = [
    { value: "LIMIT", label: "LIMIT", id: "limit" },
    { value: "PEG_MID", label: "MIDPOINT PEG", id: "mid" },
] as const satisfies readonly { value: OrdType; label: string; id: string }[];

/**
 * The mid resolved to a valid on-tick limit, integer math only, for the mid chip.
 * Returns null when the book is one-sided (chip disabled). The mid of two on-tick
 * prices is (bid + ask) / 2, an exact integer multiple of 50 units; when the
 * spread is an odd number of ticks that is a half-cent, which is off the one-cent
 * tick, so the chip snaps it UP to the next tick with integer math
 * (Math.ceil(mid / TICK_PX) * TICK_PX). It rounds toward the ask by up to half a
 * cent, stated so the chip can never populate a sub-penny price. This is
 * deliberately NOT format.ts's `midpointPx`, which stays the render-only exact
 * mid; the price path is always on the tick.
 */
export function midChipPx(bestBid: number, bestAsk: number): number | null {
    if (bestBid <= 0 || bestAsk <= 0) return null;
    const mid = (bestBid + bestAsk) / 2;
    return Math.ceil(mid / TICK_PX) * TICK_PX;
}

/**
 * Adjust a price by `steps` ticks, clamped so it can never fall below one tick.
 * Integer in, integer out, always positive: a nudge can never produce a
 * non-integer or non-positive price, and one tick is one cent (TICK_PX units).
 */
export function nudgePx(px: number, steps: number, tick: number = TICK_PX): number {
    return Math.max(tick, px + steps * tick);
}

/**
 * Apply a quantity preset without clobbering a typed value: fill only when the
 * field is empty, so a stray preset click never wipes a size the trader entered.
 */
export function applyPreset(currentQtyInput: string, preset: number): string {
    return currentQtyInput.trim() === "" ? String(preset) : currentQtyInput;
}

/** Imperative seam for the P7-5 depth curve (wired in P7-10): set the price in units. */
export interface OrderEntryHandle {
    setPrice: (pricePx: number) => void;
}

export interface OrderEntryProps {
    readonly onSubmit: (intent: OrderIntent) => void;
    /** When true (e.g. socket not open), the whole ticket is inert and visibly disabled. */
    readonly disabled?: boolean;
    /** Live best bid in units for the Bid chip; -1 (default) disables it. */
    readonly bestBidPx?: number;
    /** Live best ask in units for the Ask chip; -1 (default) disables it. */
    readonly bestAskPx?: number;
}

type MenuOption = { readonly value: string; readonly label: string };

const TIF_MENU: readonly MenuOption[] = TIF_OPTIONS.map((o) => ({ value: o, label: o }));
const ORD_TYPE_MENU: readonly MenuOption[] = ORD_TYPE_OPTIONS.map((o) => ({ value: o.value, label: o.label }));
const QTY_MENU: readonly MenuOption[] = QTY_OPTIONS.map((n) => ({ value: String(n), label: String(n) }));

interface FieldMenuProps {
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
    readonly onSelect: (value: string) => void;
    readonly onInput?: (text: string) => void;
}

/**
 * Standard dropdown / combobox for every order-entry picker (TIF, Type, Qty,
 * Display). One white scroll pick-list (no visible scrollbar) opens from either a
 * white button trigger (single-select: TIF, Type) or a sunken editable input
 * (combobox with manual entry: Qty, Display). Selecting is onMouseDown +
 * preventDefault so the trigger/input keeps focus; blur closes. See DESIGN_SYSTEM.md.
 */
function FieldMenu({
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
    onSelect,
    onInput,
}: FieldMenuProps) {
    const [open, setOpen] = useState(false);
    const close = (): void => setOpen(false);
    const optClass = `order-entry__opt${numeric ? " order-entry__opt--num" : ""}`;
    return (
        <div className="order-entry__combo">
            {editable ? (
                <input
                    className="order-entry__input"
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
                    className="order-entry__trigger"
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
                    <span className="order-entry__trigger-val">{triggerLabel ?? value}</span>
                </button>
            )}
            <span className="order-entry__caret" aria-hidden="true">▾</span>
            {open && !disabled ? (
                <ul className="order-entry__menu" role="listbox" aria-label={`${ariaLabel} options`}>
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

export const OrderEntry = forwardRef<OrderEntryHandle, OrderEntryProps>(function OrderEntry(
    { onSubmit, disabled = false, bestBidPx = -1, bestAskPx = -1 },
    ref,
) {
    const [side, setSide] = useState<Side>("BUY");
    const [tif, setTif] = useState<TimeInForce>("GTC");
    const [ordType, setOrdType] = useState<OrdType>("LIMIT");
    const [priceInput, setPriceInput] = useState("");
    const [qtyInput, setQtyInput] = useState("");
    const [displayInput, setDisplayInput] = useState("");
    const [error, setError] = useState<string | null>(null);

    // The one seam the chips, the nudges, and (via the ref) the curve all feed: it
    // writes the same priceInput the user types, so single-source-of-truth holds.
    const applyPrice = (pricePx: number): void => {
        setPriceInput(formatPrice(pricePx));
        setError(null);
    };

    useImperativeHandle(ref, () => ({ setPrice: applyPrice }), []);

    const submit = (): void => {
        if (disabled) return;
        // P14-10: a midpoint peg carries no price. Validate the quantity only, and
        // send ordType PEG_MID with the NA price sentinel and displayQty 0 (a peg is
        // non-displayed, so an iceberg display has no meaning, D8). The typed limit
        // price and display stay in state, ignored while the peg hides them, so
        // switching back to Limit restores them (the P14-8 across-toggle rule).
        if (ordType === "PEG_MID") {
            const qtyResult = validateQtyInput(qtyInput);
            if (!qtyResult.ok) {
                setError(qtyResult.reason);
                return;
            }
            setError(null);
            onSubmit({
                side,
                ordType: "PEG_MID",
                tif,
                pricePx: NA,
                qty: qtyResult.qty,
                displayQty: 0,
            });
            // Clear only the quantity; keep side, time in force, order type, and the
            // stashed limit price / display for the next order.
            setQtyInput("");
            return;
        }
        const result = validateOrderInput(priceInput, qtyInput);
        if (!result.ok) {
            setError(result.reason);
            return;
        }
        // P14-8: the Display quantity is an iceberg's visible slice, meaningful only
        // for a resting (GTC) limit order. IOC and FOK hide the field, so no display
        // is resolved or validated for them and the frame carries displayQty 0. For
        // GTC an empty field is a plain order, and display == qty normalises to 0.
        let displayQty = 0;
        if (tif === "GTC") {
            const display = validateDisplayQty(displayInput, result.qty);
            if (!display.ok) {
                setError(display.reason);
                return;
            }
            displayQty = display.displayQty;
        }
        setError(null);
        // A single resolved intent (P14-6, decision H).
        onSubmit({
            side,
            ordType: "LIMIT",
            tif,
            pricePx: result.pricePx,
            qty: result.qty,
            displayQty,
        });
        // Clear price, qty and display for the next order; keep side and time in
        // force for repeat fires.
        setPriceInput("");
        setQtyInput("");
        setDisplayInput("");
    };

    const nudge = (steps: number): void => {
        if (disabled) return;
        const current = parsePrice(priceInput);
        if (current === null) return; // empty or invalid: no base to nudge, so no-op
        applyPrice(nudgePx(current, steps));
    };

    const isPeg = ordType === "PEG_MID";
    const ordTypeLabel = ORD_TYPE_OPTIONS.find((o) => o.value === ordType)?.label ?? ordType;
    const midPx = midChipPx(bestBidPx, bestAskPx);
    // The iceberg Display slice is meaningful only for a resting (GTC) limit order
    // (D8); a peg or an IOC/FOK order hides it (P14-8).
    const showDisplay = ordType === "LIMIT" && tif === "GTC";

    return (
        <div className="order-entry">
            <div className="order-entry__header">Order entry</div>
            <div className="order-entry__body">
            <div className="order-entry__side" role="group" aria-label="Side">
                <button
                    type="button"
                    className={`order-entry__side-btn${side === "BUY" ? " order-entry__side-btn--active" : ""}`}
                    aria-pressed={side === "BUY"}
                    data-testid="side-buy"
                    disabled={disabled}
                    onClick={() => setSide("BUY")}
                >
                    BUY
                </button>
                <button
                    type="button"
                    className={`order-entry__side-btn${side === "SELL" ? " order-entry__side-btn--active" : ""}`}
                    aria-pressed={side === "SELL"}
                    data-testid="side-sell"
                    disabled={disabled}
                    onClick={() => setSide("SELL")}
                >
                    SELL
                </button>
            </div>

            <div className="order-entry__chips" role="group" aria-label="Reference prices">
                <button
                    type="button"
                    className="order-entry__chip order-entry__chip--bid"
                    data-testid="chip-bid"
                    disabled={disabled || isPeg || bestBidPx <= 0}
                    onClick={() => applyPrice(bestBidPx)}
                >
                    <span className="order-entry__chip-label">Bid</span>
                    <span className="order-entry__chip-value">
            {bestBidPx > 0 ? formatPrice(bestBidPx) : EMPTY_PRICE}
          </span>
                </button>
                <button
                    type="button"
                    className="order-entry__chip order-entry__chip--mid"
                    data-testid="chip-mid"
                    disabled={disabled || isPeg || midPx === null}
                    onClick={() => {
                        if (midPx !== null) applyPrice(midPx);
                    }}
                >
                    <span className="order-entry__chip-label">Mid</span>
                    <span className="order-entry__chip-value">
            {midPx !== null ? formatPrice(midPx) : EMPTY_PRICE}
          </span>
                </button>
                <button
                    type="button"
                    className="order-entry__chip order-entry__chip--ask"
                    data-testid="chip-ask"
                    disabled={disabled || isPeg || bestAskPx <= 0}
                    onClick={() => applyPrice(bestAskPx)}
                >
                    <span className="order-entry__chip-label">Ask</span>
                    <span className="order-entry__chip-value">
            {bestAskPx > 0 ? formatPrice(bestAskPx) : EMPTY_PRICE}
          </span>
                </button>
            </div>

            <div className="order-entry__controls">
                <div className="order-entry__control order-entry__control--tif">
                    <span className="order-entry__label">TIF</span>
                    <FieldMenu
                        testId="tif"
                        ariaLabel="Time in force"
                        options={TIF_MENU}
                        value={tif}
                        triggerLabel={tif}
                        disabled={disabled}
                        onSelect={(v) => setTif(v as TimeInForce)}
                    />
                </div>

                <div className="order-entry__control order-entry__control--ordtype">
                    <span className="order-entry__label">Type</span>
                    <FieldMenu
                        testId="ordtype"
                        ariaLabel="Order type"
                        options={ORD_TYPE_MENU}
                        value={ordType}
                        triggerLabel={ordTypeLabel}
                        disabled={disabled}
                        onSelect={(v) => setOrdType(v as OrdType)}
                    />
                </div>

                <div className="order-entry__control order-entry__control--price">
                    <span className="order-entry__label">Price</span>
                    <div className="order-entry__price-row">
                        <input
                            className="order-entry__input"
                            type="text"
                            inputMode="decimal"
                            placeholder="0.00"
                            value={isPeg ? "MID" : priceInput}
                            data-testid="price-input"
                            aria-label="Price"
                            disabled={disabled || isPeg}
                            onChange={(e) => setPriceInput(e.target.value)}
                        />
                        <div className="order-entry__stepper" aria-hidden={disabled || isPeg}>
                            <button
                                type="button"
                                className="order-entry__step order-entry__step--up"
                                data-testid="nudge-up"
                                aria-label="Increase price one tick"
                                disabled={disabled || isPeg}
                                onClick={() => nudge(1)}
                            >
                                ▲
                            </button>
                            <button
                                type="button"
                                className="order-entry__step order-entry__step--down"
                                data-testid="nudge-down"
                                aria-label="Decrease price one tick"
                                disabled={disabled || isPeg}
                                onClick={() => nudge(-1)}
                            >
                                ▼
                            </button>
                        </div>
                    </div>
                </div>

                <div className="order-entry__qtygroup">
                <div className="order-entry__control order-entry__control--qty">
                    <span className="order-entry__label">Qty</span>
                    <FieldMenu
                        testId="qty"
                        ariaLabel="Quantity"
                        options={QTY_MENU}
                        value={qtyInput}
                        editable
                        numeric
                        placeholder="0"
                        inputMode="numeric"
                        disabled={disabled}
                        onSelect={setQtyInput}
                        onInput={setQtyInput}
                    />
                </div>

                {showDisplay ? (
                    <div className="order-entry__control order-entry__control--display">
                        <span className="order-entry__label">Display</span>
                        <FieldMenu
                            testId="display"
                            ariaLabel="Display quantity"
                            options={QTY_MENU}
                            value={displayInput}
                            editable
                            numeric
                            placeholder="0"
                            inputMode="numeric"
                            disabled={disabled}
                            onSelect={setDisplayInput}
                            onInput={setDisplayInput}
                        />
                    </div>
                ) : null}
                </div>

                <button
                    type="button"
                    className="order-entry__submit"
                    data-side={side === "BUY" ? "buy" : "sell"}
                    data-testid="order-submit"
                    disabled={disabled}
                    onClick={submit}
                >
                    Submit {side}
                </button>
            </div>

            {error !== null ? (
                <div className="order-entry__error" role="alert" data-testid="order-entry-error">
                    {error}
                </div>
            ) : null}
            </div>
        </div>
    );
});

OrderEntry.displayName = "OrderEntry";
