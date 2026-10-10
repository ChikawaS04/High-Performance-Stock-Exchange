import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { createRef } from "react";

import { OrderEntry, validateOrderInput } from "../src/components/OrderEntry";
import type { OrderEntryHandle } from "../src/components/OrderEntry";

// P5-0's no-`globals` stance means RTL's auto-cleanup never registers; wire it
// explicitly so renders don't bleed across tests.
afterEach(cleanup);

const price = () => screen.getByTestId("price-input") as HTMLInputElement;
const qty = () => screen.getByTestId("qty-input") as HTMLInputElement;

describe("validateOrderInput", () => {
    it("accepts a valid price and quantity, returning integer units and an int qty", () => {
        expect(validateOrderInput("150.25", "10")).toEqual({ ok: true, pricePx: 1502500, qty: 10 });
        expect(validateOrderInput("0.05", "1")).toEqual({ ok: true, pricePx: 500, qty: 1 });
        expect(validateOrderInput("150", "3")).toEqual({ ok: true, pricePx: 1500000, qty: 3 });
        expect(validateOrderInput("  150.00  ", " 4 ")).toEqual({ ok: true, pricePx: 1500000, qty: 4 });
    });

    it("rejects prices with more than two decimals", () => {
        const r = validateOrderInput("150.255", "10");
        expect(r.ok).toBe(false);
        if (!r.ok) expect(r.reason).toMatch(/price/i);
    });

    it("rejects zero, negative, and non-numeric prices (mirrors backend parsePrice)", () => {
        for (const p of ["0", "0.00", "-1", "abc", "", "1e3", "1,000", "150.", ".5"]) {
            const r = validateOrderInput(p, "10");
            expect(r.ok).toBe(false);
            if (!r.ok) expect(r.reason).toMatch(/price/i);
        }
    });

    it("rejects zero, negative, fractional, and non-numeric quantities", () => {
        for (const q of ["0", "-1", "1.5", "abc", "", " "]) {
            const r = validateOrderInput("150.00", q);
            expect(r.ok).toBe(false);
            if (!r.ok) expect(r.reason).toMatch(/quantity/i);
        }
    });
});

describe("<OrderEntry />", () => {
    it("submits a valid order exactly once with the resolved intent", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.change(price(), { target: { value: "150.25" } });
        fireEvent.change(qty(), { target: { value: "10" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).toHaveBeenCalledTimes(1);
        expect(onSubmit).toHaveBeenCalledWith({
            side: "BUY",
            ordType: "LIMIT",
            tif: "GTC",
            pricePx: 1502500,
            qty: 10,
            displayQty: 0,
        });
    });

    it("emits SELL after toggling side", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.click(screen.getByTestId("side-sell"));
        fireEvent.change(price(), { target: { value: "1.00" } });
        fireEvent.change(qty(), { target: { value: "2" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).toHaveBeenCalledWith({
            side: "SELL",
            ordType: "LIMIT",
            tif: "GTC",
            pricePx: 10000,
            qty: 2,
            displayQty: 0,
        });
    });

    it("blocks invalid input: shows a reason and does not call onSubmit", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.change(price(), { target: { value: "150.255" } });
        fireEvent.change(qty(), { target: { value: "10" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).not.toHaveBeenCalled();
        expect(screen.getByTestId("order-entry-error").textContent).toMatch(/price/i);
    });

    it("clears price and qty after a successful submit, keeping side", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.change(price(), { target: { value: "150.00" } });
        fireEvent.change(qty(), { target: { value: "5" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(price().value).toBe("");
        expect(qty().value).toBe("");
    });

    it("uses plain click handlers, not an HTML form submit", () => {
        const { container } = render(<OrderEntry onSubmit={vi.fn()} />);
        expect(container.querySelector("form")).toBeNull();
    });

    it("is inert and visibly disabled when the disabled prop is set", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} disabled bestBidPx={1500000} bestAskPx={1502500} />);

        expect(price().disabled).toBe(true);
        expect(qty().disabled).toBe(true);
        expect((screen.getByTestId("order-submit") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("chip-bid") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("nudge-up") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("qty-preset-100") as HTMLButtonElement).disabled).toBe(true);

        fireEvent.click(screen.getByTestId("order-submit"));
        expect(onSubmit).not.toHaveBeenCalled();
    });
});

describe("<OrderEntry /> time in force (P14-6)", () => {
    const fillValidOrder = () => {
        fireEvent.change(price(), { target: { value: "150.00" } });
        fireEvent.change(qty(), { target: { value: "5" } });
        fireEvent.click(screen.getByTestId("order-submit"));
    };

    it("defaults to GTC and carries it in the intent", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        expect(screen.getByTestId("tif-gtc").getAttribute("aria-pressed")).toBe("true");
        fillValidOrder();
        expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ tif: "GTC" }));
    });

    it("sends the selected time in force in the intent", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.click(screen.getByTestId("tif-ioc"));
        expect(screen.getByTestId("tif-ioc").getAttribute("aria-pressed")).toBe("true");
        fillValidOrder();
        expect(onSubmit).toHaveBeenLastCalledWith(
            expect.objectContaining({ tif: "IOC", ordType: "LIMIT", displayQty: 0 }),
        );

        fireEvent.click(screen.getByTestId("tif-fok"));
        fillValidOrder();
        expect(onSubmit).toHaveBeenLastCalledWith(expect.objectContaining({ tif: "FOK" }));
    });

    it("keeps the chosen time in force across a successful submit", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.click(screen.getByTestId("tif-fok"));
        fillValidOrder();
        // Only price and qty clear; side and time in force persist for repeat fires.
        expect(screen.getByTestId("tif-fok").getAttribute("aria-pressed")).toBe("true");
        expect(price().value).toBe("");
    });

    it("disables the time-in-force control when the ticket is disabled", () => {
        render(<OrderEntry onSubmit={vi.fn()} disabled />);
        expect((screen.getByTestId("tif-gtc") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("tif-ioc") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("tif-fok") as HTMLButtonElement).disabled).toBe(true);
    });
});

