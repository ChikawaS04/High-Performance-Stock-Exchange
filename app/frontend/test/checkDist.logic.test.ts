import { describe, expect, it } from "vitest";

import { FORBIDDEN, findForbidden } from "../scripts/checkDist.mjs";

describe("findForbidden (postbuild dist/ check)", () => {
    it("covers the backend host and both Alpaca key name forms", () => {
        expect(FORBIDDEN).toEqual(["localhost:8080", "ALPACA_", "APCA-"]);
    });

    it("finds each forbidden string", () => {
        expect(findForbidden('new WebSocket("ws://localhost:8080/ws")')).toEqual(["localhost:8080"]);
        expect(findForbidden("env.ALPACA_SECRET_KEY")).toEqual(["ALPACA_"]);
        expect(findForbidden('headers:{"APCA-API-KEY-ID":k}')).toEqual(["APCA-"]);
        expect(findForbidden("localhost:8080 ALPACA_KEY_ID APCA-API")).toEqual(["localhost:8080", "ALPACA_", "APCA-"]);
    });

    it("finds nothing in a clean bundle", () => {
        expect(findForbidden('fetch("/market/ignition");new WebSocket(u("/ws",location))')).toEqual([]);
        expect(findForbidden("localhost:5173 alpaca_ apca")).toEqual([]);
    });
});
