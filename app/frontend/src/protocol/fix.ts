/**
 * Pure FIX tag-value parsing for the P7-9 inspector. No state dependency: this
 * module knows only about FIX strings, never about AppState or InspectorEntry,
 * so the direction of dependency stays state -> protocol, never the reverse.
 *
 * The tag set is the produced-and-parsed subset confirmed in P7-0/Q7-4, plus the
 * Phase 14 order-attribute tags (P14-10): 8 (BeginString), 9 (BodyLength),
 * 10 (CheckSum), 11 (ClOrdID), 18 (ExecInst), 35 (MsgType), 38 (OrderQty),
 * 40 (OrdType), 41 (OrigClOrdID), 44 (Price), 54 (Side), 55 (Symbol),
 * 59 (TimeInForce), 111 (MaxFloor). Tags 34/49/56/52 are absent from the wire and
 * never appear here because this module only reflects what a raw packet actually
 * contains; it never synthesizes a tag.
 *
 * Enum values (35=D, 54=1) render as their literal wire value here, not decoded
 * to a meaning: this table maps tag NUMBER to NAME, not value to semantics,
 * which is the scope the guide names for the tag dictionary.
 */

export const FIX_TAG_NAMES: Readonly<Record<string, string>> = {
    "8": "BeginString",
    "9": "BodyLength",
    "10": "CheckSum",
    "11": "ClOrdID",
    "18": "ExecInst",
    "35": "MsgType",
    "38": "OrderQty",
    "40": "OrdType",
    "41": "OrigClOrdID",
    "44": "Price",
    "54": "Side",
    "55": "Symbol",
    "59": "TimeInForce",
    "111": "MaxFloor",
};

export interface FixTagRow {
    readonly tag: string;
    /** Undefined for a tag outside FIX_TAG_NAMES; the row still renders, raw. */
    readonly name: string | undefined;
    readonly value: string;
}

/**
 * Split a raw SOH-delimited FIX packet into tag/name/value rows, in wire order.
 * An unknown tag renders with no name rather than throwing. 9= and 10= pass
 * through as ordinary fields, verbatim: nothing here recomputes them, matching
 * the P7-0/Q7-4 requirement that checksum and body length never be synthesized.
 * A field with no "=" (malformed input) renders with an empty value rather than
 * throwing, so a corrupt packet still displays something rather than crashing
 * the inspector.
 */
export function parseFixTags(raw: string): FixTagRow[] {
    return raw
        .split("\u0001")
        .filter((field) => field.length > 0)
        .map((field) => {
            const eq = field.indexOf("=");
            if (eq === -1) return { tag: field, name: undefined, value: "" };
            const tag = field.slice(0, eq);
            const value = field.slice(eq + 1);
            return { tag, name: FIX_TAG_NAMES[tag], value };
        });
}

/** The 35= MsgType value of a raw packet, or undefined if absent or malformed. */
export function fixMsgType(raw: string): string | undefined {
    return parseFixTags(raw).find((row) => row.tag === "35")?.value;
}