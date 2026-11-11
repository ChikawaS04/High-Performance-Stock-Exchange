import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { usePersistentSetting } from "../src/settings/usePersistentSetting";
import { oneOf } from "../src/settings/persist";

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    localStorage.clear();
});

const KEY = "hpse.v1.test.mode";
const isMode = oneOf(["a", "b"] as const);

function Probe({ id }: { readonly id: string }) {
    const [mode, setMode] = usePersistentSetting<"a" | "b">(KEY, "a", isMode);
    return (
        <div>
            <span data-testid={`${id}-value`}>{mode}</span>
            <button type="button" data-testid={`${id}-set-b`} onClick={() => setMode("b")}>
                b
            </button>
        </div>
    );
}

describe("usePersistentSetting (D10)", () => {
    it("starts at the default on first run", () => {
        render(<Probe id="p" />);
        expect(screen.getByTestId("p-value").textContent).toBe("a");
    });

    it("writes on change, before any unmount", () => {
        render(<Probe id="p" />);
        fireEvent.click(screen.getByTestId("p-set-b"));
        expect(screen.getByTestId("p-value").textContent).toBe("b");
        expect(localStorage.getItem(KEY)).toBe('"b"');
    });

    it("restores the value on remount", () => {
        const first = render(<Probe id="p" />);
        fireEvent.click(screen.getByTestId("p-set-b"));
        first.unmount();
        render(<Probe id="p" />);
        expect(screen.getByTestId("p-value").textContent).toBe("b");
    });

    it("ignores an invalid stored value", () => {
        localStorage.setItem(KEY, '"zzz"');
        render(<Probe id="p" />);
        expect(screen.getByTestId("p-value").textContent).toBe("a");
    });

    it("keeps two live instances of one key in step", () => {
        render(
            <>
                <Probe id="one" />
                <Probe id="two" />
            </>,
        );
        fireEvent.click(screen.getByTestId("one-set-b"));
        expect(screen.getByTestId("two-value").textContent).toBe("b");
    });

    it("still works in memory when storage throws", () => {
        vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
            throw new Error("SecurityError");
        });
        vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
            throw new Error("QuotaExceededError");
        });
        render(<Probe id="p" />);
        fireEvent.click(screen.getByTestId("p-set-b"));
        expect(screen.getByTestId("p-value").textContent).toBe("b");
    });
});
