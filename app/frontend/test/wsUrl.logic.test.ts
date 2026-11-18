import { describe, expect, it } from "vitest";

import { DEFAULT_WS_PATH, resolveWsUrl } from "../src/protocol/wsUrl";

const page = (href: string) => {
    const u = new URL(href);
    return { protocol: u.protocol, host: u.host };
};

describe("resolveWsUrl", () => {
    it("defaults to the same-origin /ws", () => {
        expect(DEFAULT_WS_PATH).toBe("/ws");
    });

    it("gives wss: under https:", () => {
        expect(resolveWsUrl("/ws", page("https://host"))).toBe("wss://host/ws");
        expect(resolveWsUrl("/ws", page("https://davidsexchange.com/charts"))).toBe("wss://davidsexchange.com/ws");
    });

    it("gives ws: under http:, keeping the port", () => {
        expect(resolveWsUrl("/ws", page("http://localhost:5173"))).toBe("ws://localhost:5173/ws");
        expect(resolveWsUrl("/ws", page("http://127.0.0.1:4173/trading"))).toBe("ws://127.0.0.1:4173/ws");
    });

    it("passes an absolute ws:// or wss:// override through unchanged", () => {
        expect(resolveWsUrl("ws://localhost:18080/ws", page("https://host"))).toBe("ws://localhost:18080/ws");
        expect(resolveWsUrl("wss://other.example/ws", page("http://localhost:5173"))).toBe("wss://other.example/ws");
        expect(resolveWsUrl("ws://test", page("http://localhost:5173"))).toBe("ws://test");
    });

    it("an absolute http(s) override keeps its host and its own security", () => {
        expect(resolveWsUrl("https://other.example/ws", page("http://localhost:5173"))).toBe("wss://other.example/ws");
        expect(resolveWsUrl("http://other.example:9/ws", page("https://host"))).toBe("ws://other.example:9/ws");
    });

    it("keeps a query on a relative override", () => {
        expect(resolveWsUrl("/ws?x=1", page("https://host"))).toBe("wss://host/ws?x=1");
    });
});
