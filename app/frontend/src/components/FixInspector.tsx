/**
 * FIX stream inspector modal (P7-9). The strongest panel in the design, built
 * honestly: the system is FIX inbound, JSON outbound, and the inspector reflects
 * that. Inbound entries show the real echoed SOH bytes from P7-2, byte-identical
 * to what FixParser consumed; the server never recomputes 9= or 10=, and neither
 * does this view. Outbound entries show the actual EXEC JSON frames, labelled as
 * such: the server builds no outbound tag-value message at all (Q7-4), so there
 * is no fabricated 35=8.
 *
 * IN/OUT in this panel means order-flow direction at the matching system (a FIX
 * packet is inbound to the engine; an EXEC report is outbound from it), not the
 * browser WebSocket direction, where both arrive as inbound messages. Stated once
 * here so it cannot mislead.
 *
 * A FIX entry's own seqNum is the P7-2 per-channel transport echo counter,
 * labelled as such. It is NOT a FIX 34= MsgSeqNum (the produced subset carries
 * none, Q7-4) and it is never conflated with the header's client-assigned
 * msgSeqNum (P7-3).
 *
 * Presentational and single-slice: takes the reducer's inspectorLog (P7-9) and
 * renders it. Storage, chronological merge, and the cap all live in reducer.ts.
 * This component owns only the filter and selection UI state.
 */

import { useEffect, useState } from "react";

import type { ExecFrame, FixFrame } from "../protocol/messages";
import type { InspectorEntry } from "../state/reducer";
import { fixMsgType, parseFixTags } from "../protocol/fix";
import { formatClockNanos } from "../format";

export type InspectorFilter = "all" | "new" | "cancel" | "exec";

function isFixEntry(entry: InspectorEntry): entry is FixFrame {
    return entry.type === "FIX";
}

/**
 * Pure predicate over one inspector entry (mirrors passesBlockFilter from
 * TradeTape). "new" and "cancel" match on the FIX packet's own 35= value (D / F);
 * "exec" matches any EXEC entry regardless of execType. Exported for direct unit
 * testing without a DOM.
 */
export function matchesFilter(entry: InspectorEntry, filter: InspectorFilter): boolean {
    if (filter === "all") return true;
    if (filter === "exec") return entry.type === "EXEC";
    if (!isFixEntry(entry)) return false;
    const msgType = fixMsgType(entry.raw);
    return filter === "new" ? msgType === "D" : msgType === "F";
}

/** Display-only SOH rendering: the FIX industry convention, a visible pipe. The
 *  underlying string used for parsing and copying is always the real SOH text. */
function withVisibleSoh(raw: string): string {
    return raw.replace(/\u0001/g, "|");
}

function entryKey(entry: InspectorEntry): string {
    return isFixEntry(entry) ? `fix-${entry.seqNum}-${entry.timestamp}` : `exec-${entry.tradeId}-${entry.timestamp}`;
}

/** The exact payload "Copy packet" copies: raw SOH bytes inbound, JSON outbound. */
function copyPayload(entry: InspectorEntry): string {
    return isFixEntry(entry) ? entry.raw : JSON.stringify(entry, null, 2);
}

const EXEC_FIELD_LABELS: readonly (keyof ExecFrame)[] = [
    "execType",
    "orderId",
    "tradeId",
    "price",
    "filledQuantity",
    "remainingQuantity",
    "aggressorOrderId",
    "passiveOrderId",
    "timestamp",
];

export interface FixInspectorProps {
    readonly entries: readonly InspectorEntry[];
    readonly open: boolean;
    readonly onClose: () => void;
}