describe("<OrderEntry /> reference chips (P7-7)", () => {
    it("populates exactly the current best bid / mid / ask in dollars", () => {
        render(<OrderEntry onSubmit={vi.fn()} bestBidPx={1500000} bestAskPx={1502500} />);

        fireEvent.click(screen.getByTestId("chip-bid"));
        expect(price().value).toBe("150.00"); // exact best bid

        fireEvent.click(screen.getByTestId("chip-ask"));
        expect(price().value).toBe("150.25"); // exact best ask

        fireEvent.click(screen.getByTestId("chip-mid"));
        expect(price().value).toBe("150.13"); // sub-penny mid 1501250 snapped up to 1501300
    });

    it("submits the exact chip units through validation and onSubmit", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} bestBidPx={1500000} bestAskPx={1505000} />);

        fireEvent.change(qty(), { target: { value: "10" } });
        fireEvent.click(screen.getByTestId("chip-mid")); // even tick -> exact 1502500
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).toHaveBeenCalledWith({
            side: "BUY",
            ordType: "LIMIT",
            tif: "GTC",
            pricePx: 1502500,
            qty: 10,
            displayQty: 0,
        });
    });

    it("disables a chip when its side is absent, and all chips with no book", () => {
        const { rerender } = render(<OrderEntry onSubmit={vi.fn()} bestBidPx={1500000} bestAskPx={-1} />);
        expect((screen.getByTestId("chip-bid") as HTMLButtonElement).disabled).toBe(false);
        expect((screen.getByTestId("chip-ask") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("chip-mid") as HTMLButtonElement).disabled).toBe(true);

        rerender(<OrderEntry onSubmit={vi.fn()} />);
        expect((screen.getByTestId("chip-bid") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("chip-ask") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("chip-mid") as HTMLButtonElement).disabled).toBe(true);
    });
});

describe("<OrderEntry /> tick nudges (P7-7)", () => {
    it("nudges the price up and down by one cent", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);
        fireEvent.change(price(), { target: { value: "150.00" } });

        fireEvent.click(screen.getByTestId("nudge-up"));
        expect(price().value).toBe("150.01");

        fireEvent.click(screen.getByTestId("nudge-down"));
        fireEvent.click(screen.getByTestId("nudge-down"));
        expect(price().value).toBe("149.99");
    });

    it("never produces a non-positive price, clamping at one cent", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);
        fireEvent.change(price(), { target: { value: "0.01" } });
        fireEvent.click(screen.getByTestId("nudge-down"));
        expect(price().value).toBe("0.01"); // floored, never 0 or negative
    });

    it("is a no-op when the price field is empty or invalid", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);

        fireEvent.click(screen.getByTestId("nudge-up"));
        expect(price().value).toBe(""); // empty stays empty

        fireEvent.change(price(), { target: { value: "abc" } });
        fireEvent.click(screen.getByTestId("nudge-up"));
        expect(price().value).toBe("abc"); // invalid untouched
    });
});

