/**
 * Manual order entry ticket (SRS §3.7), refined in P7-7. Presentational and
 * callback-driven: it owns only transient form state (side, the raw price/qty
 * strings, an error) and emits resolved, units-internal intent via `onSubmit`.
 * It never generates a clOrdId, builds a wire frame, or touches the socket; that
 * lives in one place, the App wiring over the single useOrderBook instance, so
 * clOrdId is generated in exactly one place and never derived from server data.
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
 * Controlled-price seam. The chips and nudges write the SAME uncontrolled
 * `priceInput` the user types, so there is one source of truth and the P5-4
 * validation is byte-identical. The external seam for the P7-5 depth curve
 * (wired in P7-10) is the imperative `setPrice(pricePx)` handle, which takes
 * exactly the shape `onPriceSelect` emits. The ref exposes only a price setter,
 * not state ownership, so the P5-6 discipline holds.
 */

import { forwardRef, useImperativeHandle, useState } from "react";

import { formatPrice, parsePrice, EMPTY_PRICE } from "../format";
import type { Side } from "../protocol/messages";

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
export function validateOrderInput(priceInput: string, qtyInput: string): ValidationResult {
    const pricePx = parsePrice(priceInput);
    if (pricePx === null) {
        return { ok: false, reason: PRICE_REASON };
    }

    const qtyTrimmed = qtyInput.trim();
    if (!/^\d+$/.test(qtyTrimmed)) {
        return { ok: false, reason: QTY_REASON };
    }
    const qty = Number(qtyTrimmed);
    if (!Number.isSafeInteger(qty) || qty <= 0) {
        return { ok: false, reason: QTY_REASON };
    }

    return { ok: true, pricePx, qty };
}

/** One cent, in units of $0.0001. The integer-units primitive is the unit end to end. */
export const TICK_PX = 100;

/** Round-lot quantity presets. */
export const QTY_PRESETS = [10, 50, 100, 500] as const;

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
    readonly onSubmit: (side: Side, pricePx: number, qty: number) => void;
    /** When true (e.g. socket not open), the whole ticket is inert and visibly disabled. */
    readonly disabled?: boolean;
    /** Live best bid in units for the Bid chip; -1 (default) disables it. */
    readonly bestBidPx?: number;
    /** Live best ask in units for the Ask chip; -1 (default) disables it. */
    readonly bestAskPx?: number;
    /** Display-only: the client-assigned id the next NEW will use (nextClOrdId.peek()). */
    readonly clOrdIdPreview?: number;
}

export const OrderEntry = forwardRef<OrderEntryHandle, OrderEntryProps>(function OrderEntry(
    { onSubmit, disabled = false, bestBidPx = -1, bestAskPx = -1, clOrdIdPreview },
    ref,
) {
    const [side, setSide] = useState<Side>("BUY");
    const [priceInput, setPriceInput] = useState("");
    const [qtyInput, setQtyInput] = useState("");
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
        const result = validateOrderInput(priceInput, qtyInput);
        if (!result.ok) {
            setError(result.reason);
            return;
        }
        setError(null);
        onSubmit(side, result.pricePx, result.qty);
        // Clear price + qty for the next order; keep the side for repeat fires.
        setPriceInput("");
        setQtyInput("");
    };

    const nudge = (steps: number): void => {
        if (disabled) return;
        const current = parsePrice(priceInput);
        if (current === null) return; // empty or invalid: no base to nudge, so no-op
        applyPrice(nudgePx(current, steps));
    };

    const preset = (value: number): void => {
        if (disabled) return;
        setQtyInput((q) => applyPreset(q, value));
    };

    const midPx = midChipPx(bestBidPx, bestAskPx);

    return (
        <div className="order-entry">
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
                    disabled={disabled || bestBidPx <= 0}
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
                    disabled={disabled || midPx === null}
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
                    disabled={disabled || bestAskPx <= 0}
                    onClick={() => applyPrice(bestAskPx)}
                >
                    <span className="order-entry__chip-label">Ask</span>
                    <span className="order-entry__chip-value">
            {bestAskPx > 0 ? formatPrice(bestAskPx) : EMPTY_PRICE}
          </span>
                </button>
            </div>

            <div className="order-entry__field">
                <div className="order-entry__field-head">
                    <span className="order-entry__label">Price</span>
                </div>
                <div className="order-entry__price-row">
                    <button
                        type="button"
                        className="order-entry__nudge"
                        data-testid="nudge-down"
                        aria-label="Decrease price one tick"
                        disabled={disabled}
                        onClick={() => nudge(-1)}
                    >
                        −
                    </button>
                    <input
                        className="order-entry__input"
                        type="text"
                        inputMode="decimal"
                        placeholder="0.00"
                        value={priceInput}
                        data-testid="price-input"
                        aria-label="Price"
                        disabled={disabled}
                        onChange={(e) => setPriceInput(e.target.value)}
                    />
                    <button
                        type="button"
                        className="order-entry__nudge"
                        data-testid="nudge-up"
                        aria-label="Increase price one tick"
                        disabled={disabled}
                        onClick={() => nudge(1)}
                    >
                        +
                    </button>
                </div>
            </div>

            <div className="order-entry__field">
                <div className="order-entry__field-head">
                    <span className="order-entry__label">Qty</span>
                </div>
                <input
                    className="order-entry__input"
                    type="text"
                    inputMode="numeric"
                    placeholder="0"
                    value={qtyInput}
                    data-testid="qty-input"
                    aria-label="Quantity"
                    disabled={disabled}
                    onChange={(e) => setQtyInput(e.target.value)}
                />
                <div className="order-entry__presets" role="group" aria-label="Quantity presets">
                    {QTY_PRESETS.map((p) => (
                        <button
                            key={p}
                            type="button"
                            className="order-entry__preset"
                            data-testid={`qty-preset-${p}`}
                            disabled={disabled}
                            onClick={() => preset(p)}
                        >
                            {p}
                        </button>
                    ))}
                </div>
            </div>

            <div className="order-entry__clordid" data-testid="order-entry-clordid">
                <span className="order-entry__label">Order ID</span>
                <span
                    className="order-entry__clordid-value"
                    data-testid="order-entry-clordid-value"
                >
          {clOrdIdPreview !== undefined ? clOrdIdPreview : EMPTY_PRICE}
        </span>
            </div>

            <button
                type="button"
                className="order-entry__submit"
                data-testid="order-submit"
                disabled={disabled}
                onClick={submit}
            >
                Submit {side}
            </button>

            {error !== null ? (
                <div className="order-entry__error" role="alert" data-testid="order-entry-error">
                    {error}
                </div>
            ) : null}
        </div>
    );
});

OrderEntry.displayName = "OrderEntry";