export function FixInspector({ entries, open, onClose }: FixInspectorProps) {
    const [filter, setFilter] = useState<InspectorFilter>("all");
    const [selectedKey, setSelectedKey] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        const onKeyDown = (e: KeyboardEvent): void => {
            if (e.key === "Escape") onClose();
        };
        document.addEventListener("keydown", onKeyDown);
        return () => document.removeEventListener("keydown", onKeyDown);
    }, [open, onClose]);

    if (!open) return null;

    const filtered = entries.filter((e) => matchesFilter(e, filter));
    // Falls back to the newest visible entry whenever the filter changes the
    // selection out of view; no separate reset effect needed.
    const selected = filtered.find((e) => entryKey(e) === selectedKey) ?? filtered[0] ?? null;

    const handleCopy = (): void => {
        if (selected === null) return;
        const payload = copyPayload(selected);
        if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
            navigator.clipboard.writeText(payload);
        }
    };

    return (
        <div className="fix-inspector__backdrop" data-testid="fix-inspector-backdrop" onClick={onClose}>
            <div
                className="fix-inspector"
                role="dialog"
                aria-modal="true"
                aria-label="FIX stream inspector"
                data-testid="fix-inspector"
                onClick={(e) => e.stopPropagation()}
            >
                <div className="fix-inspector__header">
                    <h2 className="fix-inspector__title">FIX stream inspector</h2>
                    <button
                        type="button"
                        className="fix-inspector__close"
                        data-testid="fix-inspector-close"
                        onClick={onClose}
                        aria-label="Close inspector"
                    >
                        ×
                    </button>
                </div>
                <p className="fix-inspector__subtitle">
                    IN / OUT means order-flow direction at the matching system, not the
                    WebSocket direction. IN is the real inbound FIX bytes the server
                    parsed. OUT is the actual EXEC JSON frames, since the server builds
                    no outbound FIX.
                </p>

                <div className="fix-inspector__filters">
                    {(["all", "new", "cancel", "exec"] as const).map((f) => (
                        <button
                            key={f}
                            type="button"
                            className={`fix-inspector__filter-btn${filter === f ? " fix-inspector__filter-btn--active" : ""}`}
                            data-testid={`fix-filter-${f}`}
                            onClick={() => setFilter(f)}
                        >
                            {f.toUpperCase()}
                        </button>
                    ))}
                </div>

                <div className="fix-inspector__body">
                    <div className="fix-inspector__list" data-testid="fix-inspector-list">
                        {filtered.length === 0 ? (
                            <div className="fix-inspector__empty" data-testid="fix-inspector-empty">
                                No messages yet
                            </div>
                        ) : (
                            filtered.map((entry) => {
                                const key = entryKey(entry);
                                const isSelected = selected !== null && entryKey(selected) === key;
                                const direction = isFixEntry(entry) ? "IN" : "OUT";
                                const msgType = isFixEntry(entry) ? fixMsgType(entry.raw) : undefined;
                                const label = isFixEntry(entry)
                                    ? msgType === "D"
                                        ? "NEW"
                                        : msgType === "F"
                                            ? "CANCEL"
                                            : "FIX"
                                    : entry.execType;
                                return (
                                    <button
                                        key={key}
                                        type="button"
                                        className={`fix-inspector__row${isSelected ? " fix-inspector__row--selected" : ""}`}
                                        data-testid={`fix-inspector-row-${key}`}
                                        onClick={() => setSelectedKey(key)}
                                    >
                    <span
                        className={`fix-inspector__row-dir fix-inspector__row-dir--${direction.toLowerCase()}`}
                    >
                      {direction}
                    </span>
                                        <span className="fix-inspector__row-label">{label}</span>
                                        <span className="fix-inspector__row-time">{formatClockNanos(entry.timestamp)}</span>
                                    </button>
                                );
                            })
                        )}
                    </div>

                    <div className="fix-inspector__detail" data-testid="fix-inspector-detail">
                        {selected === null ? (
                            <div className="fix-inspector__empty">Select a message</div>
                        ) : isFixEntry(selected) ? (
                            <FixDetail entry={selected} />
                        ) : (
                            <ExecDetail entry={selected} />
                        )}

                        {selected !== null && (
                            <button
                                type="button"
                                className="fix-inspector__copy"
                                data-testid="fix-inspector-copy"
                                onClick={handleCopy}
                            >
                                Copy packet
                            </button>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}

function FixDetail({ entry }: { readonly entry: FixFrame }) {
    const rows = parseFixTags(entry.raw);
    const bodyLength = rows.find((r) => r.tag === "9");
    const checksum = rows.find((r) => r.tag === "10");

    return (
        <div className="fix-inspector__panel">
            <div className="fix-inspector__meta">
                <span>Direction: {entry.direction}</span>
                <span>Echo seqNum (transport, not FIX 34=): {entry.seqNum}</span>
            </div>
            <pre className="fix-inspector__raw" data-testid="fix-inspector-raw">
        {withVisibleSoh(entry.raw)}
      </pre>
            <table className="fix-inspector__tags" data-testid="fix-inspector-tags">
                <thead>
                <tr>
                    <th>Tag</th>
                    <th>Name</th>
                    <th>Value</th>
                </tr>
                </thead>
                <tbody>
                {rows.map((row, i) => (
                    <tr key={`${row.tag}-${i}`}>
                        <td>{row.tag}</td>
                        <td>{row.name ?? "—"}</td>
                        <td>{row.value}</td>
                    </tr>
                ))}
                </tbody>
            </table>
            <p className="fix-inspector__verbatim-note">
                BodyLength ({bodyLength?.value ?? "—"}) and CheckSum ({checksum?.value ?? "—"}) are
                shown verbatim from the echo and are never recomputed client-side.
            </p>
        </div>
    );
}

function ExecDetail({ entry }: { readonly entry: ExecFrame }) {
    return (
        <div className="fix-inspector__panel">
            <table className="fix-inspector__tags" data-testid="fix-inspector-exec-fields">
                <thead>
                <tr>
                    <th>Field</th>
                    <th>Value</th>
                </tr>
                </thead>
                <tbody>
                {EXEC_FIELD_LABELS.map((key) => (
                    <tr key={key}>
                        <td>{key}</td>
                        <td>{String(entry[key])}</td>
                    </tr>
                ))}
                </tbody>
            </table>
            <p className="fix-inspector__json-label">EXEC JSON (the server builds no outbound FIX)</p>
            <pre className="fix-inspector__raw" data-testid="fix-inspector-exec-json">
        {JSON.stringify(entry, null, 2)}
      </pre>
        </div>
    );
}