describe("<OrderEntry /> quantity presets (P7-7)", () => {
    it("fills the quantity when the field is empty", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);
        fireEvent.click(screen.getByTestId("qty-preset-100"));
        expect(qty().value).toBe("100");
    });

    it("does not clobber a typed quantity", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);
        fireEvent.change(qty(), { target: { value: "7" } });
        fireEvent.click(screen.getByTestId("qty-preset-500"));
        expect(qty().value).toBe("7");
    });
});

describe("<OrderEntry /> clOrdId field and price seam (P7-7)", () => {
    it("shows the client-assigned id the next NEW will use", () => {
        render(<OrderEntry onSubmit={vi.fn()} clOrdIdPreview={1757000000123} />);
        expect(screen.getByTestId("order-entry-clordid-value").textContent).toBe("1757000000123");
    });

    it("exposes an imperative setPrice that populates the input (the seam the curve feeds)", () => {
        const ref = createRef<OrderEntryHandle>();
        render(<OrderEntry ref={ref} onSubmit={vi.fn()} />);

        act(() => ref.current!.setPrice(1502500));
        expect(price().value).toBe("150.25");
    });
});

describe("<OrderEntry /> FIX annotation cleanup (P8-6)", () => {
    it("shows the plain Order ID label and keeps the auto-assigned id value", () => {
        render(<OrderEntry onSubmit={vi.fn()} clOrdIdPreview={1757000000123} />);

        expect(screen.getByText("Order ID")).not.toBeNull();
        expect(screen.getByTestId("order-entry-clordid-value").textContent).toBe("1757000000123");
    });

    it("drops the tag numbers, the Primitive helper, and the client-assigned caption", () => {
        render(<OrderEntry onSubmit={vi.fn()} clOrdIdPreview={1757000000123} />);

        expect(screen.queryByText("ClOrdId")).toBeNull();
        expect(screen.queryByText(/Tag 11/)).toBeNull();
        expect(screen.queryByText(/Tag 44/)).toBeNull();
        expect(screen.queryByText(/Tag 38/)).toBeNull();
        expect(screen.queryByText(/Primitive/)).toBeNull();
        expect(screen.queryByText(/client-assigned/i)).toBeNull();
    });
});

describe("<OrderEntry /> iceberg display (P14-8)", () => {
    const display = () => screen.getByTestId("display-input") as HTMLInputElement;

    it("shows the Display field for GTC and hides it for IOC and FOK", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);
        expect(screen.queryByTestId("display-input")).not.toBeNull(); // GTC is default

        fireEvent.click(screen.getByTestId("tif-ioc"));
        expect(screen.queryByTestId("display-input")).toBeNull();

        fireEvent.click(screen.getByTestId("tif-fok"));
        expect(screen.queryByTestId("display-input")).toBeNull();

        fireEvent.click(screen.getByTestId("tif-gtc"));
        expect(screen.queryByTestId("display-input")).not.toBeNull();
    });

    it("carries a valid display quantity in the intent as displayQty", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.change(price(), { target: { value: "150.00" } });
        fireEvent.change(qty(), { target: { value: "100" } });
        fireEvent.change(display(), { target: { value: "10" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).toHaveBeenCalledWith(
            expect.objectContaining({ ordType: "LIMIT", tif: "GTC", qty: 100, displayQty: 10 }),
        );
    });

    it("sends displayQty 0 when the field is left empty (a plain order)", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.change(price(), { target: { value: "150.00" } });
        fireEvent.change(qty(), { target: { value: "100" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ displayQty: 0 }));
    });

    it("normalises display == qty to 0 (hides nothing)", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.change(price(), { target: { value: "150.00" } });
        fireEvent.change(qty(), { target: { value: "100" } });
        fireEvent.change(display(), { target: { value: "100" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ displayQty: 0 }));
    });

    it("blocks a display greater than the quantity and does not submit", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.change(price(), { target: { value: "150.00" } });
        fireEvent.change(qty(), { target: { value: "100" } });
        fireEvent.change(display(), { target: { value: "150" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).not.toHaveBeenCalled();
        expect(screen.getByTestId("order-entry-error").textContent).toMatch(/display/i);
    });

    it("ignores a display typed under GTC once IOC is selected, sending displayQty 0", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);

        fireEvent.change(price(), { target: { value: "150.00" } });
        fireEvent.change(qty(), { target: { value: "100" } });
        fireEvent.change(display(), { target: { value: "10" } });
        // Switch to IOC: the field unmounts and its value is ignored, so a valid
        // order still sends with no iceberg rather than being blocked.
        fireEvent.click(screen.getByTestId("tif-ioc"));
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(onSubmit).toHaveBeenCalledTimes(1);
        expect(onSubmit).toHaveBeenCalledWith(
            expect.objectContaining({ tif: "IOC", displayQty: 0 }),
        );
    });

    it("clears the display field after a successful submit", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);

        fireEvent.change(price(), { target: { value: "150.00" } });
        fireEvent.change(qty(), { target: { value: "100" } });
        fireEvent.change(display(), { target: { value: "10" } });
        fireEvent.click(screen.getByTestId("order-submit"));

        expect(display().value).toBe("");
    });
});


