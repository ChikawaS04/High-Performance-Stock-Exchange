import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { FixInspector } from "../src/components/FixInspector";
import type { InspectorEntry } from "../src/state/reducer";

afterEach(cleanup);

const NEW_RAW =
    "8=FIX.4.2\u00019=51\u000135=D\u000111=7\u000155=ASML\u000154=1\u000138=100\u000144=150.25\u000110=123\u0001";
const CANCEL_RAW = "8=FIX.4.2\u00019=23\u000135=F\u000111=9\u000141=7\u000110=045\u0001";

function fixEntry(raw: string, seqNum: number, timestamp = 1): InspectorEntry {
    return { type: "FIX", direction: "INBOUND", raw, seqNum, timestamp };
}

function execEntry(tradeId: number, timestamp = 2): InspectorEntry {
    return {
        type: "EXEC",
        execType: "ORDER_FILLED",
        orderId: 1,
        tradeId,
        price: 1500000,
        filledQuantity: 4,
        remainingQuantity: 0,
        aggressorOrderId: 1,
        passiveOrderId: 2,
        timestamp,
    };
}

describe("<FixInspector />", () => {
    it("renders nothing when closed", () => {
        render(<FixInspector entries={[]} open={false} onClose={vi.fn()} />);
        expect(screen.queryByTestId("fix-inspector")).toBeNull();
    });

    it("calls onClose on backdrop click, the close button, and Escape", () => {
        const onCloseBackdrop = vi.fn();
        render(<FixInspector entries={[]} open={true} onClose={onCloseBackdrop} />);
        fireEvent.click(screen.getByTestId("fix-inspector-backdrop"));
        expect(onCloseBackdrop).toHaveBeenCalledTimes(1);
        cleanup();

        const onCloseButton = vi.fn();
        render(<FixInspector entries={[]} open={true} onClose={onCloseButton} />);
        fireEvent.click(screen.getByTestId("fix-inspector-close"));
        expect(onCloseButton).toHaveBeenCalledTimes(1);
        cleanup();

        const onCloseEsc = vi.fn();
        render(<FixInspector entries={[]} open={true} onClose={onCloseEsc} />);
        fireEvent.keyDown(document, { key: "Escape" });
        expect(onCloseEsc).toHaveBeenCalledTimes(1);
    });

    it("does not close when clicking inside the dialog", () => {
        const onClose = vi.fn();
        render(<FixInspector entries={[fixEntry(NEW_RAW, 1)]} open={true} onClose={onClose} />);
        fireEvent.click(screen.getByTestId("fix-inspector"));
        expect(onClose).not.toHaveBeenCalled();
    });

    it("shows an inbound entry's raw packet and tag breakdown, selected by default", () => {
        render(<FixInspector entries={[fixEntry(NEW_RAW, 1)]} open={true} onClose={vi.fn()} />);
        expect(screen.getByTestId("fix-inspector-raw").textContent).toContain("|");
        expect(screen.getByTestId("fix-inspector-tags").textContent).toContain("ClOrdID");
    });

    it("shows checksum and body length verbatim from the echo", () => {
        render(<FixInspector entries={[fixEntry(NEW_RAW, 1)]} open={true} onClose={vi.fn()} />);
        const panel = screen.getByTestId("fix-inspector-detail");
        expect(panel.textContent).toContain("51");
        expect(panel.textContent).toContain("123");
    });

    it("shows an outbound entry's EXEC field table and JSON, labelled as EXEC JSON", () => {
        render(<FixInspector entries={[execEntry(1)]} open={true} onClose={vi.fn()} />);
        expect(screen.getByTestId("fix-inspector-exec-fields").textContent).toContain("execType");
        expect(screen.getByTestId("fix-inspector-exec-json").textContent).toContain("ORDER_FILLED");
        expect(screen.getByTestId("fix-inspector-detail").textContent).toContain("EXEC JSON");
    });

    it("never renders a fabricated 35=8 message: outbound entries are EXEC, not FIX", () => {
        render(<FixInspector entries={[execEntry(1)]} open={true} onClose={vi.fn()} />);
        expect(screen.queryByTestId("fix-inspector-raw")).toBeNull();
    });

    it("filters the list by message type", () => {
        const entries = [fixEntry(NEW_RAW, 1, 1), fixEntry(CANCEL_RAW, 2, 2), execEntry(1, 3)];
        render(<FixInspector entries={entries} open={true} onClose={vi.fn()} />);

        fireEvent.click(screen.getByTestId("fix-filter-new"));
        expect(screen.getAllByTestId(/^fix-inspector-row-/).length).toBe(1);

        fireEvent.click(screen.getByTestId("fix-filter-cancel"));
        expect(screen.getAllByTestId(/^fix-inspector-row-/).length).toBe(1);

        fireEvent.click(screen.getByTestId("fix-filter-exec"));
        expect(screen.getAllByTestId(/^fix-inspector-row-/).length).toBe(1);

        fireEvent.click(screen.getByTestId("fix-filter-all"));
        expect(screen.getAllByTestId(/^fix-inspector-row-/).length).toBe(3);
    });

    it("shows an empty state when no message matches the filter", () => {
        render(<FixInspector entries={[execEntry(1)]} open={true} onClose={vi.fn()} />);
        fireEvent.click(screen.getByTestId("fix-filter-new"));
        expect(screen.getByTestId("fix-inspector-empty")).not.toBeNull();
    });

    it("copies the raw SOH string verbatim for an inbound entry", () => {
        const writeText = vi.fn();
        Object.assign(navigator, { clipboard: { writeText } });
        render(<FixInspector entries={[fixEntry(NEW_RAW, 1)]} open={true} onClose={vi.fn()} />);

        fireEvent.click(screen.getByTestId("fix-inspector-copy"));

        expect(writeText).toHaveBeenCalledWith(NEW_RAW);
    });

    it("copies the JSON string for an outbound entry", () => {
        const writeText = vi.fn();
        Object.assign(navigator, { clipboard: { writeText } });
        const entry = execEntry(7);
        render(<FixInspector entries={[entry]} open={true} onClose={vi.fn()} />);

        fireEvent.click(screen.getByTestId("fix-inspector-copy"));

        expect(writeText).toHaveBeenCalledWith(JSON.stringify(entry, null, 2));
    });

    it("does not throw when the clipboard API is absent", () => {
        Object.assign(navigator, { clipboard: undefined });
        render(<FixInspector entries={[fixEntry(NEW_RAW, 1)]} open={true} onClose={vi.fn()} />);
        expect(() => fireEvent.click(screen.getByTestId("fix-inspector-copy"))).not.toThrow();
    });
});
