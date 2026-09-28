/**
 * Manual cancel-by-OrigClOrdID ticket (SRS §3.7), added in P7-7. Distinct from
 * the OpenOrders per-row cancel: this cancels an order by an id the trader types,
 * which may be an order the UI is not tracking (a resting order from before a
 * page reload, where clOrdId is Date.now()-seeded to avoid collisions, or an id
 * known out of band). Requiring the id to match a known myOrders row would make
 * this ticket redundant with the per-row cancel, so it does not; the server stays
 * authoritative (an unknown or uncancellable id simply yields no state change, or
 * an ORDER_REJECTED that updates only a known id).
 *
 * Presentational and callback-driven: it validates the entered id and emits
 * intent via `onCancel(origClOrdId)`, the SAME callback the per-row cancel uses,
 * so App turns both into `send(cancelOrderFrame(nextClOrdId(), origClOrdId))`.
 * One frame-building path; the two cancel routes cannot diverge. It builds no
 * frame and holds no clOrdId generator or socket. The cancelled order id is FIX
 * Tag 41 on the wire; P8-6 removed that tag annotation from this ticket, which
 * now shows a plain "Order ID to cancel" label, message, and aria-label.
 */

import { useState } from "react";

export type CancelValidation =
    | { readonly ok: true; readonly origClOrdId: number }
    | { readonly ok: false; readonly reason: string };

const ID_REASON = "Order ID must be a positive whole number";

/**
 * Pure validation of the entered OrigClOrdID, exported for direct unit testing
 * (mirrors `validateOrderInput`). clOrdId is client-owned and numeric, so a valid
 * id is a positive safe integer: no decimals, no sign, no exponent.
 */
export function validateOrigClOrdId(input: string): CancelValidation {
    const trimmed = input.trim();
    if (!/^\d+$/.test(trimmed)) {
        return { ok: false, reason: ID_REASON };
    }
    const id = Number(trimmed);
    if (!Number.isSafeInteger(id) || id <= 0) {
        return { ok: false, reason: ID_REASON };
    }
    return { ok: true, origClOrdId: id };
}

export interface CancelTicketProps {
    readonly onCancel: (origClOrdId: number) => void;
    readonly disabled?: boolean;
}

export function CancelTicket({ onCancel, disabled = false }: CancelTicketProps) {
    const [idInput, setIdInput] = useState("");
    const [error, setError] = useState<string | null>(null);

    const submit = (): void => {
        if (disabled) return;
        const result = validateOrigClOrdId(idInput);
        if (!result.ok) {
            setError(result.reason);
            return;
        }
        setError(null);
        onCancel(result.origClOrdId);
        setIdInput("");
    };

    return (
        <div className="cancel-ticket">
            <div className="cancel-ticket__field">
                <input
                    className="cancel-ticket__input"
                    type="text"
                    inputMode="numeric"
                    placeholder="0"
                    value={idInput}
                    data-testid="cancel-ticket-input"
                    aria-label="Order ID to cancel"
                    disabled={disabled}
                    onChange={(e) => setIdInput(e.target.value)}
                />
            </div>

            <button
                type="button"
                className="cancel-ticket__submit"
                data-testid="cancel-ticket-submit"
                disabled={disabled}
                onClick={submit}
            >
                Cancel order
            </button>

            {error !== null ? (
                <div className="cancel-ticket__error" role="alert" data-testid="cancel-ticket-error">
                    {error}
                </div>
            ) : null}
        </div>
    );
}