describe("<OrderEntry /> midpoint peg (P14-10)", () => {
    const pegQty = () => screen.getByTestId("qty-input") as HTMLInputElement;

    it("defaults the order type to Limit", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);
        expect(screen.getByTestId("ordtype-limit").getAttribute("aria-pressed")).toBe("true");
        expect(screen.getByTestId("ordtype-mid").getAttribute("aria-pressed")).toBe("false");
    });

    it("disables the price input, chips and nudges and shows MID when Mid peg is selected", () => {
        render(<OrderEntry onSubmit={vi.fn()} bestBidPx={1500000} bestAskPx={1502500} />);
        fireEvent.click(screen.getByTestId("ordtype-mid"));
        expect(price().disabled).toBe(true);
        expect(price().value).toBe("MID");
        expect((screen.getByTestId("chip-bid") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("chip-mid") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("chip-ask") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("nudge-up") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("nudge-down") as HTMLButtonElement).disabled).toBe(true);
    });

    it("hides the Display field for a peg even under GTC", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);
        expect(screen.queryByTestId("display-input")).not.toBeNull(); // Limit GTC
        fireEvent.click(screen.getByTestId("ordtype-mid"));
        expect(screen.queryByTestId("display-input")).toBeNull();
    });

    it("submits a peg with the NA price and no display, no price typed", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);
        fireEvent.click(screen.getByTestId("ordtype-mid"));
        fireEvent.change(pegQty(), { target: { value: "100" } });
        fireEvent.click(screen.getByTestId("order-submit"));
        expect(onSubmit).toHaveBeenCalledTimes(1);
        expect(onSubmit).toHaveBeenCalledWith({
            side: "BUY",
            ordType: "PEG_MID",
            tif: "GTC",
            pricePx: -1,
            qty: 100,
            displayQty: 0,
        });
    });

    it("carries the selected time in force on a peg", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);
        fireEvent.click(screen.getByTestId("ordtype-mid"));
        fireEvent.click(screen.getByTestId("tif-ioc"));
        fireEvent.change(pegQty(), { target: { value: "5" } });
        fireEvent.click(screen.getByTestId("order-submit"));
        expect(onSubmit).toHaveBeenCalledWith(
            expect.objectContaining({ ordType: "PEG_MID", tif: "IOC", pricePx: -1, displayQty: 0 }),
        );
    });

    it("blocks a peg submit on an invalid quantity", () => {
        const onSubmit = vi.fn();
        render(<OrderEntry onSubmit={onSubmit} />);
        fireEvent.click(screen.getByTestId("ordtype-mid"));
        fireEvent.change(pegQty(), { target: { value: "0" } });
        fireEvent.click(screen.getByTestId("order-submit"));
        expect(onSubmit).not.toHaveBeenCalled();
        expect(screen.getByTestId("order-entry-error").textContent).toMatch(/quantity/i);
    });

    it("restores a typed limit price when switching back to Limit", () => {
        render(<OrderEntry onSubmit={vi.fn()} />);
        fireEvent.change(price(), { target: { value: "150.25" } });
        fireEvent.click(screen.getByTestId("ordtype-mid"));
        expect(price().value).toBe("MID");
        fireEvent.click(screen.getByTestId("ordtype-limit"));
        expect(price().disabled).toBe(false);
        expect(price().value).toBe("150.25");
    });

    it("disables the order-type control when the ticket is disabled", () => {
        render(<OrderEntry onSubmit={vi.fn()} disabled />);
        expect((screen.getByTestId("ordtype-limit") as HTMLButtonElement).disabled).toBe(true);
        expect((screen.getByTestId("ordtype-mid") as HTMLButtonElement).disabled).toBe(true);
    });
});
