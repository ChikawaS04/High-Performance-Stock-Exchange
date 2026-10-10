import { describe, expect, it } from "vitest";

import { FIX_TAG_NAMES, fixMsgType, parseFixTags } from "../src/protocol/fix";

const NEW_RAW =
    "8=FIX.4.2\u00019=51\u000135=D\u000111=7\u000155=ASML\u000154=1\u000138=100\u000144=150.25\u000110=123\u0001";

const CANCEL_RAW = "8=FIX.4.2\u00019=23\u000135=F\u000111=9\u000141=7\u000110=045\u0001";

describe("parseFixTags", () => {
    it("parses a known SOH packet into tag/name/value rows in wire order", () => {
        const rows = parseFixTags(NEW_RAW);
        expect(rows.map((r) => r.tag)).toEqual(["8", "9", "35", "11", "55", "54", "38", "44", "10"]);
        expect(rows[0]).toEqual({ tag: "8", name: "BeginString", value: "FIX.4.2" });
        expect(rows.find((r) => r.tag === "35")).toEqual({ tag: "35", name: "MsgType", value: "D" });
        expect(rows.find((r) => r.tag === "11")).toEqual({ tag: "11", name: "ClOrdID", value: "7" });
    });

    it("passes 9= and 10= through verbatim, never recomputed", () => {
        const rows = parseFixTags(NEW_RAW);
        expect(rows.find((r) => r.tag === "9")).toEqual({ tag: "9", name: "BodyLength", value: "51" });
        expect(rows.find((r) => r.tag === "10")).toEqual({ tag: "10", name: "CheckSum", value: "123" });
    });

    it("renders an unknown tag raw, with no name, rather than throwing", () => {
        const raw = "8=FIX.4.2\u00019999=whatever\u000110=001\u0001";
        expect(() => parseFixTags(raw)).not.toThrow();
        const rows = parseFixTags(raw);
        expect(rows.find((r) => r.tag === "9999")).toEqual({
            tag: "9999",
            name: undefined,
            value: "whatever",
        });
    });

    it("never produces a row for tags absent from the produced subset (34/49/56/52)", () => {
        const rows = parseFixTags(NEW_RAW);
        expect(rows.some((r) => r.tag === "34")).toBe(false);
        expect(rows.some((r) => r.tag === "49")).toBe(false);
        expect(rows.some((r) => r.tag === "56")).toBe(false);
        expect(rows.some((r) => r.tag === "52")).toBe(false);
    });

    it("ignores a trailing empty field from the final SOH", () => {
        expect(parseFixTags("8=FIX.4.2\u0001").length).toBe(1);
    });

    it("exposes the exact tag set named in the guide", () => {
        expect(Object.keys(FIX_TAG_NAMES).sort()).toEqual(
            ["10", "11", "18", "35", "38", "40", "41", "44", "54", "55", "59", "111", "8", "9"].sort(),
        );
    });

    it("names the Phase 14 order-attribute tags (P14-10)", () => {
        expect(FIX_TAG_NAMES["18"]).toBe("ExecInst");
        expect(FIX_TAG_NAMES["40"]).toBe("OrdType");
        expect(FIX_TAG_NAMES["59"]).toBe("TimeInForce");
        expect(FIX_TAG_NAMES["111"]).toBe("MaxFloor");
    });
});

describe("fixMsgType", () => {
    it("reads 35= for a NEW packet", () => {
        expect(fixMsgType(NEW_RAW)).toBe("D");
    });

    it("reads 35= for a CANCEL packet", () => {
        expect(fixMsgType(CANCEL_RAW)).toBe("F");
    });

    it("returns undefined when 35= is absent", () => {
        expect(fixMsgType("8=FIX.4.2\u00019=5\u000110=000\u0001")).toBeUndefined();
    });
});